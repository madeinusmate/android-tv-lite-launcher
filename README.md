# Lite Home

A minimal Android TV home launcher for Google TV and Chromecast with Google TV. One row of apps on a glass dock, a TMDB-powered hero carousel on the background, and a favourites grid for quick jumps into streaming apps.

Built without Gradle: `aapt2`, `javac`, `d8`, `zipalign`, and `apksigner` only.

## Features

- **Default HOME launcher** with leanback app row (icons, long-press menu: reorder, force stop, uninstall, app info)
- **Hero backdrop** from [TMDB](https://www.themoviedb.org/) weekly trending (released titles only; anime filtered out), with infinite paging as you browse
- **Watch / Trailer** actions that open Max, Apple TV, Stremio, or YouTube when available
- **Favourites page** (Down from the app row): grid of pinned shows with deeplinks
- **Liquid-glass** dock and controls using live backdrop blur (Android 14+)

Without a TMDB API key, the hero falls back to [Cinemeta](https://github.com/Stremio/stremio-addon-sdk) catalogs.

## Requirements

- Android TV device or emulator, **API 28+** (tested on Android 14 / Google TV)
- Android SDK **platform 34** and **build-tools 35.0.1** (or set `ANDROID_JAR` / `ANDROID_BUILD_TOOLS`)
- JDK **17+**
- [TMDB](https://www.themoviedb.org/settings/api) API key (recommended)

## Setup

1. Clone the repository.

2. **TMDB API key** (do not commit this file):

   ```bash
   cp res/values/tmdb.xml.example res/values/tmdb.xml
   ```

   Edit `res/values/tmdb.xml` and replace `YOUR_TMDB_API_KEY` with your v3 API key.

   `res/values/tmdb.xml` is listed in `.gitignore`. If a key was ever committed or shared, rotate it in the TMDB dashboard.

3. **Signing**: the build script uses `debug.keystore` in the project root if present, otherwise `~/.android/debug.keystore` (default Android debug alias `androiddebugkey`, password `android`).

## Build

```bash
chmod +x build.sh
./build.sh
```

Output: `build/lite-home.apk`

Environment overrides:

| Variable | Default |
|----------|---------|
| `ANDROID_BUILD_TOOLS` | `$HOME/Library/Android/sdk/build-tools/35.0.1` |
| `ANDROID_JAR` | `$HOME/Library/Android/sdk/platforms/android-34/android.jar` |
| `KEYSTORE` | `./debug.keystore` or `~/.android/debug.keystore` |

## Install

```bash
adb install -r build/lite-home.apk
```

Set **Lite Home** as the default launcher in system settings, or start it once:

```bash
adb shell am start -n dev.litehome/.HomeActivity
```

## Permissions

| Permission | Why |
|------------|-----|
| `INTERNET` | TMDB, Cinemeta, poster images, YouTube links |
| `QUERY_ALL_PACKAGES` | List installed TV and phone launcher apps for the dock |
| `KILL_BACKGROUND_PROCESSES` | Optional force-stop from the long-press menu |

## Third-party services and trademarks

- Metadata and images may be loaded from **TMDB** and **Cinemeta** under their respective terms.
- Deeplinks target **Max**, **Apple TV**, and **Stremio** if those apps are installed; package names are constants in `ShowCatalog.java`.
- Streaming service names and logos are trademarks of their owners. This project is not affiliated with Google, TMDB, Warner Bros. Discovery, Apple, or Stremio.

## Artwork in `res/drawable-nodpi/`

Demo posters and backdrops for the favourites grid are included for development. **Replace them before redistribution** if you do not have rights to those images.

## Project layout

```
AndroidManifest.xml
build.sh
src/dev/litehome/
  HomeActivity.java    # UI, dock, hero, favourites
  ShowCatalog.java     # TMDB / Cinemeta, cache, images
  DockScroller.java
  ProgressRing.java
res/
  layout/
  values/
  drawable-nodpi/      # backgrounds and favourite art
```

## License

[MIT](LICENSE)
