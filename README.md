# 🎧 DJ Friend

> A background music discovery utility for Android. Monitors what you're playing, asks Last.fm what's good next, and puts the suggestions right in your notification shade.

---

## What it does

DJ Friend runs quietly in the background as a foreground service. When it detects a song playing on your device, it queries Last.fm for similar tracks and surfaces up to three suggestions as notification action buttons — one tap plays it locally or opens Spotify search.

- **Monitors any media player** via MediaSession API
- **Filters out podcasts, audiobooks, and long-form audio** automatically
- **Three-tier recommendation engine** using Last.fm (similar tracks → similar artists → artist top tracks)
- **Fuzzy-matches suggestions against your local library** using Levenshtein distance
- **One tap to play** — local files open in your preferred audio app, everything else goes to Spotify search
- **Configurable timeout** — service shuts itself down after 1, 3, or 5 minutes of silence to save battery

---

## Screenshots

> _Add screenshots here once the app is built._

---

## Requirements

- Android 9 (API 28) or higher
- A free [Last.fm API key](https://www.last.fm/api/account/create)
- Notification listener permission (granted once in Settings)

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

2. Copy the properties template and fill in your values:
   ```bash
   cp local.properties.template local.properties
   # Edit local.properties — add your sdk.dir and LASTFM_API_KEY
   ```

3. Build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

4. Build a signed release APK:
   ```bash
   ./gradlew assembleRelease \
     -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
     -Pandroid.injected.signing.store.password=YOUR_STORE_PASS \
     -Pandroid.injected.signing.key.alias=YOUR_ALIAS \
     -Pandroid.injected.signing.key.password=YOUR_KEY_PASS
   ```

### CI — GitHub Actions

Push a version tag to trigger an automated signed build:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow will build, sign, and attach the APK to a GitHub Release. You can also trigger it manually from the **Actions** tab.

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

1. Open **DJ Friend** and tap **Grant Notification Access** — this is required for MediaSession monitoring.
2. Optionally tap **Disable Battery Optimisation** if you want the service to survive aggressive power management.
3. Tap **Start DJ Friend**.
4. Play any song in any music app. Suggestions will appear in your notification shade within a few seconds.

---

## Architecture

```
MainActivity
    └── starts ──► DjFriendService  (ForegroundService)
                        │
                   MediaSessionManager.getActiveSessions()
                        │
                   MediaController.Callback
                    ├── onMetadataChanged
                    │       └── isMusicContent() ──► fetchSuggestions()
                    └── onPlaybackStateChanged
                            └── startTimeout() / cancelTimeout()
                                        │
                               LastFmApiService  (Retrofit)
                                ├── track.getInfo       (verify track)
                                ├── track.getSimilar    (primary)
                                ├── artist.getSimilar   (fallback A)
                                └── artist.getTopTracks (fallback B)
                                        │
                               findLocalTrack()  (MediaStore)
                                └── FuzzyMatcher.levenshtein()
                                        │
                               NotificationCompat.Builder
                                └── 3 action buttons
                                     ├── 📁 Local → Intent.ACTION_VIEW audio/*
                                     └── 🌐 Web   → open.spotify.com/search/...
```

---

## Recommendation logic

| Situation | Strategy |
|-----------|----------|
| Normal playback | `track.getSimilar` — up to 3 similar tracks |
| Obscure track (< 10k listeners) | `artist.getSimilar` — each similar artist's #1 track |
| No similar tracks found | `artist.getSimilar` fallback as above |
| No similar artists found | `artist.getTopTracks` for the current artist |

The current artist is never suggested back-to-back when using the artist similarity fallback.

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/djfriend/app/
│   ├── DjFriendApp.kt
│   ├── api/
│   │   └── LastFmApiService.kt       Retrofit interface + response models
│   ├── model/
│   │   └── SuggestionResult.kt
│   ├── receiver/
│   │   ├── BootReceiver.kt           Auto-start on device boot (optional)
│   │   └── NotificationActionReceiver.kt
│   ├── service/
│   │   ├── DjFriendService.kt        Core foreground service
│   │   └── MediaSessionListenerService.kt
│   ├── ui/
│   │   └── MainActivity.kt           Jetpack Compose entry point
│   └── util/
│       └── FuzzyMatcher.kt           Levenshtein distance + normalisation
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

---

## Permissions explained

| Permission | Why it's needed |
|------------|-----------------|
| `FOREGROUND_SERVICE` | Run as a persistent foreground service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Foreground service type for media monitoring |
| `POST_NOTIFICATIONS` | Show the suggestion notification |
| `INTERNET` | Call the Last.fm API |
| `READ_MEDIA_AUDIO` | Search local music library |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Access active MediaSessions |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep running when "Always" timeout is selected |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-start after reboot |

---

## License

Personal use. Not affiliated with Last.fm or Spotify.
