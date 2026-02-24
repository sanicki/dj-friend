# 🎧 DJ Friend

> A background music discovery utility for Android. Monitors what you're playing, asks Last.fm what's similar, and puts the suggestions right in your notification shade and in-app.

---

## What it does

DJ Friend runs quietly in the background as a foreground service. When it detects a song playing on your device it queries Last.fm for similar tracks and surfaces them as tappable suggestions — both as notification action buttons and in a scrollable in-app list.

- **Monitors any media player** via the MediaSession API — Poweramp, Plexamp, DJay, Deezer in Firefox, Google Files, YouTube, and more
- **Follows the active player** — always reflects whichever app is currently playing, switches automatically when you switch apps
- **Filters out podcasts, audiobooks, and long-form audio** automatically
- **Three-tier recommendation engine** using Last.fm (`track.getSimilar` → `artist.getSimilar` → `artist.getTopTracks`)
- **Results sorted by match score** — the highest-confidence suggestions are always shown first
- **Fuzzy-matches suggestions against your local library** using Levenshtein distance, with `The Artist` / `Artist, The` normalisation and parenthetical stripping (`Wannabe (Radio Edit)` matches `Wannabe`)
- **One tap to act** — local files copy to clipboard, web tracks resolve via iTunes → Odesli to a real Spotify URL then open SpotiFLAC or Spotify
- **Paginated in-app suggestion list** — browse all available candidates with Back / More navigation

---

## Screenshots

> _Add screenshots here once the app is built._

---

## Downloading and installing

1. Go to the [**Releases**](../../releases) page and download the latest `djfriend.vX.Y.Z.apk`.
2. Before installing, you must allow your device to install apps from outside the Play Store. See [How to enable Install from Unknown Sources](https://www.samsung.com/ae/support/mobile-devices/how-to-enable-permission-to-install-apps-from-unknown-source-on-my-samsung-phone/) — the steps are similar on all Android devices, though menu names vary by manufacturer.
3. Open the downloaded APK to install it, then follow the first-time setup below.

---

## First-time setup on device

A welcome screen is shown on first launch with these same instructions.

After installing the APK:

1. Open **DJ Friend** and work through the Settings buttons:
   - **Allow Notifications** — lets DJ Friend post its notification
   - **Grant Music Access** — lets DJ Friend check suggestions against your local library
   - **Grant Notification Listener Access** — required for MediaSession monitoring; opens the system settings page, find DJ Friend in the list and enable it
   - **Disable Battery Optimisation** — recommended, prevents Android from killing the service
2. Optionally install **SpotiFLAC** and/or **Spotify** to preview songs not in your local music library.
3. Go back to the main screen and tap **Start**.
4. Play any song in any music app. Suggestions will appear in your notification shade and in the app within a few seconds.

---

## In-app suggestion list

The main screen shows:

- **Now Playing: Track by Artist** — if the current track is in your local library, tapping opens the active media player app (button appears in the normal faded purple). If it is *not* in your library, the button turns faded maroon and tapping behaves like a web suggestion — resolving a Spotify link and opening SpotiFLAC or Spotify per your setting.
- **Suggestion buttons** — each shows the track name, artist, and a ✔ (in your local library) or ✗ (web only) symbol. Local matches are **bold**. Tapping a local match copies it to clipboard. Tapping a web match resolves a real Spotify URL via iTunes + Odesli, then either opens SpotiFLAC (default, with fallback to Spotify if SpotiFLAC is not installed) or Spotify depending on your setting.
- **Back / More** — paginate through all available candidates when "Songs per page" is set to 5 or 10.

---

## Settings reference

The Settings screen is divided into two sections. The upper portion contains **Settings** — preferences that control DJ Friend's behaviour. Below the "Back to DJ Friend" button is **Configuration** — permission grants and app install buttons needed for initial setup.

| Setting | Options | Default | Description |
|---------|---------|---------|-------------|
| When I tap a song in my library | Copy song name / Copy artist - song name | Copy song name | What gets copied to clipboard for local matches |
| When I tap a song NOT in my library | Open SpotiFLAC / Open Spotify | Open SpotiFLAC | Whether to open SpotiFLAC (with Spotify URL on clipboard, falling back to Spotify if SpotiFLAC is not installed) or open Spotify directly (copies "artist - track" to clipboard) |
| Songs per page | 5 / 10 / All | All | How many suggestions to show at once in the app |
| Notification suggestions | Show all / Only songs in music library / Only songs NOT in music library | Show all | Filters which suggestions appear as notification action buttons |
| DJ Friend in-app suggestions | Show all / Only songs in music library / Only songs NOT in music library | Show all | Filters which suggestions appear in the in-app suggestion list |

### Web suggestion behaviour in detail

| Setting | Spotify URL found | Behaviour |
|---------|-------------------|-----------|
| Open SpotiFLAC | Yes | Copies Spotify URL → opens SpotiFLAC (falls back to Spotify if not installed) |
| Open SpotiFLAC | No | Copies "Artist - Track" → opens SpotiFLAC (falls back to Spotify if not installed) |
| Open Spotify | Yes or No | Copies "Artist - Track" → opens Spotify URL (or search URL as fallback) |

---

## Bugs and feature requests

Found a bug or have an idea? Please [open a GitHub Issue](../../issues) — include your Android version, the DJ Friend version from the APK filename, and a description of what happened or what you'd like to see.

---

## Known limitations

- **Spotify longpress paste** — when "Open Spotify" is selected, DJ Friend copies "Artist - Track" to the clipboard and opens a Spotify URL. Spotify's in-app browser/search field may not support longpress-to-paste on some devices; use your keyboard's clipboard button instead. This is a Spotify UI limitation, not a DJ Friend bug.
- **MusicBrainz coverage** — MusicBrainz may not have a Spotify URL on file for every track, particularly for obscure releases. When no URL is found, DJ Friend falls back to copying "Artist - Track" to the clipboard instead.

---

## Contributing

New to contributing on GitHub? See [GitHub's guide to contributing to a project](https://docs.github.com/en/get-started/exploring-projects-on-github/contributing-to-a-project) for a walkthrough of forking, branching, and opening a pull request.

### Requirements

- Android 9 (API 28) or higher
- A free [Last.fm API key](https://www.last.fm/api/account/create)
- Notification listener permission (granted once in Settings)
- [SpotiFLAC](https://github.com/zarzet/SpotiFLAC-Mobile/releases) and/or [Spotify](https://play.google.com/store/apps/details?id=com.spotify.music) installed (optional, to preview songs not in your local music library)

### Building

#### Prerequisites

- JDK 17
- Android SDK (compile SDK 35)
- A signing keystore (see below)

#### Local build

1. Clone the repo:
   ```bash
   git clone https://github.com/YOUR_USERNAME/dj-friend.git
   cd dj-friend
   ```

2. Add your keys to `local.properties`:
   ```properties
   sdk.dir=/path/to/your/android/sdk
   LASTFM_API_KEY=your_lastfm_api_key_here
   ```

3. Build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

4. Build a signed release APK:
   ```bash
   ./gradlew assembleRelease \
     -PLASTFM_API_KEY=your_key \
     -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
     -Pandroid.injected.signing.store.password=YOUR_STORE_PASS \
     -Pandroid.injected.signing.key.alias=YOUR_ALIAS \
     -Pandroid.injected.signing.key.password=YOUR_KEY_PASS
   ```

#### CI — GitHub Actions

Four workflows are available under the **Actions** tab, all triggered manually (`workflow_dispatch`):

| Workflow | What it does |
|----------|-------------|
| **Build & Sign APK for Testing** | Builds a signed APK, attaches it as a workflow artifact (`djfriend-testing.apk`). No release created. |
| **Build & Sign APK for Patch Release** | Increments the patch version (e.g. `v1.2.3` → `v1.2.4`), creates a GitHub Release, attaches `djfriend.v1.2.4.apk`. |
| **Build & Sign APK for Minor Release** | Increments the minor version (e.g. `v1.2.3` → `v1.3.0`), creates a GitHub Release, attaches APK. |
| **Build & Sign APK for Major Release** | Increments the major version (e.g. `v1.2.3` → `v2.0.0`), creates a GitHub Release, attaches APK. |

Version numbers are determined automatically by reading existing git tags — no manual tagging needed.

#### Required repository secrets

| Secret | Description |
|--------|-------------|
| `SIGNING_KEYSTORE_BASE64` | Your `.jks` keystore file encoded as base64 |
| `SIGNING_STORE_PASSWORD` | Keystore store password |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `SIGNING_KEY_PASSWORD` | Key password |
| `LASTFM_API_KEY` | Your Last.fm API key |


---

## Architecture

```
MainActivity  ──────────────────────────────────────────────────────────┐
  ├── DjFriendApp()      Compose root + screen routing                   │
  ├── WelcomeScreen()    First-run setup instructions                    │
  ├── MainScreen()       Now Playing + Suggestion list                   │
  └── SettingsScreen()   Preferences + permission buttons                │
                                                                          │
DjFriendService  (ForegroundService) ◄──────── starts/stops ─────────────┘
  │
  ├── MediaSessionManager.getActiveSessions()
  │     └── per-controller MediaController.Callback (one instance per app)
  │           ├── onMetadataChanged  → isMusicContent() → fetchSuggestions()
  │           └── onPlaybackStateChanged → active-player tracking + timeout
  │
  ├── fetchSuggestions()
  │     ├── LastFmApiService (Retrofit)
  │     │     ├── track.getInfo       (verify + listener count)
  │     │     ├── track.getSimilar    (primary, sorted by match score)
  │     │     ├── artist.getSimilar   (fallback A)
  │     │     └── artist.getTopTracks (fallback B)
  │     ├── findLocalTrack()  (MediaStore + FuzzyMatcher)
  │     └── broadcastStateUpdate() → MainActivity
  │
  └── updateNotification()  (3 action buttons max)

NotificationActionReceiver
  ├── Local tap  → clipboard copy
  └── Web tap    → SpotifyLinkResolver (MusicBrainz search + lookup → Spotify URL)
                        └── open SpotiFLAC (fallback: Spotify) or Spotify per setting

SpotifyLinkResolver
  ├── MusicBrainz Search  musicbrainz.org/ws/2/recording?query=...
  └── MusicBrainz Lookup  musicbrainz.org/ws/2/recording/<mbid>?inc=url-rels
```

---

## Recommendation logic

| Situation | Strategy |
|-----------|----------|
| Normal playback | `track.getSimilar` — all results with match ≥ 0.2, sorted by score |
| Obscure / low-listener track | `artist.getSimilar` — each similar artist's #1 track (fills to 3) |
| No similar tracks found | `artist.getSimilar` fallback as above |
| No similar artists found | `artist.getTopTracks` for the current artist |

Artist diversity is enforced — the current artist is never suggested, and each artist appears at most once across all suggestions. `The Artist` and `Artist, The` are treated as identical.

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/djfriend/app/
│   ├── DjFriendApp.kt
│   ├── api/
│   │   ├── LastFmApiService.kt         Retrofit interface + response models
│   │   └── SpotifyLinkResolver.kt      MusicBrainz → canonical Spotify URL
│   ├── model/
│   │   └── SuggestionResult.kt
│   ├── receiver/
│   │   ├── BootReceiver.kt             Auto-start on device boot
│   │   └── NotificationActionReceiver.kt
│   ├── service/
│   │   ├── DjFriendService.kt          Core foreground service
│   │   └── MediaSessionListenerService.kt
│   ├── ui/
│   │   └── MainActivity.kt             Jetpack Compose UI (Welcome + Main + Settings screens)
│   └── util/
│       └── FuzzyMatcher.kt             Levenshtein distance + normalisation
└── res/
    ├── values/strings.xml
    ├── values/themes.xml
    └── xml/file_paths.xml
```

---

## Dependencies

| Library | Purpose |
|---------|---------|
| Retrofit 2 + Gson | Last.fm API calls |
| Kotlin Coroutines | Async network + MediaStore queries |
| Jetpack Compose + Material 3 | UI |
| AndroidX Core KTX | Kotlin extensions |

External APIs used (no additional dependencies — plain `HttpURLConnection`):
- MusicBrainz API — recording search + Spotify URL lookup (free, no key required, 1 req/s limit)
- GitHub Releases API — self-update version check

---

## Permissions explained

| Permission | Why it's needed |
|------------|-----------------|
| `FOREGROUND_SERVICE` | Run as a persistent foreground service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Foreground service type for media monitoring |
| `POST_NOTIFICATIONS` | Show the suggestion notification |
| `INTERNET` | Call Last.fm, iTunes, Odesli, and GitHub APIs |
| `READ_MEDIA_AUDIO` | Search local music library |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Access active MediaSessions across all apps |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep running when screen is off |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after device reboot |

---

## Attribution

Powered by [Last.fm](https://www.last.fm/api).

Track links resolved via [MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API).

---

## License

Personal use. Not affiliated with Last.fm, Spotify, Apple, or Odesli.
