package dev.litehome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HomeActivity extends Activity {
    private static final int TILE_WIDTH_DP = 132;
    private static final int TILE_HEIGHT_DP = 80;
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SPOTIFY = "com.spotify.tv.android";

    private TextView clock;
    private ImageView store;
    private ImageView settings;
    private TextView cover;
    private FrameLayout dockHost;
    private HorizontalScrollView scroller;
    private LinearLayout apps;
    private FrameLayout menu;
    private TextView menuTitle;
    private Runnable tick;
    private String focusedPackage;
    private int focusedTileId = View.NO_ID;
    private Row menuRow;
    private View reorderTile;
    private ImageView backdrop;
    private ImageView lift;
    private TextView focusName;
    private GlassPanel glass;
    private int nameHeight;
    private Drawable watchedBackdrop;
    private ViewTreeObserver.OnPreDrawListener backdropWatch;
    private ShowCatalog catalog;
    private ImageView backdropFade;
    private View backdropScrim;
    private TextView showTitle;
    private LinearLayout showMeta;
    private TextView showYear;
    private TextView showOverview;
    private TextView showRating;
    private TextView showBadge;
    private TextView showGenres;
    private TextView watch;
    private TextView trailer;
    private GlassPanel watchGlass;
    private GlassPanel trailerGlass;
    private View transport;
    private TextView heroPrev;
    private TextView heroNext;
    private ProgressRing heroRing;
    private View backdropScrimTop;
    private ShowCatalog.Show heroShow;
    private Drawable.ConstantState natureState;
    private final Handler backdropHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyBackdropTask = this::applyBackdrop;
    private final Runnable advanceBackdropTask = this::advanceBackdrop;
    private String pendingCatalog;
    private String shownImage;
    private int backdropToken;
    private boolean natureShowing;
    private FrameLayout rootView;
    private FrameLayout homePage;
    private FrameLayout morePage;
    private ImageView favouriteTile;
    private ImageView favPhoto;
    private ImageView favPhotoOut;
    private GlassPanel favGlass;
    private GlassPanel favGlassOut;
    private int shownBackdrop;
    private int favBannerToken;
    private boolean paging;
    private boolean onMore;
    private int coverStep = LauncherCover.READY;
    private boolean coverReturn;
    private ContentObserver keyboardWatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home);
        installPages();
        clock = findViewById(R.id.clock);
        store = findViewById(R.id.store);
        settings = findViewById(R.id.settings);
        store.setOnFocusChangeListener((v, hasFocus) ->
                v.animate().scaleX(hasFocus ? 1.2f : 1f).scaleY(hasFocus ? 1.2f : 1f).setDuration(160).start());
        dockHost = findViewById(R.id.dock_host);
        settings.setOnClickListener(v -> startActivity(settingsIntent()));
        cover = findViewById(R.id.cover);
        cover.setOnClickListener(v -> openCoverStep());
        settings.setOnFocusChangeListener((v, hasFocus) ->
                v.animate().scaleX(hasFocus ? 1.2f : 1f).scaleY(hasFocus ? 1.2f : 1f).setDuration(160).start());
        scroller = findViewById(R.id.scroller);
        apps = findViewById(R.id.apps);
        menu = findViewById(R.id.menu);
        menuTitle = findViewById(R.id.menu_title);
        TextView reorder = findViewById(R.id.action_reorder);
        TextView info = findViewById(R.id.action_info);
        TextView quit = findViewById(R.id.action_quit);
        trap(reorder, reorder, info);
        trap(info, reorder, quit);
        trap(quit, info, quit);
        reorder.setOnClickListener(v -> startReorder());
        info.setOnClickListener(v -> openAppInfo());
        quit.setOnClickListener(v -> forceStop());
        installChrome();
        menu.addView(new GlassPanel(px(26f), px(28f), 0x8C2A2A2E, 0xB31C1C1E), 0,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tick = () -> {
            clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            clock.postDelayed(tick, 30_000L);
        };
        tick.run();
        catalog = new ShowCatalog(this, getString(R.string.tmdb_api_key));
        catalog.setListener(packageName -> {
            if (!ShowCatalog.HERO.equals(packageName)) {
                return;
            }
            if (shownImage == null) {
                pendingCatalog = ShowCatalog.HERO;
                backdropHandler.removeCallbacks(applyBackdropTask);
                applyBackdrop();
                return;
            }
            if (ShowCatalog.HERO.equals(pendingCatalog)) {
                ShowCatalog.Show show = catalog.current(ShowCatalog.HERO);
                if (show != null) {
                    backdropHandler.removeCallbacks(advanceBackdropTask);
                    showStill(ShowCatalog.HERO, show, 0);
                }
            }
        });
        watch.setOnClickListener(v -> openHero());
        trailer.setOnClickListener(v -> openTrailer());
        heroPrev.setOnClickListener(v -> stepHero(-1));
        heroNext.setOnClickListener(v -> stepHero(1));
        View.OnFocusChangeListener transportFocus = (v, hasFocus) -> paintTransport();
        View.OnHoverListener transportHover = (v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                v.setHovered(true);
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                v.setHovered(false);
            }
            paintTransport();
            return false;
        };
        heroPrev.setOnFocusChangeListener(transportFocus);
        heroNext.setOnFocusChangeListener(transportFocus);
        transport.setOnHoverListener(transportHover);
        heroPrev.setOnHoverListener(transportHover);
        heroNext.setOnHoverListener(transportHover);
        heroRing.setOnHoverListener(transportHover);
        if (getIntent().getBooleanExtra(LauncherCover.EXTRA_COVER, false)) {
            overridePendingTransition(0, 0);
        }
        keyboardWatch = ScreenKeyboard.watch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(LauncherCover.EXTRA_COVER, false)) {
            coverReturn = apps != null && apps.getChildCount() > 0;
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenKeyboard.pin(this);
        if (coverReturn) {
            coverReturn = false;
            if (onMore && favouriteTile != null) {
                favouriteTile.post(favouriteTile::requestFocus);
            } else if (shownImage != null) {
                armAdvance();
            }
            refreshCover();
            return;
        }
        catalog.refresh();
        bindApps();
        bindStore();
        if (onMore && favouriteTile != null) {
            favouriteTile.post(favouriteTile::requestFocus);
        } else if (shownImage != null) {
            armAdvance();
        }
        refreshCover();
    }

    @Override
    protected void onPause() {
        backdropHandler.removeCallbacks(applyBackdropTask);
        backdropHandler.removeCallbacks(advanceBackdropTask);
        heroRing.stop();
        if (reorderTile != null) {
            stopReorder();
        }
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (reorderTile == null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && steerHero(event)) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) {
                shiftReorder(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!down) {
                stopReorder();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        if (keyboardWatch != null) {
            getContentResolver().unregisterContentObserver(keyboardWatch);
        }
        catalog.shutdown();
        backdropHandler.removeCallbacksAndMessages(null);
        clock.removeCallbacks(tick);
        if (backdropWatch != null && backdrop.getViewTreeObserver().isAlive()) {
            backdrop.getViewTreeObserver().removeOnPreDrawListener(backdropWatch);
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (onMore || paging) {
            if (paging) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeMore();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && favouriteAtTop()) {
                closeMore();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && favouriteAtBottom())) {
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (menu.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideMenu();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && (watch.hasFocus() || trailer.hasFocus()
                || heroPrev.hasFocus() || heroNext.hasFocus())) {
            View tile = focusedTile();
            if (tile != null) {
                tile.requestFocus();
            }
            return true;
        }
        if ((heroPrev.hasFocus() || heroNext.hasFocus())
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                && event.getRepeatCount() == 0) {
            stepHero(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (watch.hasFocus() || trailer.hasFocus())) {
            if (transport.getVisibility() == View.VISIBLE) {
                heroPrev.requestFocus();
            } else {
                focusTile();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (heroPrev.hasFocus() || heroNext.hasFocus())) {
            focusTile();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && (heroPrev.hasFocus() || heroNext.hasFocus())) {
            focusLeadingHeroAction();
            return true;
        }
        if (cover.getVisibility() == View.VISIBLE && cover.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                settings.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            View first = apps.getChildCount() > 0 ? apps.getChildAt(0) : null;
            if (first != null && !first.hasFocus()) {
                first.requestFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && settings.hasFocus()) {
            if (store.getVisibility() == View.VISIBLE) {
                store.requestFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && store.hasFocus()) {
            settings.requestFocus();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT && store.hasFocus())
                || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && settings.hasFocus())) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && (settings.hasFocus() || store.hasFocus())) {
            if (cover.getVisibility() == View.VISIBLE) {
                cover.requestFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && (watch.hasFocus() || trailer.hasFocus())) {
            settings.requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !settings.hasFocus() && !store.hasFocus()) {
            focusLeadingHeroAction();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (settings.hasFocus() || store.hasFocus())) {
            View tile = focusedTileId != View.NO_ID ? apps.findViewById(focusedTileId) : null;
            if (tile == null && apps.getChildCount() > 0) {
                tile = apps.getChildAt(0);
            }
            if (tile != null) {
                tile.requestFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && tileFocused() && reorderTile == null) {
            openMore();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU
                && !cover.hasFocus()
                && !settings.hasFocus()
                && !store.hasFocus()
                && !watch.hasFocus()
                && !trailer.hasFocus()
                && !heroPrev.hasFocus()
                && !heroNext.hasFocus()) {
            Row row = rowForPackage(focusedPackage);
            if (row != null) {
                showMenu(row);
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && settings.hasFocus()) {
            startActivity(settingsIntent());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void bindApps() {
        focusName.setVisibility(View.INVISIBLE);
        hideLift();
        String keep = focusedPackage;
        View current = getCurrentFocus();
        if (current != null && current.getTag() instanceof String) {
            keep = (String) current.getTag();
        }
        List<Row> rows = loadRows();
        apps.removeAllViews();
        View restore = null;
        int tileWidth = dp(TILE_WIDTH_DP);
        int tileHeight = dp(TILE_HEIGHT_DP);
        int gap = dp(12);
        for (int i = 0; i < rows.size(); i++) {
            View cell = createCell(rows.get(i), tileWidth, tileHeight, i < rows.size() - 1 ? gap : 0);
            apps.addView(cell);
            if (rows.get(i).packageName.equals(keep)) {
                restore = cell;
            }
        }
        relinkTiles();
        fitDock();
        View target = restore != null ? restore : (apps.getChildCount() > 0 ? apps.getChildAt(0) : null);
        if (target != null) {
            target.post(() -> {
                target.requestFocus();
                pointTilesUp();
            });
        } else {
            pointTilesUp();
        }
    }

    private void pointTilesUp() {
        View leading = leadingHeroAction();
        int up = leading != null ? leading.getId() : R.id.settings;
        for (int i = 0; i < apps.getChildCount(); i++) {
            apps.getChildAt(i).setNextFocusUpId(up);
        }
        int tileId = focusedTileId;
        if (tileId == View.NO_ID && apps.getChildCount() > 0) {
            tileId = apps.getChildAt(0).getId();
        }
        int heroDown = transport.getVisibility() == View.VISIBLE ? R.id.hero_prev : tileId;
        watch.setNextFocusDownId(heroDown);
        trailer.setNextFocusDownId(heroDown);
        if (tileId != View.NO_ID) {
            heroPrev.setNextFocusDownId(tileId);
            heroNext.setNextFocusDownId(tileId);
        }
        int heroUp = leading != null ? leading.getId() : R.id.settings;
        heroPrev.setNextFocusUpId(heroUp);
        heroNext.setNextFocusUpId(heroUp);
    }

    private boolean steerHero(KeyEvent event) {
        if (onMore || paging || menu.getVisibility() == View.VISIBLE) {
            return false;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && (watch.hasFocus() || trailer.hasFocus())) {
            focusTransport();
            return true;
        }
        if (!heroPrev.hasFocus() && !heroNext.hasFocus()) {
            return false;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                && event.getRepeatCount() == 0) {
            stepHero(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusTile();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            focusLeadingHeroAction();
            return true;
        }
        return false;
    }

    private View leadingHeroAction() {
        if (trailer.getVisibility() == View.VISIBLE) {
            return trailer;
        }
        if (watch.getVisibility() == View.VISIBLE) {
            return watch;
        }
        return null;
    }

    private void focusLeadingHeroAction() {
        View action = leadingHeroAction();
        if (action != null) {
            action.requestFocus();
        } else {
            settings.requestFocus();
        }
    }

    private void linkHeroActions(boolean hasTrailer) {
        if (hasTrailer) {
            trailer.setNextFocusLeftId(R.id.trailer);
            trailer.setNextFocusRightId(R.id.watch);
            watch.setNextFocusLeftId(R.id.trailer);
            watch.setNextFocusRightId(R.id.watch);
        } else {
            watch.setNextFocusLeftId(R.id.watch);
            watch.setNextFocusRightId(R.id.watch);
        }
    }

    private void paintTransport() {
        boolean hot = transport.isHovered()
                || heroPrev.isHovered()
                || heroNext.isHovered()
                || heroRing.isHovered()
                || heroPrev.isFocused()
                || heroNext.isFocused();
        transport.setActivated(hot);
    }

    private void focusTransport() {
        heroPrev.setFocusable(true);
        heroNext.setFocusable(true);
        heroPrev.setFocusableInTouchMode(true);
        heroNext.setFocusableInTouchMode(true);
        if (transport.getVisibility() == View.VISIBLE && heroPrev.requestFocus()) {
            return;
        }
        Log.i("LiteHome", "transport focus missed visible=" + (transport.getVisibility() == View.VISIBLE));
        focusTile();
    }

    private void focusTile() {
        View tile = focusedTile();
        if (tile == null && apps.getChildCount() > 0) {
            tile = apps.getChildAt(0);
        }
        if (tile != null) {
            tile.requestFocus();
        }
    }

    private View createCell(Row row, int tileWidth, int tileHeight, int marginEnd) {
        ImageView tile = new ImageView(this);
        tile.setId(View.generateViewId());
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setNextFocusUpId(R.id.settings);
        tile.setTag(row.packageName);
        tile.setContentDescription(row.label);
        tile.setScaleType(ImageView.ScaleType.FIT_XY);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(tileWidth, tileHeight);
        params.setMarginEnd(marginEnd);
        tile.setLayoutParams(params);
        float radius = tileHeight * 0.28f;
        Drawable dockIcon = dockTileIcon(row);
        if (dockIcon != null) {
            tile.setImageBitmap(roundedTile(
                    dockIcon,
                    tileWidth,
                    tileHeight,
                    radius,
                    solidBlackTile(row.packageName),
                    fullBleedTile(row.packageName)));
        }
        tile.setLongClickable(true);
        tile.setOnLongClickListener(v -> {
            showMenu(row);
            return true;
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                showMenu(row);
                return true;
            }
            return false;
        });
        tile.setOnClickListener(v -> open(row));
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                focusedPackage = row.packageName;
                focusedTileId = v.getId();
                settings.setNextFocusDownId(v.getId());
                store.setNextFocusDownId(v.getId());
                v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), true);
                showFocusedTile(v, row.label);
                int heroDown = transport.getVisibility() == View.VISIBLE ? R.id.hero_prev : v.getId();
                watch.setNextFocusDownId(heroDown);
                trailer.setNextFocusDownId(heroDown);
                heroPrev.setNextFocusDownId(v.getId());
                heroNext.setNextFocusDownId(v.getId());
            } else {
                clearFocusedTile(v);
            }
        });
        return tile;
    }

    private void fitDock() {
        dockHost.post(() -> {
            int max = dockHost.getWidth() - dockHost.getPaddingLeft() - dockHost.getPaddingRight();
            scroller.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            apps.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int insetX = dp(12);
            int insetY = dp(10);
            scroller.setClipChildren(true);
            scroller.setClipToPadding(false);
            scroller.setPadding(insetX, insetY, insetX, insetY);
            int tileWidth = dp(TILE_WIDTH_DP);
            int gap = dp(12);
            int fit = Math.max(1, (Math.max(0, max - insetX * 2) + gap) / (tileWidth + gap));
            int shown = Math.max(1, Math.min(fit, apps.getChildCount()));
            int barWidth = shown * tileWidth + (shown - 1) * gap + insetX * 2;
            int barHeight = dp(TILE_HEIGHT_DP) + insetY * 2;
            FrameLayout.LayoutParams scrollerParams = (FrameLayout.LayoutParams) scroller.getLayoutParams();
            scrollerParams.width = barWidth;
            scrollerParams.height = barHeight;
            scrollerParams.topMargin = 0;
            scrollerParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            scroller.setLayoutParams(scrollerParams);
            FrameLayout.LayoutParams glassParams = new FrameLayout.LayoutParams(barWidth, barHeight);
            glassParams.topMargin = 0;
            glassParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            glass.setLayoutParams(glassParams);
            glass.post(() -> {
                View tile = focusedTile();
                if (tile != null && tile.hasFocus()) {
                    showFocusedTile(tile, String.valueOf(tile.getContentDescription()));
                }
            });
        });
    }

    private void open(Row row) {
        try {
            startActivity(row.intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "Can't open " + row.label, Toast.LENGTH_SHORT).show();
        }
    }

    private List<Row> loadRows() {
        PackageManager pm = getPackageManager();
        Map<String, Row> rows = new LinkedHashMap<>();
        collect(pm, rows, Intent.CATEGORY_LEANBACK_LAUNCHER, false);
        collect(pm, rows, Intent.CATEGORY_LAUNCHER, true);
        List<Row> sorted = new ArrayList<>(rows.values());
        Map<String, Integer> order = savedOrder();
        Collections.sort(sorted, (a, b) -> {
            int ia = order.containsKey(a.packageName) ? order.get(a.packageName) : Integer.MAX_VALUE;
            int ib = order.containsKey(b.packageName) ? order.get(b.packageName) : Integer.MAX_VALUE;
            if (ia != ib) {
                return Integer.compare(ia, ib);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return sorted;
    }

    private void showMenu(Row row) {
        menuRow = row;
        menuTitle.setText(row.label);
        menu.setVisibility(View.VISIBLE);
        apps.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        settings.setFocusable(false);
        store.setFocusable(false);
        heroPrev.setFocusable(false);
        heroNext.setFocusable(false);
        findViewById(R.id.action_reorder).requestFocus();
    }

    private void hideMenu() {
        menu.setVisibility(View.GONE);
        apps.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        settings.setFocusable(true);
        store.setFocusable(true);
        boolean hero = transport.getVisibility() == View.VISIBLE;
        heroPrev.setFocusable(hero);
        heroNext.setFocusable(hero);
        View tile = focusedTileId != View.NO_ID ? apps.findViewById(focusedTileId) : null;
        if (tile == null && focusedPackage != null) {
            for (int i = 0; i < apps.getChildCount(); i++) {
                if (focusedPackage.equals(apps.getChildAt(i).getTag())) {
                    tile = apps.getChildAt(i);
                    break;
                }
            }
        }
        if (tile != null) {
            tile.requestFocus();
        }
    }

    private void startReorder() {
        hideMenu();
        View tile = focusedTile();
        if (tile == null) {
            return;
        }
        reorderTile = tile;
        for (int i = 0; i < apps.getChildCount(); i++) {
            View child = apps.getChildAt(i);
            child.animate().alpha(child == tile ? 1f : 0.4f).setDuration(160).start();
        }
        showFocusedTile(tile, String.valueOf(tile.getContentDescription()));
        lift.animate().cancel();
        lift.animate().scaleX(1.14f).scaleY(1.14f).setDuration(180).start();
    }

    private void stopReorder() {
        View tile = reorderTile;
        reorderTile = null;
        for (int i = 0; i < apps.getChildCount(); i++) {
            apps.getChildAt(i).animate().alpha(1f).setDuration(160).start();
        }
        if (tile == null || !tile.hasFocus()) {
            return;
        }
        showFocusedTile(tile, String.valueOf(tile.getContentDescription()));
        lift.animate().cancel();
        lift.animate().scaleX(1.06f).scaleY(1.06f).setDuration(180).start();
    }

    private void shiftReorder(int delta) {
        View tile = reorderTile;
        int from = apps.indexOfChild(tile);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= apps.getChildCount()) {
            return;
        }
        View neighbor = apps.getChildAt(to);
        apps.removeViewAt(to);
        apps.addView(neighbor, from);
        relinkTiles();
        saveOrder();
        apps.post(() -> {
            if (reorderTile != tile) {
                return;
            }
            tile.requestRectangleOnScreen(new android.graphics.Rect(0, 0, tile.getWidth(), tile.getHeight()), true);
            placeLift(tile);
            moveName(tile);
        });
    }

    private void relinkTiles() {
        int count = apps.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = apps.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.setMarginEnd(i < count - 1 ? dp(12) : 0);
            child.setLayoutParams(lp);
            child.setNextFocusLeftId(i == 0 ? child.getId() : View.NO_ID);
            child.setNextFocusRightId(i == count - 1 ? child.getId() : View.NO_ID);
        }
    }

    private void saveOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < apps.getChildCount(); i++) {
            if (apps.getChildAt(i).getTag() instanceof String pkg) {
                order.add(pkg);
            }
        }
        getSharedPreferences("launcher", MODE_PRIVATE)
                .edit()
                .putString("order", String.join(",", order))
                .apply();
    }

    private void openAppInfo() {
        if (menuRow == null) {
            return;
        }
        Row row = menuRow;
        hideMenu();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + row.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "Can't open settings for " + row.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void forceStop() {
        if (menuRow == null) {
            return;
        }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        String label = menuRow.label;
        boolean stopped = false;
        try {
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(manager, menuRow.packageName);
            stopped = true;
        } catch (ReflectiveOperationException ignored) {
            try {
                manager.killBackgroundProcesses(menuRow.packageName);
                stopped = true;
            } catch (SecurityException ignoredAgain) {
                stopped = false;
            }
        }
        hideMenu();
        Toast.makeText(this, stopped ? "Quit " + label : "Can't quit " + label, Toast.LENGTH_SHORT).show();
    }

    private Row rowForPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (Row row : loadRows()) {
            if (packageName.equals(row.packageName)) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Integer> savedOrder() {
        Map<String, Integer> order = new HashMap<>();
        String saved = getSharedPreferences("launcher", MODE_PRIVATE).getString("order", "");
        if (saved == null || saved.isEmpty()) {
            return order;
        }
        String[] packages = saved.split(",");
        for (int i = 0; i < packages.length; i++) {
            if (!packages[i].isEmpty()) {
                order.put(packages[i], i);
            }
        }
        return order;
    }

    private void trap(TextView item, TextView up, TextView down) {
        item.setNextFocusUpId(up.getId());
        item.setNextFocusDownId(down.getId());
        item.setNextFocusLeftId(item.getId());
        item.setNextFocusRightId(item.getId());
    }

    private void collect(PackageManager pm, Map<String, Row> rows, String category, boolean skipSystem) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        if (found == null) {
            return;
        }
        for (ResolveInfo info : found) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || rows.containsKey(packageName)) {
                continue;
            }
            if ("com.google.android.apps.tv.launcherx".equals(packageName)
                    || "com.google.android.tvlauncher".equals(packageName)
                    || "com.android.tv.settings".equals(packageName)
                    || PLAY_STORE.equals(packageName)) {
                continue;
            }
            boolean system = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (skipSystem && system) {
                continue;
            }
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(packageName, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            CharSequence label = info.loadLabel(pm);
            Drawable icon = info.loadIcon(pm);
            Drawable art = info.activityInfo.loadBanner(pm);
            if (art == null && icon != null && icon.getConstantState() != null) {
                art = icon.getConstantState().newDrawable();
            } else if (art == null) {
                art = icon;
            }
            rows.put(packageName, new Row(
                    packageName,
                    label == null ? packageName : label.toString(),
                    launch,
                    icon,
                    art,
                    false));
        }
    }

    private Drawable dockTileIcon(Row row) {
        if (ShowCatalog.APPLE.equals(row.packageName)) {
            return getDrawable(R.drawable.apple_tv_tile);
        }
        return row.icon;
    }

    private boolean solidBlackTile(String packageName) {
        return SPOTIFY.equals(packageName);
    }

    private boolean fullBleedTile(String packageName) {
        return ShowCatalog.APPLE.equals(packageName);
    }

    private Bitmap roundedTile(
            Drawable drawable, int width, int height, float radius, boolean solidBlack, boolean fullBleed) {
        Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawContain(drawable, new Canvas(raw), width, height, solidBlack, fullBleed);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0, 0, width, height), radius, radius, paint);
        raw.recycle();
        return out;
    }

    private void drawContain(
            Drawable drawable, Canvas canvas, int width, int height, boolean solidBlack, boolean fullBleed) {
        if (fullBleed) {
            fit(drawable, canvas, width, height, true);
            return;
        }
        if (solidBlack) {
            canvas.drawColor(0xFF000000);
            Drawable glyph = drawable;
            if (drawable instanceof AdaptiveIconDrawable adaptive) {
                glyph = adaptive.getForeground();
            }
            if (glyph != null) {
                fit(glyph, canvas, width, height, false);
            }
            return;
        }
        if (drawable instanceof AdaptiveIconDrawable adaptive) {
            Drawable background = adaptive.getBackground();
            if (background != null) {
                fit(background, canvas, width, height, true);
            } else {
                canvas.drawColor(0xFF1C1C1E);
            }
            Drawable foreground = adaptive.getForeground();
            if (foreground != null) {
                fit(foreground, canvas, width, height, false);
            }
            return;
        }
        canvas.drawColor(edgeColor(drawable));
        fit(drawable, canvas, width, height, false);
    }

    private void fit(Drawable drawable, Canvas canvas, int width, int height, boolean cover) {
        int intrinsicWidth = Math.max(drawable.getIntrinsicWidth(), 1);
        int intrinsicHeight = Math.max(drawable.getIntrinsicHeight(), 1);
        float scale = cover
                ? Math.max(width / (float) intrinsicWidth, height / (float) intrinsicHeight)
                : Math.min(width / (float) intrinsicWidth, height / (float) intrinsicHeight);
        int scaledWidth = Math.max(1, Math.round(intrinsicWidth * scale));
        int scaledHeight = Math.max(1, Math.round(intrinsicHeight * scale));
        int left = (width - scaledWidth) / 2;
        int top = (height - scaledHeight) / 2;
        drawable.setBounds(left, top, left + scaledWidth, top + scaledHeight);
        drawable.draw(canvas);
    }

    private int edgeColor(Drawable drawable) {
        Drawable sample = drawable.getConstantState() != null
                ? drawable.getConstantState().newDrawable()
                : drawable;
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        sample.setBounds(0, 0, 4, 4);
        sample.draw(new Canvas(bitmap));
        int color = bitmap.getPixel(0, 0);
        bitmap.recycle();
        if (Color.alpha(color) < 40) {
            return 0xFF1C1C1E;
        }
        return color | 0xFF000000;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindStore() {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(PLAY_STORE);
        if (launch == null) {
            launch = pm.getLaunchIntentForPackage(PLAY_STORE);
        }
        if (launch == null) {
            store.setVisibility(View.GONE);
            store.setOnClickListener(null);
            settings.setNextFocusLeftId(settings.getId());
            return;
        }
        Intent open = launch;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        store.setVisibility(View.VISIBLE);
        settings.setNextFocusLeftId(store.getId());
        store.setOnClickListener(v -> {
            try {
                startActivity(open);
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Can't open Play Store", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static Intent settingsIntent() {
        return new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void refreshCover() {
        int step = LauncherCover.step(this);
        boolean show = step != LauncherCover.READY;
        boolean changed = step != coverStep;
        coverStep = step;
        cover.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        cover.setText(LauncherCover.stepLabel(step));
        if (changed) {
            cover.post(cover::requestFocus);
        }
    }

    private void openCoverStep() {
        Intent intent = LauncherCover.stepIntent(this);
        if (intent == null) {
            return;
        }
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            String message = LauncherCover.step(this) == LauncherCover.SWITCH_HOME
                    ? "Open Settings and choose Google TV as the home app"
                    : "Open Settings, then Accessibility, and turn on Home cover";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }


    private void installPages() {
        rootView = findViewById(R.id.root);
        homePage = new FrameLayout(this);
        homePage.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homePage.setClipChildren(false);
        while (rootView.getChildCount() > 0) {
            View child = rootView.getChildAt(0);
            rootView.removeViewAt(0);
            homePage.addView(child);
        }
        morePage = new FrameLayout(this);
        morePage.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        morePage.setBackgroundColor(Color.BLACK);
        morePage.setClipChildren(false);
        morePage.setFocusable(false);
        morePage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        morePage.setDefaultFocusHighlightEnabled(false);
        installFavourites();
        rootView.addView(morePage);
        rootView.addView(homePage);
        rootView.post(this::placeMorePage);
    }

    private void placeMorePage() {
        if (rootView.getHeight() == 0) {
            rootView.post(this::placeMorePage);
            return;
        }
        if (!onMore) {
            morePage.setTranslationY(rootView.getHeight());
        }
    }

    private boolean tileFocused() {
        View focus = getCurrentFocus();
        return focus != null && focus.getParent() == apps;
    }

    private void openMore() {
        if (paging || onMore || rootView.getHeight() == 0) {
            return;
        }
        paging = true;
        int height = rootView.getHeight();
        backdropHandler.removeCallbacks(advanceBackdropTask);
        morePage.setTranslationY(height);
        morePage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (favouriteTile != null) {
            favouriteTile.requestFocus();
        } else {
            morePage.setFocusable(true);
            morePage.requestFocus();
        }
        homePage.animate().translationY(-height).setDuration(460).setInterpolator(new DecelerateInterpolator()).start();
        morePage.animate().translationY(0f).setDuration(460).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            paging = false;
            onMore = true;
        }).start();
    }

    private void closeMore() {
        if (paging || !onMore) {
            return;
        }
        paging = true;
        onMore = false;
        int height = rootView.getHeight();
        homePage.animate().translationY(0f).setDuration(460).setInterpolator(new DecelerateInterpolator()).start();
        morePage.animate().translationY(height).setDuration(460).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            paging = false;
            View tile = focusedTile();
            if (tile == null && apps.getChildCount() > 0) {
                tile = apps.getChildAt(0);
            }
            morePage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            morePage.setFocusable(false);
            if (tile != null) {
                tile.requestFocus();
            }
            if (shownImage != null) {
                armAdvance();
            }
        }).start();
    }

    private void installFavourites() {
        favPhoto = new ImageView(this);
        favPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        favPhoto.setVisibility(View.INVISIBLE);
        morePage.addView(favPhoto, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        favPhotoOut = new ImageView(this);
        favPhotoOut.setScaleType(ImageView.ScaleType.CENTER_CROP);
        favPhotoOut.setVisibility(View.INVISIBLE);
        morePage.addView(favPhotoOut, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        favGlass = new GlassPanel(0f, px(28f), 0x55FFFFFF, 0x28FFFFFF);
        favGlass.setBlurSource(favPhoto);
        morePage.addView(favGlass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        favGlassOut = new GlassPanel(0f, px(28f), 0x55FFFFFF, 0x28FFFFFF);
        favGlassOut.setBlurSource(favPhotoOut);
        favGlassOut.setVisibility(View.INVISIBLE);
        morePage.addView(favGlassOut, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(48), dp(48), dp(48));
        page.setClipChildren(false);
        morePage.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView heading = new TextView(this);
        heading.setText("Favourites");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
        heading.setTypeface(getResources().getFont(R.font.inter_medium));
        heading.setIncludeFontPadding(false);
        heading.setShadowLayer(px(8f), 0f, px(1f), 0x99000000);
        page.addView(heading);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(28);
        page.addView(grid, gridParams);

        int columns = 6;
        int tileWidth = dp(TILE_WIDTH_DP);
        int tileHeight = dp(TILE_HEIGHT_DP);
        float radius = tileHeight * 0.28f;
        int gap = dp(12);
        Favourite[] favourites = favourites();
        ImageView[] tiles = new ImageView[favourites.length];
        LinearLayout row = null;
        for (int i = 0; i < favourites.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rowParams.topMargin = dp(22);
                }
                grid.addView(row, rowParams);
            }
            Favourite favourite = favourites[i];
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipChildren(false);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % columns != columns - 1) {
                columnParams.setMarginEnd(gap);
            }
            row.addView(column, columnParams);

            ImageView tile = new ImageView(this);
            tile.setId(View.generateViewId());
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setDefaultFocusHighlightEnabled(false);
            tile.setScaleType(ImageView.ScaleType.FIT_XY);
            tile.setImageBitmap(roundedCover(favourite.art, tileWidth, tileHeight, radius, favourite.crop));
            tile.setContentDescription(favourite.title);
            column.addView(tile, new LinearLayout.LayoutParams(tileWidth, tileHeight));
            tiles[i] = tile;
            if (i == 0) {
                favouriteTile = tile;
            }

            TextView label = new TextView(this);
            label.setText(favourite.title);
            label.setTextColor(Color.WHITE);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTypeface(getResources().getFont(R.font.inter_medium));
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setShadowLayer(px(1.2f), 0f, px(0.6f), 0x80000000);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(10);
            column.addView(label, labelParams);

            tile.setOnClickListener(v -> openFavourite(favourite));
            tile.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK || (keyCode == KeyEvent.KEYCODE_DPAD_UP && v.getNextFocusUpId() == v.getId())) {
                    closeMore();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && v.getNextFocusDownId() == v.getId()) {
                    return true;
                }
                return false;
            });
            int backdropRes = favourite.backdrop;
            tile.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().cancel();
                v.animate().scaleX(hasFocus ? 1.06f : 1f).scaleY(hasFocus ? 1.06f : 1f).setDuration(160).start();
                if (hasFocus) {
                    showFavouriteBackdrop(backdropRes);
                }
            });
        }
        linkFavouriteGrid(tiles, columns);
    }

    private void linkFavouriteGrid(ImageView[] tiles, int columns) {
        int count = tiles.length;
        for (int i = 0; i < count; i++) {
            ImageView tile = tiles[i];
            int column = i % columns;
            int below = i + columns;
            if (below >= count) {
                int nextRow = (i / columns) * columns + columns;
                below = nextRow < count ? count - 1 : i;
            }
            int above = i - columns;
            tile.setNextFocusLeftId(column == 0 ? tile.getId() : tiles[i - 1].getId());
            tile.setNextFocusRightId(column == columns - 1 || i == count - 1 ? tile.getId() : tiles[i + 1].getId());
            tile.setNextFocusUpId(above < 0 ? tile.getId() : tiles[above].getId());
            tile.setNextFocusDownId(below == i ? tile.getId() : tiles[below].getId());
        }
    }

    private boolean favouriteAtTop() {
        View focus = getCurrentFocus();
        return focus == null || focus.getNextFocusUpId() == focus.getId() || focus.getNextFocusUpId() == View.NO_ID;
    }

    private boolean favouriteAtBottom() {
        View focus = getCurrentFocus();
        return focus != null && focus.getNextFocusDownId() == focus.getId();
    }

    private Favourite[] favourites() {
        return new Favourite[] {
                new Favourite("Friends", R.drawable.friends, R.drawable.friends_bg,
                        "https://play.max.com/show/52dae4c7-2ab1-4bb9-ab1c-8100fd54e2f9", ShowCatalog.MAX, "Max"),
                new Favourite("The Big Bang Theory", R.drawable.bigbang, R.drawable.bigbang_bg,
                        "https://play.max.com/show/c8ea8e19-cae7-4683-9b62-cdbbed744784", ShowCatalog.MAX, "Max"),
                new Favourite("Silo", R.drawable.silo, R.drawable.silo_bg,
                        "https://tv.apple.com/us/show/silo/umc.cmc.3yksgc857px0k0rqe5zd4jice", ShowCatalog.APPLE, "Apple TV"),
                new Favourite("Silicon Valley", R.drawable.silicon, R.drawable.silicon_bg,
                        "https://play.max.com/show/b4583939-e39f-4b5c-822d-5b6cc186172d", ShowCatalog.MAX, "Max"),
                new Favourite("Young Sheldon", R.drawable.sheldon, R.drawable.sheldon_bg,
                        "https://play.max.com/show/76b03b76-cee8-49f1-bcc5-32f315c5a227", ShowCatalog.MAX, "Max", 0.28f),
                new Favourite("Sex and the City", R.drawable.satc, R.drawable.satc_bg,
                        "https://play.max.com/show/2641fd06-387f-4d92-a322-accb8e180713", ShowCatalog.MAX, "Max"),
                new Favourite("How I Met Your Mother", R.drawable.himym, R.drawable.himym_bg,
                        "stremio:///detail/series/tt0460649", ShowCatalog.STREMIO, "Stremio"),
        };
    }

    private void showFavouriteBackdrop(int resId) {
        if (resId == shownBackdrop || favPhoto == null) {
            return;
        }
        Drawable banner = getDrawable(resId);
        if (banner == null) {
            return;
        }
        shownBackdrop = resId;
        Drawable.ConstantState state = banner.getConstantState();
        favPhoto.setImageDrawable(state != null ? state.newDrawable(getResources()) : banner);
        favGlass.refreshSource();
    }

    private void syncFavouriteBackdrop() {
        if (shownBackdrop != 0 || favPhoto == null || backdrop == null) {
            return;
        }
        Drawable drawable = backdrop.getDrawable();
        if (drawable == null) {
            favPhoto.setImageDrawable(null);
        } else {
            Drawable.ConstantState state = drawable.getConstantState();
            favPhoto.setImageDrawable(state != null ? state.newDrawable(getResources()) : drawable);
        }
        if (favGlass != null) {
            favGlass.invalidate();
        }
    }

    private Bitmap roundedCover(int resId, int width, int height, float radius, float crop) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap source = android.graphics.BitmapFactory.decodeResource(getResources(), resId, options);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
        float extraY = source.getHeight() * scale - height;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - source.getWidth() * scale) / 2f, extraY <= 0f ? 0f : -extraY * crop);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        canvas.drawRoundRect(new RectF(0, 0, width, height), radius, radius, paint);
        source.recycle();
        return out;
    }

    private void openFavourite(Favourite favourite) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(favourite.uri));
        intent.setPackage(favourite.packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "Can't open " + favourite.appLabel, Toast.LENGTH_SHORT).show();
        }
    }

    private void setActionVisible(TextView action, boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        action.setVisibility(state);
        if (menu.getVisibility() != View.VISIBLE) {
            action.setFocusable(visible);
        }
        View parent = (View) action.getParent();
        if (parent instanceof FrameLayout) {
            parent.setVisibility(state);
        }
    }

    private GlassPanel glassBehind(TextView button) {
        ViewGroup row = (ViewGroup) button.getParent();
        int index = row.indexOfChild(button);
        ViewGroup.LayoutParams slot = button.getLayoutParams();
        row.removeView(button);
        FrameLayout host = new FrameLayout(this);
        host.setClipChildren(false);
        GlassPanel panel = new GlassPanel(0f, px(14f), 0xF21C1C1E, 0xE61C1C1E, false);
        host.addView(panel, new FrameLayout.LayoutParams(0, 0));
        button.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.addView(button);
        host.setVisibility(button.getVisibility());
        row.addView(host, index, slot);
        button.setBackgroundResource(R.drawable.hero_action);
        button.setTextColor(getColorStateList(R.color.hero_action_text));
        button.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = button.getWidth();
            int height = button.getHeight();
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (params.width == width && params.height == height) {
                return;
            }
            params.width = width;
            params.height = height;
            panel.setLayoutParams(params);
            panel.setRadius(height * 0.5f);
        });
        Runnable sync = () -> {
            boolean hot = actionHot(button);
            panel.setAlpha(hot ? 0f : 1f);
            float scale = hot ? 1.06f : 1f;
            host.animate().cancel();
            host.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
        };
        button.setOnFocusChangeListener((v, hasFocus) -> sync.run());
        button.setOnHoverListener((v, event) -> {
            sync.run();
            return false;
        });
        host.bringChildToFront(button);
        sync.run();
        return panel;
    }

    private boolean actionHot(TextView button) {
        return button.isFocused()
                || button.isSelected()
                || button.isPressed()
                || button.isActivated()
                || button.isHovered();
    }

    private void refreshGlass() {
        if (glass != null) {
            glass.invalidate();
        }
        if (watchGlass != null) {
            watchGlass.invalidate();
        }
        if (trailerGlass != null) {
            trailerGlass.invalidate();
        }
    }

    private void installChrome() {
        backdrop = findViewById(R.id.backdrop);
        backdropFade = findViewById(R.id.backdrop_fade);
        backdropScrim = findViewById(R.id.backdrop_scrim);
        showTitle = findViewById(R.id.show_title);
        showMeta = findViewById(R.id.show_meta);
        showYear = findViewById(R.id.show_year);
        showOverview = findViewById(R.id.show_overview);
        showRating = findViewById(R.id.show_rating);
        showBadge = findViewById(R.id.show_badge);
        showGenres = findViewById(R.id.show_genres);
        watch = findViewById(R.id.watch);
        trailer = findViewById(R.id.trailer);
        Typeface inter = getResources().getFont(R.font.inter_regular);
        Typeface interMedium = getResources().getFont(R.font.inter_medium);
        showTitle.setTypeface(interMedium);
        showYear.setTypeface(inter);
        showRating.setTypeface(inter);
        showGenres.setTypeface(inter);
        showOverview.setTypeface(inter);
        showOverview.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 3);
        showBadge.setTypeface(interMedium);
        watch.setTypeface(interMedium);
        trailer.setTypeface(interMedium);
        watchGlass = glassBehind(watch);
        trailerGlass = glassBehind(trailer);
        transport = findViewById(R.id.transport);
        heroPrev = findViewById(R.id.hero_prev);
        heroNext = findViewById(R.id.hero_next);
        heroRing = findViewById(R.id.hero_ring);
        backdropScrimTop = findViewById(R.id.backdrop_scrim_top);
        transport.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                placeTransport());
        backdrop.setImageDrawable(new ColorDrawable(getColor(R.color.backdrop_placeholder)));
        focusName = findViewById(R.id.focus_name);
        nameHeight = focusName.getLineHeight() + dp(8);
        focusName.setGravity(Gravity.CENTER);
        focusName.setMaxLines(1);
        focusName.setHorizontallyScrolling(false);
        focusName.setEllipsize(TextUtils.TruncateAt.END);
        focusName.setFocusable(false);
        focusName.setClickable(false);
        focusName.setIncludeFontPadding(true);
        focusName.setShadowLayer(px(1.2f), 0f, px(0.6f), 0x80000000);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, nameHeight);
        nameParams.gravity = Gravity.TOP | Gravity.START;
        focusName.setLayoutParams(nameParams);
        glass = new GlassPanel(px(32f), px(18f), 0x55FFFFFF, 0x28FFFFFF);
        dockHost.setClipToPadding(false);
        dockHost.setClipChildren(false);
        dockHost.addView(glass, 0, new FrameLayout.LayoutParams(0, 0));
        glass.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                placeTransport());
        scroller.setOutlineProvider(new GlassOutline(px(32f)));
        scroller.setClipToOutline(true);
        lift = new ImageView(this);
        lift.setScaleType(ImageView.ScaleType.FIT_XY);
        lift.setFocusable(false);
        lift.setClickable(false);
        lift.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lift.setVisibility(View.INVISIBLE);
        dockHost.addView(lift, new FrameLayout.LayoutParams(0, 0));
        dockHost.bringChildToFront(focusName);
        scroller.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            View tile = focusedTile();
            if (tile != null && focusName.getVisibility() == View.VISIBLE) {
                moveName(tile);
            }
            if (tile != null && lift.getTag() == tile) {
                placeLift(tile);
            }
        });
        backdrop.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                refreshGlass());
        watchedBackdrop = backdrop.getDrawable();
        backdropWatch = () -> {
            Drawable current = backdrop.getDrawable();
            if (current != watchedBackdrop) {
                watchedBackdrop = current;
                refreshGlass();
                syncFavouriteBackdrop();
            }
            return true;
        };
        backdrop.getViewTreeObserver().addOnPreDrawListener(backdropWatch);
        syncFavouriteBackdrop();
    }

    private void requestBackdrop(String packageName) {
        pendingCatalog = packageName != null && ShowCatalog.supports(packageName) ? packageName : null;
        backdropHandler.removeCallbacks(advanceBackdropTask);
        backdropHandler.removeCallbacks(applyBackdropTask);
        backdropHandler.postDelayed(applyBackdropTask, 180);
    }

    private void applyBackdrop() {
        backdropHandler.removeCallbacks(advanceBackdropTask);
        if (pendingCatalog == null) {
            fadeToNature();
            return;
        }
        showStill(pendingCatalog, catalog.current(pendingCatalog), 0);
    }

    private void advanceBackdrop() {
        if (pendingCatalog == null) {
            return;
        }
        showStill(pendingCatalog, catalog.advance(pendingCatalog), 0);
    }

    private void showStill(String packageName, ShowCatalog.Show show, int skips) {
        if (show == null || skips > 2 || !packageName.equals(pendingCatalog)) {
            fadeToNature();
            return;
        }
        if (show.imageUrl.equals(shownImage)) {
            showCaption(show);
            armAdvance();
            return;
        }
        int generation = catalog.nextGeneration();
        catalog.decode(show, generation, bitmap -> {
            if (!packageName.equals(pendingCatalog)) {
                return;
            }
            if (bitmap == null) {
                showStill(packageName, catalog.advance(packageName), skips + 1);
                return;
            }
            crossfadeTo(bitmap, show);
            armAdvance();
        });
    }

    private void armAdvance() {
        backdropHandler.removeCallbacks(advanceBackdropTask);
        long now = SystemClock.uptimeMillis();
        backdropHandler.postDelayed(advanceBackdropTask, 25_000L);
        heroRing.start(now, 25_000L);
        catalog.warmAround(ShowCatalog.HERO);
    }

    private void stepHero(int delta) {
        if (!ShowCatalog.HERO.equals(pendingCatalog)) {
            return;
        }
        ShowCatalog.Show show = delta < 0
                ? catalog.retreat(ShowCatalog.HERO)
                : catalog.advance(ShowCatalog.HERO);
        if (show == null) {
            return;
        }
        backdropHandler.removeCallbacks(advanceBackdropTask);
        heroRing.holdEmpty();
        showStill(ShowCatalog.HERO, show, 0);
    }

    private void showCaption(ShowCatalog.Show show) {
        heroShow = show;
        showTitle.setText(show.title);
        showTitle.setVisibility(View.VISIBLE);
        boolean hasYear = show.year != null && !show.year.isEmpty();
        showYear.setText(show.year);
        showYear.setVisibility(hasYear ? View.VISIBLE : View.GONE);
        boolean hasRating = show.rating != null && !show.rating.isEmpty();
        showRating.setText(show.rating);
        showRating.setVisibility(hasRating ? View.VISIBLE : View.GONE);
        showBadge.setVisibility(hasRating ? View.VISIBLE : View.GONE);
        boolean hasGenres = show.genres != null && !show.genres.isEmpty();
        showGenres.setText(show.genres);
        showGenres.setVisibility(hasGenres ? View.VISIBLE : View.GONE);
        showMeta.setVisibility(hasYear || hasRating || hasGenres ? View.VISIBLE : View.GONE);
        boolean hasOverview = show.overview != null && !show.overview.isEmpty();
        showOverview.setText(show.overview);
        showOverview.setVisibility(hasOverview ? View.VISIBLE : View.GONE);
        watch.setText(watchLabel(show));
        setActionVisible(watch, true);
        boolean hasTrailer = show.trailer != null && !show.trailer.isEmpty();
        setActionVisible(trailer, hasTrailer);
        linkHeroActions(hasTrailer);
        if (trailer.hasFocus() && !hasTrailer) {
            watch.requestFocus();
        }
        setTransportVisible(true);
        pointTilesUp();
    }

    private String watchLabel(ShowCatalog.Show show) {
        if (ShowCatalog.WATCH_MAX.equals(show.watch)) {
            return "Watch on Max";
        }
        if (ShowCatalog.WATCH_APPLE.equals(show.watch)) {
            return "Watch on Apple TV";
        }
        return "Watch";
    }

    private void setTransportVisible(boolean visible) {
        transport.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (menu.getVisibility() != View.VISIBLE) {
            heroPrev.setFocusable(visible);
            heroNext.setFocusable(visible);
        }
        if (!visible) {
            heroRing.stop();
            return;
        }
        transport.post(this::placeTransport);
    }

    private void placeTransport() {
        if (glass == null || transport.getVisibility() != View.VISIBLE || glass.getWidth() == 0 || transport.getWidth() == 0) {
            return;
        }
        View parent = (View) transport.getParent();
        int[] glassLoc = new int[2];
        int[] parentLoc = new int[2];
        glass.getLocationInWindow(glassLoc);
        parent.getLocationInWindow(parentLoc);
        float x = (parent.getWidth() - transport.getWidth()) / 2f;
        float y = glassLoc[1] - parentLoc[1] - transport.getHeight() - dp(12);
        transport.setX(x);
        transport.setY(Math.max(dp(8), y));
    }

    private void openHero() {
        if (heroShow == null) {
            return;
        }
        if (ShowCatalog.WATCH_MAX.equals(heroShow.watch)) {
            openApp(ShowCatalog.MAX, "https://play.max.com", "Max");
            return;
        }
        if (ShowCatalog.WATCH_APPLE.equals(heroShow.watch)) {
            openApp(ShowCatalog.APPLE, "https://tv.apple.com", "Apple TV");
            return;
        }
        if (heroShow.stremioUri() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(heroShow.stremioUri()));
        intent.setPackage(ShowCatalog.STREMIO);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i("LiteHome", "open stremio " + heroShow.title + " " + heroShow.stremioUri());
        try {
            startActivity(intent);
        } catch (RuntimeException ex) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Can't open " + heroShow.title + " in Stremio", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openApp(String packageName, String web, String label) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_VIEW, Uri.parse(web));
            launch.setPackage(packageName);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Log.i("LiteHome", "open " + label + " pkg=" + packageName + " component=" + launch.getComponent());
        try {
            startActivity(launch);
        } catch (RuntimeException ex) {
            Intent fallback = pm.getLaunchIntentForPackage(packageName);
            if (fallback == null) {
                Toast.makeText(this, "Can't open " + label, Toast.LENGTH_SHORT).show();
                return;
            }
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Log.i("LiteHome", "open " + label + " fallback component=" + fallback.getComponent());
            try {
                startActivity(fallback);
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Can't open " + label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openTrailer() {
        if (heroShow == null || heroShow.trailer == null || heroShow.trailer.isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + heroShow.trailer));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "Can't open the trailer", Toast.LENGTH_SHORT).show();
        }
    }

    private void crossfadeTo(Bitmap bitmap, ShowCatalog.Show show) {
        int token = ++backdropToken;
        shownImage = show.imageUrl;
        showCaption(show);
        backdropScrim.animate().alpha(1f).setDuration(520).start();
        backdropScrimTop.animate().alpha(1f).setDuration(520).start();
        Drawable outgoing = snapshotDrawable(backdrop.getDrawable());
        backdrop.setImageBitmap(bitmap);
        natureShowing = false;
        refreshGlass();
        if (outgoing == null) {
            backdropFade.animate().cancel();
            backdropFade.setVisibility(View.INVISIBLE);
            return;
        }
        backdropFade.animate().cancel();
        backdropFade.setImageDrawable(outgoing);
        backdropFade.setAlpha(1f);
        backdropFade.setVisibility(View.VISIBLE);
        backdropFade.animate().alpha(0f).setDuration(520).withEndAction(() -> {
            if (token != backdropToken) {
                return;
            }
            backdropFade.setAlpha(0f);
            backdropFade.setVisibility(View.INVISIBLE);
            backdropFade.setImageDrawable(null);
        }).start();
    }

    private void fadeToNature() {
        backdropHandler.removeCallbacks(advanceBackdropTask);
        shownImage = null;
        heroShow = null;
        showTitle.setVisibility(View.GONE);
        showMeta.setVisibility(View.GONE);
        showOverview.setVisibility(View.GONE);
        setActionVisible(watch, false);
        setActionVisible(trailer, false);
        setTransportVisible(false);
        backdropScrim.animate().alpha(0f).setDuration(520).start();
        backdropScrimTop.animate().alpha(0f).setDuration(520).start();
        if (natureShowing && backdropFade.getVisibility() != View.VISIBLE) {
            return;
        }
        int token = ++backdropToken;
        Drawable outgoing = snapshotDrawable(backdrop.getDrawable());
        backdrop.setImageDrawable(natureDrawable());
        natureShowing = true;
        refreshGlass();
        if (outgoing == null) {
            backdropFade.animate().cancel();
            backdropFade.setVisibility(View.INVISIBLE);
            return;
        }
        backdropFade.animate().cancel();
        backdropFade.setImageDrawable(outgoing);
        backdropFade.setAlpha(1f);
        backdropFade.setVisibility(View.VISIBLE);
        backdropFade.animate().alpha(0f).setDuration(520).withEndAction(() -> {
            if (token != backdropToken) {
                return;
            }
            backdropFade.setAlpha(0f);
            backdropFade.setVisibility(View.INVISIBLE);
            backdropFade.setImageDrawable(null);
        }).start();
    }

    private Drawable natureDrawable() {
        if (natureState == null) {
            Drawable nature = getDrawable(R.drawable.nature);
            if (nature == null) {
                return new ColorDrawable(getColor(R.color.backdrop_placeholder));
            }
            Drawable.ConstantState state = nature.getConstantState();
            if (state == null) {
                return nature;
            }
            natureState = state;
        }
        return natureState.newDrawable(getResources());
    }

    private Drawable snapshotDrawable(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        Drawable.ConstantState state = drawable.getConstantState();
        return state != null ? state.newDrawable(getResources()) : drawable;
    }

    private void showFocusedTile(View tile, String name) {
        String label = reorderTile == tile ? "‹   " + name + "   ›" : name;
        focusName.setText(label);
        if (!tile.isLaidOut() || glass.getWidth() == 0) {
            if (tile.isAttachedToWindow()) {
                tile.post(() -> {
                    if (tile.getId() == focusedTileId && tile.hasFocus()) {
                        showFocusedTile(tile, name);
                    }
                });
            }
            return;
        }
        showLift(tile);
        int inner = Math.max(dp(48), glass.getWidth() - dp(32));
        int textWidth = Math.round(focusName.getPaint().measureText(label)) + dp(22);
        int width = Math.min(Math.max(textWidth, dp(24)), inner);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusName.getLayoutParams();
        boolean resize = lp.width != width || lp.height != nameHeight;
        lp.width = width;
        lp.height = nameHeight;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = 0;
        lp.topMargin = 0;
        if (resize) {
            focusName.setVisibility(View.INVISIBLE);
            focusName.setLayoutParams(lp);
            focusName.post(() -> {
                if (tile.getId() != focusedTileId || !tile.hasFocus()) {
                    return;
                }
                moveName(tile);
                focusName.setVisibility(View.VISIBLE);
            });
            return;
        }
        moveName(tile);
        focusName.setVisibility(View.VISIBLE);
    }

    private void clearFocusedTile(View tile) {
        if (tile.getId() != focusedTileId) {
            return;
        }
        focusName.setVisibility(View.INVISIBLE);
        hideLift();
    }

    private void showLift(View tile) {
        if (!(tile instanceof ImageView image) || !(image.getDrawable() instanceof BitmapDrawable bitmap)) {
            hideLift();
            return;
        }
        boolean fresh = lift.getTag() != tile || lift.getVisibility() != View.VISIBLE;
        lift.setTag(tile);
        lift.setImageBitmap(bitmap.getBitmap());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lift.getLayoutParams();
        if (lp.width != tile.getWidth() || lp.height != tile.getHeight()) {
            lp.width = tile.getWidth();
            lp.height = tile.getHeight();
            lp.gravity = Gravity.TOP | Gravity.START;
            lift.setLayoutParams(lp);
        }
        placeLift(tile);
        lift.setVisibility(View.VISIBLE);
        if (fresh) {
            lift.animate().cancel();
            lift.setScaleX(1f);
            lift.setScaleY(1f);
            lift.animate().scaleX(1.06f).scaleY(1.06f).setDuration(180).start();
        }
    }

    private void placeLift(View tile) {
        int[] tileLoc = new int[2];
        int[] dockLoc = new int[2];
        tile.getLocationInWindow(tileLoc);
        dockHost.getLocationInWindow(dockLoc);
        lift.setX(tileLoc[0] - dockLoc[0]);
        lift.setY(tileLoc[1] - dockLoc[1]);
    }

    private void hideLift() {
        lift.animate().cancel();
        lift.setVisibility(View.INVISIBLE);
        lift.setTag(null);
    }

    private void moveName(View tile) {
        if (glass.getWidth() == 0 || tile.getWidth() == 0 || focusName.getWidth() == 0) {
            return;
        }
        int[] tileLoc = new int[2];
        int[] dockLoc = new int[2];
        tile.getLocationInWindow(tileLoc);
        dockHost.getLocationInWindow(dockLoc);
        int nameWidth = focusName.getWidth();
        float center = tileLoc[0] - dockLoc[0] + tile.getWidth() / 2f;
        float left = center - nameWidth / 2f;
        float minLeft = dp(8);
        float maxLeft = dockHost.getWidth() - dp(8) - nameWidth;
        if (left < minLeft) {
            left = minLeft;
        }
        if (maxLeft >= minLeft && left > maxLeft) {
            left = maxLeft;
        }
        focusName.setX(left);
        focusName.setY(glass.getBottom() + dp(10));
    }

    private View focusedTile() {
        if (focusedTileId == View.NO_ID) {
            return null;
        }
        return apps.findViewById(focusedTileId);
    }

    private float px(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Favourite {
        final String title;
        final int art;
        final int backdrop;
        final String uri;
        final String packageName;
        final String appLabel;
        final float crop;

        Favourite(String title, int art, int backdrop, String uri, String packageName, String appLabel) {
            this(title, art, backdrop, uri, packageName, appLabel, 0.5f);
        }

        Favourite(String title, int art, int backdrop, String uri, String packageName, String appLabel, float crop) {
            this.title = title;
            this.art = art;
            this.backdrop = backdrop;
            this.uri = uri;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.crop = crop;
        }
    }

    private static final class Row {
        final String packageName;
        final String label;
        final Intent intent;
        final Drawable icon;
        final Drawable art;
        final boolean settings;

        Row(String packageName, String label, Intent intent, Drawable icon, Drawable art, boolean settings) {
            this.packageName = packageName;
            this.label = label;
            this.intent = intent;
            this.icon = icon;
            this.art = art;
            this.settings = settings;
        }
    }

    private final class GlassPanel extends View {
        private final RenderNode node = new RenderNode("dock-glass");
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private float radius;
        private final float blurPx;
        private int topTint;
        private int bottomTint;
        private final int expand;
        private Drawable snapshot;
        private Drawable snapshotSource;
        private ImageView blurSource;
        private boolean blurFailed;
        private final boolean showEdge;

        GlassPanel(float radius, float blurPx, int topTint, int bottomTint) {
            this(radius, blurPx, topTint, bottomTint, true);
        }

        GlassPanel(float radius, float blurPx, int topTint, int bottomTint, boolean showEdge) {
            super(HomeActivity.this);
            this.radius = radius;
            this.blurPx = blurPx;
            this.topTint = topTint;
            this.bottomTint = bottomTint;
            this.showEdge = showEdge;
            this.expand = (int) Math.ceil(blurPx) + 8;
            setWillNotDraw(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setDefaultFocusHighlightEnabled(false);
            setOutlineProvider(new GlassOutline(radius));
            setClipToOutline(true);
            fill.setColor(0x38FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, px(1.25f)));
            edge.setColor(0x73FFFFFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                node.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
            }
        }

        void setRadius(float radius) {
            this.radius = radius;
            setOutlineProvider(new GlassOutline(radius));
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0) {
                clipPath.reset();
                float r = cornerRadius(w, h);
                clipPath.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
            }
            invalidateOutline();
            invalidate();
        }

        void setTint(int top, int bottom) {
            topTint = top;
            bottomTint = bottom;
            int h = getHeight();
            if (h > 0) {
                fill.setShader(new LinearGradient(
                        0, 0, 0, h, topTint, bottomTint, Shader.TileMode.CLAMP));
            }
            invalidate();
        }

        private float cornerRadius(int w, int h) {
            return Math.min(radius, Math.min(w, h) * 0.5f);
        }

        void setBlurSource(ImageView source) {
            blurSource = source;
            refreshSource();
        }

        void refreshSource() {
            snapshot = null;
            snapshotSource = null;
            invalidate();
        }

        private ImageView blurView() {
            return blurSource != null ? blurSource : backdrop;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(exactOrZero(widthMeasureSpec), exactOrZero(heightMeasureSpec));
        }

        private int exactOrZero(int spec) {
            return MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(spec) : 0;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            clipPath.reset();
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = cornerRadius(w, h);
            clipPath.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
            fill.setShader(new LinearGradient(
                    0, 0, 0, h, topTint, bottomTint, Shader.TileMode.CLAMP));
            invalidateOutline();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            boolean solid = Color.alpha(topTint) == 255 && Color.alpha(bottomTint) == 255;
            if (!solid && !blurFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()) {
                try {
                    drawBlur(canvas, w, h);
                } catch (RuntimeException ex) {
                    blurFailed = true;
                    Log.e("LiteHome", "glass blur failed", ex);
                }
            }
            float corner = cornerRadius(w, h);
            if (solid) {
                if (corner <= 0f) {
                    canvas.drawRect(0, 0, w, h, fill);
                } else {
                    canvas.drawRoundRect(0, 0, w, h, corner, corner, fill);
                }
                return;
            }
            if (corner <= 0f) {
                canvas.drawRect(0, 0, w, h, fill);
                return;
            }
            if (!showEdge) {
                canvas.drawRoundRect(0, 0, w, h, corner, corner, fill);
                return;
            }
            float inset = edge.getStrokeWidth() / 2f + 1f;
            rect.set(inset, inset, w - inset, h - inset);
            float r = Math.max(0f, corner - inset);
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.drawRoundRect(rect, r, r, edge);
        }

        private void drawBlur(Canvas canvas, int w, int h) {
            int bw = w + expand * 2;
            int bh = h + expand * 2;
            node.setPosition(0, 0, bw, bh);
            node.setUseCompositingLayer(true, null);
            Canvas recording = node.beginRecording();
            try {
                drawBackdrop(recording);
            } finally {
                node.endRecording();
            }
            canvas.save();
            if (!clipPath.isEmpty()) {
                canvas.clipPath(clipPath);
            }
            canvas.translate(-expand, -expand);
            canvas.drawRenderNode(node);
            canvas.restore();
        }

        private void drawBackdrop(Canvas canvas) {
            ImageView photo = blurView();
            Drawable drawable = backdropCopy();
            if (drawable == null || photo.getWidth() == 0) {
                return;
            }
            int[] glassLoc = new int[2];
            int[] photoLoc = new int[2];
            getLocationOnScreen(glassLoc);
            photo.getLocationOnScreen(photoLoc);
            int viewW = photo.getWidth() - photo.getPaddingLeft() - photo.getPaddingRight();
            int viewH = photo.getHeight() - photo.getPaddingTop() - photo.getPaddingBottom();
            int dwidth = drawable.getIntrinsicWidth();
            int dheight = drawable.getIntrinsicHeight();
            if (viewW <= 0 || viewH <= 0 || dwidth <= 0 || dheight <= 0) {
                return;
            }
            float scale;
            float dx = 0f;
            float dy = 0f;
            if (dwidth * viewH > viewW * dheight) {
                scale = viewH / (float) dheight;
                dx = (viewW - dwidth * scale) * 0.5f;
            } else {
                scale = viewW / (float) dwidth;
                dy = (viewH - dheight * scale) * 0.5f;
            }
            int saveL = drawable.getBounds().left;
            int saveT = drawable.getBounds().top;
            int saveR = drawable.getBounds().right;
            int saveB = drawable.getBounds().bottom;
            drawable.setBounds(0, 0, dwidth, dheight);
            canvas.save();
            canvas.translate(photoLoc[0] - glassLoc[0] + expand, photoLoc[1] - glassLoc[1] + expand);
            canvas.translate(photo.getPaddingLeft() + Math.round(dx), photo.getPaddingTop() + Math.round(dy));
            canvas.scale(scale, scale);
            drawable.draw(canvas);
            canvas.restore();
            drawable.setBounds(saveL, saveT, saveR, saveB);
        }

        private Drawable backdropCopy() {
            Drawable source = blurView().getDrawable();
            if (source == null) {
                snapshot = null;
                snapshotSource = null;
                return null;
            }
            if (source != snapshotSource || snapshot == null) {
                snapshotSource = source;
                Drawable.ConstantState state = source.getConstantState();
                snapshot = state != null ? state.newDrawable(getResources()).mutate() : source;
            }
            return snapshot;
        }
    }

    private static final class GlassOutline extends ViewOutlineProvider {
        private final float radius;

        GlassOutline(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) {
                outline.setEmpty();
                return;
            }
            outline.setRoundRect(0, 0, w, h, radius);
        }
    }
}
