# FireLauncher

An alternative home screen for **Fire TV Stick** (Fire OS 6+), with a carousel of **trailers for recently released movies** that start instantly, a **watchlist** (heart / X), and the weather.

Two ways to get the trailers, selectable in settings:

- **NAS mode** (instant start): a small service on your **home NAS** (built for Synology, but it runs on any Linux with Python 3.8+) downloads up to 1000 trailers every night and serves them on the local network, so the Fire TV Stick starts them in under a second.
- **YouTube mode** (no NAS needed): the app builds a list of 50 trailers with TMDB at startup (refreshed daily) and streams them from YouTube with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). Starting a trailer takes a couple of seconds.

```
NAS mode      TMDB ──► NAS service (Python) ──► manifest + video/audio ──► FireLauncher (Fire TV)
              YouTube ─┘  (yt-dlp, nightly)        local network :8787

YouTube mode  TMDB ─────────────────────────────► FireLauncher ◄── YouTube (NewPipeExtractor, streaming)
```

## Quick start without a NAS

1. On the Fire TV: Settings → My Fire TV → About, click the device name 7 times, then in Developer options turn on **ADB debugging** and **Apps from Unknown Sources**.
2. Download the latest `FireLauncher-*.apk` from [Releases](https://github.com/DanieleOwner/FireLauncher/releases) and install it (for example with the Downloader app, or `adb install`).
3. Get a free **TMDB API key** (themoviedb.org → Settings → API) and enter it in FireLauncher → Settings → TMDB key, or from a computer:

```bash
adb shell am start -a dev.firelauncher.CONFIGURE -n dev.firelauncher/.MainActivity --es source youtube --es tmdb_key YOUR_KEY
```

4. Optionally grant the Home-button permissions (see [The Home button on Fire OS](#the-home-button-on-fire-os)).

Updates: the published APK checks GitHub once a day; when a new version is out, Settings → **Updates** downloads and installs it.

## Features

**Home screen**
- Favorite apps along the bottom, with their original banners, in an order you choose.
- Shortcuts to all apps, the Appstore and settings.
- Greeting, clock and weather (Open-Meteo, no API key) at the top.
- Background that follows the selected movie.

**Trailers**
- Carousel of movies released within a configurable window (default: 18 to 6 months ago), sorted by popularity.
- Italian trailers first: the NAS prefers movies that have an Italian trailer, downloads the Italian version when it exists and re-checks English-only ones weekly. In settings you can show Italian trailers first, Italian only, or any language.
- Title, plot, genres, runtime and rating from TMDB, in Italian by default.
- Playback in the small preview window or full screen.
- Instant start: the selected trailer is prepared as soon as it gets focus, and the beginning of neighboring trailers is pre-cached on disk.
- "Stremio" opens Stremio with a search for that movie (the button only appears if Stremio is installed).

**Watchlist**

| Control | Action |
|---|---|
| **Watchlist** button below the plot (or ⏩ / ⏪ on the remote, on the home screen) | heart: add to / remove from the watchlist |
| ⏯ play/pause | pause and resume the trailer, in the preview window or full screen |
| ⏪ / ⏩ in full screen | jump back / forward 10 seconds |
| **Discard** button | X: never show this movie again (also deleted from the NAS) |
| **Novità / Watchlist** tabs above the buttons (or ≡) | switch between recent trailers and your watchlist; each list remembers where you were |
| OK | play; press OK again to go full screen |
| ‹ › in full screen | previous / next trailer |

Hearted trailers are **never removed**: not by the NAS rotation, nor when the YouTube-mode list is rebuilt. In NAS mode hearts and discards are stored on the NAS, so they survive reinstalling the app; in YouTube mode they stay on the Fire TV.

**Settings**, from the home screen or from the "Impostazioni launcher" icon:
- Trailer source (NAS or YouTube), NAS address, TMDB key (YouTube mode).
- Trailers on/off, release window in months, how many to show (5–100 or "all"), preview window or full screen, audio in the preview window.
- Apps on the home screen: add, remove, reorder.
- City for the weather (default: Terni).
- Interface language: Italian or English. Movie titles, plots and genres follow it too (from TMDB); when TMDB has no Italian translation, the English one is used instead of the original-language title and an empty plot.
- Updates (published APK only).

## Repository layout

```
app/                          Android app (Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, Coil)
  src/main/java/dev/firelauncher/
    MainActivity.kt           screen navigation and wiring
    HomeWatcherService.kt     detects the Amazon home screen and opens FireLauncher instead
    HomeOverlay.kt            cover screen shown while Fire OS makes us wait
    data/                     installed apps, settings, trailers and preferences, weather,
                              YoutubeCatalog (TMDB list for YouTube mode), Updater (GitHub releases)
    player/TrailerPlayer.kt   single ExoPlayer, early preparation and disk cache
    player/YoutubeResolver.kt YouTube stream URLs via NewPipeExtractor
    ui/                       home, all apps, settings, full-screen player
.github/
  workflows/release.yml       builds the APK and publishes it as a GitHub release
  dependabot.yml              keeps NewPipeExtractor and the other libraries up to date
nas-agent/
  agent.py                    trailer service (Python 3.8 standard library only)
  start.sh                    starts it in the background (run it at NAS boot)
  agent.example.json          optional service settings
```

## Setup

### 1. Trailer service on the NAS

Requirements:
- Python 3.8 or newer (Synology DSM 7 ships it as `/usr/bin/python3`).
- A free **TMDB API key**: on themoviedb.org go to Settings → API.

On Synology, create a shared folder, for example `firelauncher` on Volume 1, and lay out the files like this:

```
/volume1/firelauncher/
  agent/agent.py
  agent/start.sh
  config/tmdb.key        # ONLY the TMDB key (v3 key or v4 token); never commit it
  config/agent.json      # optional, see nas-agent/agent.example.json
  tools/yt-dlp           # yt-dlp binary for your CPU architecture
```

`yt-dlp` for 64-bit ARM Synology models (e.g. DS220j):

```bash
curl -L -o /volume1/firelauncher/tools/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64
chmod +x /volume1/firelauncher/tools/yt-dlp
```

Protect the key and start the service:

```bash
chmod 700 /volume1/firelauncher/config && chmod 600 /volume1/firelauncher/config/tmdb.key
sh /volume1/firelauncher/agent/start.sh
```

To start it automatically in DSM: **Control Panel → Task Scheduler → Create → Triggered Task → User-defined script**, event "Boot-up", your user, script `sh /volume1/firelauncher/agent/start.sh`.

The service:
- listens on port **8787** and refreshes the list every night at 4 AM;
- keeps up to **1000 trailers**: 125 for each of the 8 three-month windows over the last 24 months, taken from TMDB's most popular movies worldwide in that window, giving priority to those with an Italian trailer;
- downloads 1080p H.264 video and audio as separate files, so no ffmpeg is needed (the player merges them);
- uses about 30 MB per trailer;
- logs to `logs/agent.log`.

HTTP endpoints:

| Method | Path | Description |
|---|---|---|
| GET | `/manifest.json` | list of ready trailers |
| GET | `/media/<id>/…` | video, audio, images (Range requests supported) |
| GET | `/api/prefs` | `{"like": [...], "nope": [...]}` |
| POST | `/api/prefs` | `{"id": 123, "state": "like" \| "nope" \| "none"}` |
| POST | `/api/refresh` | start a refresh now |
| GET | `/api/status` | `{"refreshing": true/false}` |

> On Synology `/tmp` is mounted `noexec`, so the service makes `yt-dlp` unpack itself into `tools/tmp` instead.

### 2. App on the Fire TV

Use the published APK (see [Quick start](#quick-start-without-a-nas)) and set Settings → Trailer source → NAS and the NAS address, or build it yourself:

1. On the Fire TV, go to Settings → My Fire TV → About and click the device name 7 times to unlock **Developer options**. Turn on **ADB debugging** and **Apps from Unknown Sources**.
2. On your computer you need the Android SDK (installing Android Studio is the easiest way). Put your local configuration in `local.properties` (it is git-ignored):

```properties
firelauncher.nasUrl=http://192.168.1.10:8787
# optional, for YouTube mode:
firelauncher.tmdbKey=YOUR_TMDB_KEY
```

3. Build and install:

```bash
./gradlew assembleRelease
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

4. Grant the permissions used for the Home button. Fire OS only allows this through ADB:

```bash
adb shell appops set dev.firelauncher GET_USAGE_STATS allow
adb shell pm grant dev.firelauncher android.permission.READ_LOGS
adb shell appops set dev.firelauncher SYSTEM_ALERT_WINDOW allow
```

A local `release` build is signed with your local debug key, which is fine for personal sideloading. It doesn't show the Updates entry: an APK signed with a different key can't update it, so you update by rebuilding.

### Publishing releases from a fork

The [workflow](.github/workflows/release.yml) builds every push to `main` and, if these repository secrets exist, publishes a signed APK as release `build-<number>`: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. [Dependabot](.github/dependabot.yml) opens a pull request when NewPipeExtractor is updated (YouTube changes often break older versions): merging it, even from the GitHub mobile app, produces a new release that Fire TVs pick up from Settings → Updates.

## The Home button on Fire OS

Amazon does not let you replace the launcher:
- you can't pick another app as the home screen;
- third-party accessibility services are blocked;
- the Amazon home cannot be disabled ("protected package").

FireLauncher works around this:
1. It reads the system log (`READ_LOGS`) to see the request to open the Amazon home the instant it happens.
2. It immediately covers the screen with a launcher-style placeholder (`SYSTEM_ALERT_WINDOW`).
3. It reopens FireLauncher.

After Home is pressed, however, Android blocks apps from opening for **about 5 seconds**. That wait remains, but the Amazon home stays hidden. At boot, FireLauncher opens directly.

## Limitations and notes

- Some YouTube trailers are region-locked or private: the service tries TMDB's next trailer for that movie, and skips the movie if none works.
- Downloading videos from YouTube with `yt-dlp`, or streaming them outside YouTube's own apps with NewPipeExtractor, is against YouTube's Terms of Service. This project is meant for personal use, at your own risk.
- In YouTube mode trailers depend on NewPipeExtractor keeping up with YouTube's changes: if they stop playing, update the app.
- Movie data comes from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but is not endorsed or certified by TMDB.
- Weather data comes from [Open-Meteo](https://open-meteo.com/).

## License

MIT. See [LICENSE](LICENSE).
