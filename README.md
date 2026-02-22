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
- **One tap to act** — local files copy to clipboard (and optionally open your player), web tracks resolve via iTunes → Odesli to a real Spotify URL then open Spotify or SpotiFLAC
- **Paginated in-app suggestion list** — browse all available candidates with Back / More navigation

---

## Screenshots

> _Add screenshots here once the app is built._

---

## Requirements

- Android 9 (API 28) or higher
- A free [Last.fm API key](https://www.last.fm/api/account/create)
- Notification listener permission (granted once in Settings)
- [Spotify](https://play.google.com/store/apps/details?id=com.spotify.music) and/or [SpotiFLAC](https://github.com/zarzet/SpotiFLAC-Mobile/releases) installed (optional, for web suggestions)

---

## Building

### Prerequisites

- JDK 17
- Android SDK (compile SDK 35)
- A signing keystore (see below)

### Local build

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

### CI — GitHub Actions

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

> See `SETUP_GUIDE.html` for step-by-step instructions on generating a keystore in Termux and adding secrets to GitHub.

---

## First-time setup on device

After installing the APK:

1. Open **DJ Friend** and work through the Settings buttons:
   - **Allow Notifications** — lets DJ Friend post its notification
   - **Grant Music Access** — lets DJ Friend check suggestions against your local library
   - **Grant Notification Listener Access** — required for MediaSession monitoring; opens the system settings page, find DJ Friend in the list and enable it
   - **Disable Battery Optimisation** — recommended, prevents Android from killing the service
2. Optionally install **Spotify** and/or **SpotiFLAC** for web suggestions.
3. Go back to the main screen and tap **Start**.
4. Play any song in any music app. Suggestions will appear in your notification shade and in the app within a few seconds.

---

## In-app suggestion list

The main screen shows:

- **Now Playing: Track by Artist** — tapping opens the active media player app
- **Suggestion buttons** — each shows the track name, artist, and a ✔ (in your local library) or ✗ (web only) symbol. Local matches are **bold**. Tapping a local match copies it to clipboard. Tapping a web match resolves a real Spotify URL via iTunes + Odesli, then either opens SpotiFLAC (default) or Spotify depending on your setting.
- **Back / More** — paginate through all available candidates when "Songs per page" is set to 5 or 10.

---

## Settings reference

| Setting | Options | Default | Description |
|---------|---------|---------|-------------|
| When I tap a song in my library | Copy song name / Copy artist - song name | Copy song name | What gets copied to clipboard for local matches |
| When I tap a song NOT in my library | Open SpotiFLAC / Open Spotify | Open SpotiFLAC | Whether to open SpotiFLAC (with Spotify URL on clipboard) or open Spotify directly |
| Songs per page | 5 / 10 / All | All | How many suggestions to show at once in the app |

---

## Architecture

```
MainActivity  ──────────────────────────────────────────────────────────┐
  ├── DjFriendApp()      Compose root                                     │
  ├── MainScreen()       Now Playing + Suggestion list                    │
  └── SettingsScreen()   Preferences + permission buttons                 │
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
  └── Web tap    → SpotifyLinkResolver (iTunes → Odesli → Spotify URL)
                        └── open SpotiFLAC or Spotify per setting

SpotifyLinkResolver
  ├── iTunes Search API   itunes.apple.com/search?term=...
  └── Odesli Links API    api.odesli.co/v1-alpha.1/links?url=...
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
│   │   └── SpotifyLinkResolver.kt      iTunes → Odesli → Spotify URL chain
│   ├── model/
│   │   └── SuggestionResult.kt
│   ├── receiver/
│   │   ├── BootReceiver.kt             Auto-start on device boot
│   │   └── NotificationActionReceiver.kt
│   ├── service/
│   │   ├── DjFriendService.kt          Core foreground service
│   │   └── MediaSessionListenerService.kt
│   ├── ui/
│   │   └── MainActivity.kt             Jetpack Compose UI (Main + Settings screens)
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
- iTunes Search API (Apple) — track lookup
- Odesli Links API — cross-platform streaming URL resolution
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

---

## License

Personal use. Not affiliated with Last.fm, Spotify, Apple, or Odesli.
