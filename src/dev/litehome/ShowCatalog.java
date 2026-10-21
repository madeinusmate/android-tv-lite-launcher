package dev.litehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShowCatalog {
    static final String HERO = "hero";
    static final String APPLE = "com.apple.atve.androidtv.appletv";
    static final String MAX = "com.wbd.stream";
    static final String STREMIO = "com.stremio.one";
    static final String WATCH_STREMIO = "stremio";
    static final String WATCH_MAX = "max";
    static final String WATCH_APPLE = "apple";
    private static final int CACHE_VERSION = 4;

    private static final long MAX_AGE_MS = 12L * 60L * 60L * 1000L;
    private static final int DISCOVER_LIMIT = 15;
    private static final int HERO_PREFETCH_AHEAD = 6;
    private static final int JSON_LIMIT = 3 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 8 * 1024 * 1024;
    private static final int TARGET_WIDTH = 1920;

    interface Listener {
        void onUpdated(String packageName);
    }

    static final class Show {
        final String id;
        final String type;
        final String title;
        final String imageUrl;
        final String overview;
        final String rating;
        final String trailer;
        final boolean credit;
        final String year;
        final String genres;
        final String watch;

        Show(String id, String type, String title, String imageUrl, String overview, String rating, String trailer, boolean credit, String year, String genres, String watch) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.imageUrl = imageUrl;
            this.overview = overview;
            this.rating = rating;
            this.trailer = trailer;
            this.credit = credit;
            this.year = year == null ? "" : year;
            this.genres = genres == null ? "" : genres;
            this.watch = watch == null || watch.isEmpty() ? WATCH_STREMIO : watch;
        }

        String stremioUri() {
            if (id == null || id.isEmpty()) {
                return null;
            }
            if ("movie".equals(type)) {
                return "stremio:///detail/movie/" + id + "/" + id;
            }
            return "stremio:///detail/series/" + id;
        }
    }

    private final Context context;
    private final String tmdbKey;
    private final String region;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "show-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService images = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "show-image");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, List<Show>> shows = new HashMap<>();
    private final Map<String, Integer> index = new HashMap<>();
    private final Map<String, Object> fileLocks = new HashMap<>();
    private final LruCache<String, Bitmap> bitmaps = new LruCache<>(4);
    private final Object lock = new Object();
    private Listener listener;
    private volatile int generation;
    private boolean refreshRunning;
    private int heroNextPage = 2;
    private int heroTotalPages = Integer.MAX_VALUE;
    private volatile boolean heroExhausted;
    private volatile boolean heroPageFetching;

    ShowCatalog(Context context, String tmdbKey) {
        this.context = context.getApplicationContext();
        this.tmdbKey = tmdbKey == null ? "" : tmdbKey.trim();
        String country = Locale.getDefault().getCountry();
        this.region = country == null || country.length() != 2
                ? "US"
                : country.toUpperCase(Locale.US);
    }

    static boolean supports(String packageName) {
        return APPLE.equals(packageName) || MAX.equals(packageName) || STREMIO.equals(packageName);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void refresh() {
        synchronized (lock) {
            if (refreshRunning) {
                return;
            }
            refreshRunning = true;
        }
        catalogThread.execute(() -> {
            try {
                refreshAll();
            } finally {
                synchronized (lock) {
                    refreshRunning = false;
                }
            }
        });
    }

    void shutdown() {
        listener = null;
        generation++;
        catalogThread.shutdownNow();
        images.shutdownNow();
    }

    int nextGeneration() {
        return ++generation;
    }

    Show current(String packageName) {
        synchronized (lock) {
            if (HERO.equals(packageName)) {
                return currentHero();
            }
            return at(packageName, 0);
        }
    }

    Show advance(String packageName) {
        synchronized (lock) {
            if (HERO.equals(packageName)) {
                return advanceHero();
            }
            return at(packageName, 1);
        }
    }

    Show retreat(String packageName) {
        synchronized (lock) {
            if (HERO.equals(packageName)) {
                return retreatHero();
            }
            return at(packageName, -1);
        }
    }

    void warmAround(String packageName) {
        prefetch(packageName);
    }

    void decode(Show show, int requestGeneration, BitmapListener callback) {
        Bitmap cached = bitmaps.get(show.imageUrl);
        if (cached != null && !cached.isRecycled()) {
            main.post(() -> deliver(requestGeneration, cached, callback));
            return;
        }
        images.execute(() -> {
            if (requestGeneration != generation) {
                return;
            }
            Bitmap bitmap = loadBitmap(show.imageUrl);
            if (bitmap != null) {
                bitmaps.put(show.imageUrl, bitmap);
            }
            if (requestGeneration != generation) {
                return;
            }
            main.post(() -> deliver(requestGeneration, bitmap, callback));
        });
    }

    private void deliver(int requestGeneration, Bitmap bitmap, BitmapListener callback) {
        if (requestGeneration == generation && listener != null) {
            callback.onBitmap(bitmap);
        }
    }

    private Show at(String packageName, int delta) {
        List<Show> list = shows.get(packageName);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int position = index.containsKey(packageName) ? index.get(packageName) : 0;
        position = Math.floorMod(position + delta, list.size());
        index.put(packageName, position);
        return list.get(position);
    }

    private Show currentHero() {
        List<Show> list = shows.get(HERO);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int position = Math.min(index.getOrDefault(HERO, 0), list.size() - 1);
        index.put(HERO, position);
        maybeScheduleHeroPageFetch(position);
        return list.get(position);
    }

    private Show advanceHero() {
        List<Show> list = shows.get(HERO);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int position = index.getOrDefault(HERO, 0) + 1;
        if (position >= list.size()) {
            if (heroExhausted) {
                position = 0;
            } else {
                scheduleHeroPageFetch();
                position = list.size() - 1;
            }
        }
        index.put(HERO, position);
        maybeScheduleHeroPageFetch(position);
        return list.get(position);
    }

    private Show retreatHero() {
        List<Show> list = shows.get(HERO);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int position = Math.max(0, index.getOrDefault(HERO, 0) - 1);
        index.put(HERO, position);
        maybeScheduleHeroPageFetch(position);
        return list.get(position);
    }

    private void maybeScheduleHeroPageFetch(int position) {
        List<Show> list = shows.get(HERO);
        if (list == null || heroExhausted || tmdbKey.isEmpty()) {
            return;
        }
        if (position + HERO_PREFETCH_AHEAD >= list.size()) {
            scheduleHeroPageFetch();
        }
    }

    private void scheduleHeroPageFetch() {
        if (heroPageFetching || heroExhausted || tmdbKey.isEmpty()) {
            return;
        }
        heroPageFetching = true;
        catalogThread.execute(() -> {
            try {
                int page = heroNextPage;
                List<Show> more = fetchTmdbHeroPage(page);
                heroNextPage = page + 1;
                appendHeroPage(more);
            } catch (IOException ex) {
                Log.w("LiteHome", "Hero page fetch failed", ex);
                heroExhausted = true;
            } finally {
                heroPageFetching = false;
            }
        });
    }

    private void appendHeroPage(List<Show> more) {
        if (more.isEmpty()) {
            heroExhausted = true;
            return;
        }
        List<Show> merged;
        synchronized (lock) {
            List<Show> current = shows.get(HERO);
            if (current == null) {
                current = Collections.emptyList();
            }
            int oldSize = current.size();
            int position = index.getOrDefault(HERO, 0);
            ArrayList<Show> next = new ArrayList<>(current);
            next.addAll(more);
            merged = Collections.unmodifiableList(next);
            shows.put(HERO, merged);
            if (position == oldSize - 1 && next.size() > oldSize) {
                index.put(HERO, position + 1);
            }
        }
        Log.i("LiteHome", HERO + " " + merged.size() + " shows");
        try {
            save(HERO, merged);
        } catch (IOException ex) {
            Log.w("LiteHome", "Hero cache save failed", ex);
        }
        Listener currentListener = listener;
        if (currentListener != null) {
            main.post(() -> {
                Listener active = listener;
                if (active != null) {
                    active.onUpdated(HERO);
                }
            });
        }
    }

    private void refreshAll() {
        boolean fresh = loadCached(HERO);
        prefetch(HERO);
        if (fresh) {
            return;
        }
        try {
            heroNextPage = 2;
            heroExhausted = false;
            heroTotalPages = Integer.MAX_VALUE;
            List<Show> list = fetchHero();
            if (list.isEmpty()) {
                Log.i("LiteHome", "No hero shows");
                return;
            }
            save(HERO, list);
            publish(HERO, list);
            maybeScheduleHeroPageFetch(0);
            prefetch(HERO);
        } catch (IOException ex) {
            Log.w("LiteHome", "Hero catalog failed", ex);
        }
    }

    private boolean loadCached(String packageName) {
        File file = catalogFile(packageName);
        if (!file.isFile()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(new String(readFully(new FileInputStream(file), JSON_LIMIT), StandardCharsets.UTF_8));
            if (HERO.equals(packageName) && root.optInt("version") != CACHE_VERSION) {
                return false;
            }
            if (HERO.equals(packageName) && !tmdbKey.isEmpty() && !"tmdb".equals(root.optString("source"))) {
                return false;
            }
            List<Show> list = parseStored(root);
            if (list.isEmpty()) {
                return false;
            }
            publish(packageName, list);
            if (HERO.equals(packageName)) {
                heroNextPage = root.optInt("heroNextPage", 2);
                heroExhausted = root.optBoolean("heroExhausted", false);
                heroTotalPages = root.optInt("heroTotalPages", Integer.MAX_VALUE);
            }
            long age = System.currentTimeMillis() - root.optLong("fetchedAt");
            return age >= 0 && age < MAX_AGE_MS;
        } catch (IOException | JSONException ex) {
            Log.w("LiteHome", "Bad catalog cache for " + packageName, ex);
            return false;
        }
    }

    private List<Show> fetch(String packageName) throws IOException {
        if (STREMIO.equals(packageName)) {
            return fetchStremio();
        }
        if (tmdbKey.isEmpty()) {
            Log.i("LiteHome", "TMDB key missing, skipping " + packageName);
            return Collections.emptyList();
        }
        return fetchTmdb(packageName);
    }

    private List<Show> fetchTmdb(String packageName) throws IOException {
        int provider = APPLE.equals(packageName) ? 350 : maxProvider(region);
        List<Show> list = provider < 0 ? Collections.emptyList() : discover(provider, region);
        if (!list.isEmpty() || "US".equals(region)) {
            return list;
        }
        int fallback = APPLE.equals(packageName) ? 350 : maxProvider("US");
        return fallback < 0 ? list : discover(fallback, "US");
    }

    private int maxProvider(String watchRegion) throws IOException {
        JSONObject root = getJson("https://api.themoviedb.org/3/watch/providers/tv?api_key="
                + enc(tmdbKey) + "&watch_region=" + enc(watchRegion));
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            throw new IOException(root.optString("status_message", "No Max provider"));
        }
        int fallback = -1;
        for (int i = 0; i < results.length(); i++) {
            JSONObject provider = results.optJSONObject(i);
            if (provider == null) {
                continue;
            }
            String name = provider.optString("provider_name", "");
            if (name.equalsIgnoreCase("Max")) {
                return provider.optInt("provider_id", -1);
            }
            if (name.equalsIgnoreCase("HBO Max")) {
                fallback = provider.optInt("provider_id", -1);
            }
        }
        return fallback;
    }

    private List<Show> discover(int provider, String watchRegion) throws IOException {
        String today = today();
        JSONObject root = getJson("https://api.themoviedb.org/3/discover/tv?api_key=" + enc(tmdbKey)
                + "&watch_region=" + enc(watchRegion)
                + "&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate"
                + "&sort_by=first_air_date.desc"
                + "&first_air_date.lte=" + today
                + "&vote_count.gte=20"
                + "&include_adult=false"
                + "&language=en-US"
                + "&page=1");
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            throw new IOException(root.optString("status_message", "No TV results"));
        }
        List<Show> list = new ArrayList<>();
        for (int i = 0; i < results.length() && list.size() < DISCOVER_LIMIT; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String path = item.optString("backdrop_path", "");
            String title = item.optString("name", "");
            if (path.isEmpty() || title.isEmpty()) {
                continue;
            }
            list.add(new Show("", "series", title, "https://image.tmdb.org/t/p/w1280" + path, "", "", "", true, "", "", WATCH_STREMIO));
        }
        return list;
    }

    private List<Show> fetchHero() throws IOException {
        if (!tmdbKey.isEmpty()) {
            return fetchTmdbHero();
        }
        return fetchCinemetaHero();
    }

    private List<Show> fetchTmdbHero() throws IOException {
        return fetchTmdbHeroPage(1);
    }

    private List<Show> fetchTmdbHeroPage(int page) throws IOException {
        JSONObject root = getJson("https://api.themoviedb.org/3/trending/all/week?api_key="
                + enc(tmdbKey) + "&language=en-US&page=" + page);
        heroTotalPages = root.optInt("total_pages", page);
        if (page >= heroTotalPages) {
            heroExhausted = true;
        }
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            if (page == 1) {
                throw new IOException(root.optString("status_message", "No trending titles"));
            }
            heroExhausted = true;
            return Collections.emptyList();
        }
        List<Show> list = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            try {
                Show show = tmdbShow(item);
                if (show != null) {
                    list.add(show);
                }
            } catch (IOException ex) {
                Log.w("LiteHome", "Skipped a trending title", ex);
            }
        }
        if (results.length() == 0) {
            heroExhausted = true;
        }
        return list;
    }

    private Show tmdbShow(JSONObject item) throws IOException {
        String media = item.optString("media_type", "");
        if (!"movie".equals(media) && !"tv".equals(media)) {
            return null;
        }
        String path = item.optString("backdrop_path", "");
        String title = "movie".equals(media) ? item.optString("title", "") : item.optString("name", "");
        int id = item.optInt("id", -1);
        if (path.isEmpty() || title.isEmpty() || id < 0) {
            return null;
        }
        boolean movie = "movie".equals(media);
        if (!trendingReleased(item, movie)) {
            return null;
        }
        String kind = movie ? "movie" : "tv";
        String root = "https://api.themoviedb.org/3/" + kind + "/" + id;
        String query = "?api_key=" + enc(tmdbKey);
        JSONObject detail;
        try {
            detail = getJson(root + query + "&append_to_response=videos,external_ids,watch/providers&language=en-US");
        } catch (IOException appended) {
            detail = getJson(root + query + "&append_to_response=videos,external_ids&language=en-US");
        }
        if (detail.optJSONObject("watch/providers") == null) {
            try {
                JSONObject providers = getJson(root + "/watch/providers" + query);
                detail.put("watch/providers", providers);
            } catch (IOException | JSONException ex) {
                Log.w("LiteHome", "No providers for " + title);
            }
        }
        JSONObject external = detail.optJSONObject("external_ids");
        String imdb = external == null ? "" : external.optString("imdb_id", "");
        if (!imdb.startsWith("tt")) {
            return null;
        }
        if (isAnime(detail) || !isReleased(detail, movie)) {
            return null;
        }
        String overview = detail.optString("overview", item.optString("overview", ""));
        double vote = detail.optDouble("vote_average", item.optDouble("vote_average", 0));
        return new Show(
                imdb,
                movie ? "movie" : "series",
                title,
                "https://image.tmdb.org/t/p/w1280" + path,
                overview,
                formatRating(vote),
                youtubeTrailer(detail.optJSONObject("videos")),
                true,
                yearLabel(detail, movie),
                genreLine(detail),
                watchTarget(detail, title));
    }

    private String watchTarget(JSONObject detail, String title) {
        JSONObject providers = detail.optJSONObject("watch/providers");
        JSONObject results = providers == null ? null : providers.optJSONObject("results");
        JSONObject country = results == null ? null : results.optJSONObject(region);
        if (country == null && results != null && !"US".equals(region)) {
            country = results.optJSONObject("US");
        }
        JSONArray flatrate = country == null ? null : country.optJSONArray("flatrate");
        boolean max = false;
        boolean hbo = false;
        boolean apple = false;
        StringBuilder names = new StringBuilder();
        if (flatrate != null) {
            for (int i = 0; i < flatrate.length(); i++) {
                JSONObject provider = flatrate.optJSONObject(i);
                if (provider == null) {
                    continue;
                }
                String name = provider.optString("provider_name", "").trim();
                int id = provider.optInt("provider_id", -1);
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(name).append("#").append(id);
                if ("Max".equals(name)) {
                    max = true;
                } else if ("HBO Max".equals(name)) {
                    hbo = true;
                }
                if (id == 350) {
                    apple = true;
                }
            }
        }
        String watch = (max || hbo) ? WATCH_MAX : (apple ? WATCH_APPLE : WATCH_STREMIO);
        Log.i("LiteHome", title + " flatrate=[" + names + "] -> " + watch);
        return watch;
    }

    private static String yearLabel(JSONObject detail, boolean movie) {
        if (movie) {
            return yearOf(detail.optString("release_date", ""));
        }
        String start = yearOf(detail.optString("first_air_date", ""));
        if (start.isEmpty()) {
            return "";
        }
        String status = detail.optString("status", "");
        boolean ended = "Ended".equals(status) || "Canceled".equals(status);
        if (!ended) {
            return start + "\u2013";
        }
        String end = yearOf(detail.optString("last_air_date", ""));
        if (end.isEmpty()) {
            return start + "\u2013";
        }
        return start + "\u2013" + end;
    }

    private static String yearOf(String date) {
        if (date == null || date.length() < 4) {
            return "";
        }
        for (int i = 0; i < 4; i++) {
            char c = date.charAt(i);
            if (c < '0' || c > '9') {
                return "";
            }
        }
        return date.substring(0, 4);
    }

    private static boolean trendingReleased(JSONObject item, boolean movie) {
        String date = movie ? item.optString("release_date", "") : item.optString("first_air_date", "");
        return isReleasedDate(date);
    }

    private static boolean isReleased(JSONObject detail, boolean movie) {
        String status = detail.optString("status", "");
        if ("Canceled".equals(status) || "Rumored".equals(status) || "Planned".equals(status)) {
            return false;
        }
        if (movie) {
            if ("In Production".equals(status) || "Post Production".equals(status)) {
                return false;
            }
            return isReleasedDate(detail.optString("release_date", ""));
        }
        return isReleasedDate(detail.optString("first_air_date", ""));
    }

    private static boolean isReleasedDate(String date) {
        if (date == null || date.length() < 10) {
            return false;
        }
        try {
            LocalDate air = LocalDate.parse(date);
            return !air.isAfter(LocalDate.now());
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isAnime(JSONObject detail) {
        JSONArray genres = detail.optJSONArray("genres");
        boolean animation = false;
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                JSONObject genre = genres.optJSONObject(i);
                if (genre == null) {
                    continue;
                }
                String name = genre.optString("name", "").trim();
                if ("Anime".equalsIgnoreCase(name)) {
                    return true;
                }
                int id = genre.optInt("id", -1);
                if (id == 16 || "Animation".equalsIgnoreCase(name)) {
                    animation = true;
                }
            }
        }
        if (!animation) {
            return false;
        }
        String lang = detail.optString("original_language", "").toLowerCase(Locale.US);
        if ("ja".equals(lang)) {
            return true;
        }
        JSONArray origins = detail.optJSONArray("origin_country");
        if (origins != null) {
            for (int i = 0; i < origins.length(); i++) {
                if ("JP".equals(origins.optString(i, ""))) {
                    return true;
                }
            }
        }
        JSONArray countries = detail.optJSONArray("production_countries");
        if (countries != null) {
            for (int i = 0; i < countries.length(); i++) {
                JSONObject country = countries.optJSONObject(i);
                if (country != null && "JP".equals(country.optString("iso_3166_1", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String genreLine(JSONObject detail) {
        JSONArray array = detail.optJSONArray("genres");
        if (array == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (int i = 0; i < array.length() && count < 4; i++) {
            JSONObject genre = array.optJSONObject(i);
            if (genre == null) {
                continue;
            }
            String name = genre.optString("name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (count > 0) {
                builder.append(" | ");
            }
            builder.append(name);
            count++;
        }
        return builder.toString();
    }

    private static String youtubeTrailer(JSONObject videos) {
        if (videos == null) {
            return "";
        }
        JSONArray results = videos.optJSONArray("results");
        if (results == null) {
            return "";
        }
        String fallback = "";
        for (int i = 0; i < results.length(); i++) {
            JSONObject video = results.optJSONObject(i);
            if (video == null || !"YouTube".equals(video.optString("site"))) {
                continue;
            }
            String key = video.optString("key", "");
            if (key.isEmpty()) {
                continue;
            }
            if ("Trailer".equals(video.optString("type"))) {
                return key;
            }
            if (fallback.isEmpty()) {
                fallback = key;
            }
        }
        return fallback;
    }

    private static String formatRating(double vote) {
        if (vote <= 0) {
            return "";
        }
        return String.format(Locale.US, "%.1f", vote);
    }

    private List<Show> fetchCinemetaHero() throws IOException {
        List<Show> series = readCinemeta("https://v3-cinemeta.strem.io/catalog/series/top.json");
        List<Show> movies = readCinemeta("https://v3-cinemeta.strem.io/catalog/movie/top.json");
        List<Show> mixed = new ArrayList<>();
        int cursor = 0;
        while (cursor < series.size() || cursor < movies.size()) {
            if (cursor < series.size()) {
                mixed.add(series.get(cursor));
            }
            if (cursor < movies.size()) {
                mixed.add(movies.get(cursor));
            }
            cursor++;
        }
        return mixed;
    }

    private List<Show> readCinemeta(String url) throws IOException {
        JSONObject root = getJson(url);
        JSONArray metas = root.optJSONArray("metas");
        if (metas == null) {
            throw new IOException("No Cinemeta catalog");
        }
        List<Show> list = new ArrayList<>();
        for (int i = 0; i < metas.length(); i++) {
            JSONObject item = metas.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            String title = item.optString("name", "");
            String background = item.optString("background", "");
            if (!id.startsWith("tt") || title.isEmpty() || background.isEmpty()
                    || cinemetaIsAnime(item) || !cinemetaReleased(item)) {
                continue;
            }
            background = background.replace("/background/medium/", "/background/large/");
            String type = "movie".equals(item.optString("type", "")) ? "movie" : "series";
            list.add(new Show(
                    id,
                    type,
                    title,
                    background,
                    item.optString("description", ""),
                    item.optString("imdbRating", ""),
                    trailerId(item),
                    false,
                    cinemetaYear(item),
                    cinemetaGenres(item),
                    WATCH_STREMIO));
        }
        return list;
    }

    private static String trailerId(JSONObject item) {
        JSONArray trailers = item.optJSONArray("trailers");
        if (trailers != null) {
            for (int i = 0; i < trailers.length(); i++) {
                JSONObject trailer = trailers.optJSONObject(i);
                if (trailer == null) {
                    continue;
                }
                String source = trailer.optString("source", "");
                if (!source.isEmpty()) {
                    return source;
                }
            }
        }
        JSONArray streams = item.optJSONArray("trailerStreams");
        if (streams != null && streams.length() > 0) {
            JSONObject stream = streams.optJSONObject(0);
            if (stream != null) {
                return stream.optString("ytId", "");
            }
        }
        return "";
    }

    private static String cinemetaYear(JSONObject item) {
        String year = item.optString("releaseInfo", "");
        if (year.isEmpty()) {
            year = item.optString("year", "");
        }
        return year.replace('-', '\u2013').replace('\u2014', '\u2013');
    }

    private static boolean cinemetaReleased(JSONObject item) {
        String info = item.optString("releaseInfo", "");
        if (info.isEmpty()) {
            info = item.optString("year", "");
        }
        if (info.length() < 4) {
            return true;
        }
        try {
            int year = Integer.parseInt(info.substring(0, 4));
            return year <= Calendar.getInstance().get(Calendar.YEAR);
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private static boolean cinemetaIsAnime(JSONObject item) {
        JSONArray array = item.optJSONArray("genres");
        if (array == null) {
            array = item.optJSONArray("genre");
        }
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "").trim();
            if (name.isEmpty()) {
                JSONObject object = array.optJSONObject(i);
                name = object == null ? "" : object.optString("name", "").trim();
            }
            if (name.isEmpty()) {
                continue;
            }
            if ("Anime".equalsIgnoreCase(name)) {
                return true;
            }
            if ("Animation".equalsIgnoreCase(name)) {
                String lang = item.optString("language", item.optString("originalLanguage", "")).toLowerCase(Locale.US);
                if ("ja".equals(lang) || "jp".equals(lang)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String cinemetaGenres(JSONObject item) {
        JSONArray array = item.optJSONArray("genres");
        if (array == null) {
            array = item.optJSONArray("genre");
        }
        if (array == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (int i = 0; i < array.length() && count < 4; i++) {
            String name = array.optString(i, "").trim();
            if (name.isEmpty()) {
                JSONObject object = array.optJSONObject(i);
                name = object == null ? "" : object.optString("name", "").trim();
            }
            if (name.isEmpty()) {
                continue;
            }
            if (count > 0) {
                builder.append(" | ");
            }
            builder.append(name);
            count++;
        }
        return builder.toString();
    }

    private List<Show> fetchStremio() throws IOException {
        return readCinemeta("https://v3-cinemeta.strem.io/catalog/series/top.json");
    }

    private void prefetch(String packageName) {
        Show first;
        Show second;
        Show previous;
        synchronized (lock) {
            List<Show> list = shows.get(packageName);
            if (list == null || list.isEmpty()) {
                return;
            }
            int position = index.containsKey(packageName) ? index.get(packageName) : 0;
            if (HERO.equals(packageName)) {
                position = Math.min(position, list.size() - 1);
                first = list.get(position);
                second = list.get(Math.min(position + 1, list.size() - 1));
                previous = list.get(Math.max(0, position - 1));
                maybeScheduleHeroPageFetch(position);
            } else {
                position = Math.floorMod(position, list.size());
                first = list.get(position);
                second = list.get(Math.floorMod(position + 1, list.size()));
                previous = list.get(Math.floorMod(position - 1, list.size()));
            }
        }
        warm(first);
        if (second != first) {
            warm(second);
        }
        if (previous != first && previous != second) {
            warm(previous);
        }
    }

    private void warm(Show show) {
        if (bitmaps.get(show.imageUrl) != null) {
            return;
        }
        images.execute(() -> {
            if (bitmaps.get(show.imageUrl) != null) {
                return;
            }
            Bitmap bitmap = loadBitmap(show.imageUrl);
            if (bitmap != null) {
                bitmaps.put(show.imageUrl, bitmap);
            }
        });
    }

    private Bitmap loadBitmap(String url) {
        synchronized (lockFor(url)) {
            Bitmap cached = bitmaps.get(url);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
            File file = imageFile(url);
            if (!file.isFile() || file.length() == 0) {
                try {
                    download(url, file);
                } catch (IOException ex) {
                    Log.w("LiteHome", "Image failed", ex);
                    file.delete();
                    return null;
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete();
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, TARGET_WIDTH);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) {
                file.delete();
            }
            return bitmap;
        }
    }

    private void download(String url, File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdir");
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > IMAGE_LIMIT) {
                        throw new IOException("image too large");
                    }
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("rename");
        }
    }

    private JSONObject getJson(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            InputStream stream = code == HttpURLConnection.HTTP_OK
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = stream == null ? "" : new String(readFully(stream, JSON_LIMIT), StandardCharsets.UTF_8);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try {
                return new JSONObject(body);
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LiteHome");
        return connection;
    }

    private void publish(String packageName, List<Show> list) {
        synchronized (lock) {
            shows.put(packageName, Collections.unmodifiableList(new ArrayList<>(list)));
        }
        Log.i("LiteHome", packageName + " " + list.size() + " shows");
        Listener currentListener = listener;
        if (currentListener != null) {
            main.post(() -> {
                Listener active = listener;
                if (active != null) {
                    active.onUpdated(packageName);
                }
            });
        }
    }

    private void save(String packageName, List<Show> list) throws IOException {
        JSONArray array = new JSONArray();
        for (Show show : list) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", show.id);
                item.put("type", show.type);
                item.put("title", show.title);
                item.put("image", show.imageUrl);
                item.put("overview", show.overview);
                item.put("rating", show.rating);
                item.put("trailer", show.trailer);
                item.put("credit", show.credit);
                item.put("year", show.year);
                item.put("genres", show.genres);
                item.put("watch", show.watch);
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
            array.put(item);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("fetchedAt", System.currentTimeMillis());
            root.put("version", CACHE_VERSION);
            root.put("source", tmdbKey.isEmpty() ? "cinemeta" : "tmdb");
            if (HERO.equals(packageName)) {
                root.put("heroNextPage", heroNextPage);
                root.put("heroExhausted", heroExhausted);
                root.put("heroTotalPages", heroTotalPages);
            }
            root.put("shows", array);
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        write(catalogFile(packageName), root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Show> parseStored(JSONObject root) {
        JSONArray array = root.optJSONArray("shows");
        if (array == null) {
            return Collections.emptyList();
        }
        List<Show> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = item.optString("title", "");
            String image = item.optString("image", "");
            if (title.isEmpty() || image.isEmpty()) {
                continue;
            }
            list.add(new Show(
                    item.optString("id", ""),
                    item.optString("type", "series"),
                    title,
                    image,
                    item.optString("overview", ""),
                    item.optString("rating", ""),
                    item.optString("trailer", ""),
                    item.optBoolean("credit"),
                    item.optString("year", ""),
                    item.optString("genres", ""),
                    item.optString("watch", WATCH_STREMIO)));
        }
        return list;
    }

    private File catalogFile(String packageName) {
        return new File(new File(context.getFilesDir(), "catalogs"), packageName + ".json");
    }

    private File imageFile(String url) {
        return new File(new File(context.getCacheDir(), "backdrops"), digest(url) + ".img");
    }

    private Object lockFor(String url) {
        synchronized (fileLocks) {
            Object current = fileLocks.get(url);
            if (current == null) {
                current = new Object();
                fileLocks.put(url, current);
            }
            return current;
        }
    }

    private static String digest(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdir");
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("rename");
        }
    }

    private static byte[] readFully(InputStream input, int max) throws IOException {
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > max) {
                    throw new IOException("response too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static int sampleSize(int width, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    private static String today() {
        Calendar calendar = Calendar.getInstance();
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return value;
        }
    }

    interface BitmapListener {
        void onBitmap(Bitmap bitmap);
    }
}
