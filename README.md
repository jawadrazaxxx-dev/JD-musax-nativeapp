# SHIAudio

**SHIAudio** is a Shia-focused Islamic audio discovery and listening app. Its separate collections organize audiobooks, nohay, marsiya, Quran recitation, Tafseer, majalis, duas and ziyarat, manqabat, and Islamic podcasts. It combines native Android playback with a personal library, bookmarks, listening history, playlists, and supported offline downloads.

The app is built with **Kotlin, Jetpack Compose, Material 3, and AndroidX Media3 (ExoPlayer)**. Its screens and player are native Android UI—there is no HTML or WebView-based app interface.

> **Project status:** Actively developed. This README describes the implementation on the `feat/playlists-stats-progress-bookmarks` branch and may change as the project evolves. A feature being listed here does not imply that the app is a production release or has been tested on every Android device.

## Highlights

- Native Compose interface with Light, Dark, and AMOLED themes.
- Privacy-conscious local library: saved tracks, playlists, bookmarks, listening history, and progress stay on-device; no account is required.
- Optional audio visualizer permission is requested only when the user enables the visualizer.
- Playback session restoration supports secure HTTPS provider streams and local document URIs.
- Separate Discover tabs for Shia Audiobooks, Nohay, Marsiya, Quran, Tafseer, Majalis, Duas & Ziyarat, Manqabat, and Shia Podcasts.
- Shia-focused catalogue discovery across publisher websites, community platforms, and documented audio APIs.
- External-only sources open their original provider; the app does not bypass access, DRM, or download restrictions.
- Local audio import, favorites, bookmarks, and listening history.
- Background playback, media notifications, and a persistent playback queue.
- Custom playlists and queue editing.
- Saved playback positions for resuming long-form audio.
- Listening statistics, daily listening goals, and weekly activity.
- Downloaded-audio library for supported tracks.
- Equalizer, audio visualizer, playback speed, sleep timer, and editable timed lyrics.

## Features

### Discover audio

Discover is organized into nine focused category tabs: Shia Audiobooks, Nohay, Marsiya, Quran, Tafseer, Majalis, Duas & Ziyarat, Manqabat, and Shia Podcasts. Category tabs appear in Discover only; Home no longer repeats the same horizontal tab strip.

The catalogue layer supports several kinds of audio sources:

- **Shia Audiobooks:** Shia Audios, Wilayat Waves, Shia Readers, and Urdu Shia audio archives.

- **Nohay, Marsiya, Manqabat, Majalis, and Duas:** Rawda, Azadari Media, Nauha Diary, ShiaRise Media, and other relevant Shia providers.

- **Quran and Tafseer:** Al Furqan's live recitation API plus Shia Quran and Tafseer resources.
- **Al Furqan Quran API** — live Quran catalogue with 114 surahs, individual ayah streams, and reciter selection. Each result is one ayah, not a falsely labelled complete surah.
- **Shia Podcasts and lectures:** Shia Readers, ShiaViews, ShiaRise, Alwasi TV, Kashana-e-Baseerat, and Al Basheer.
- **IslamHouse** — Islamic audio and book/audio entries, with language and category filtering.




The Quran tab uses Al Furqan's documented public API (`https://alfurqan.online/api/v1`): it loads surah and reciter metadata, then streams individual ayahs. Al-Husary is the default reciter when available; entering a reciter name or ID filters to that reciter. The API documents 44 reciters, 114 surahs, and individual ayah audio endpoints. Other provider results depend on each provider's API, catalogue, network availability, and stream URLs. A provider directory entry is a link to the original source, not a claim that its catalogue is playable in-app. External-only sources open the provider. The app does not bypass login, DRM, copyright, or download restrictions, and does not host these catalogues or audio files.

### Personal library

- Import audio files using Android's system document picker.
- Keep a Favorites collection.
- Bookmark books or tracks in a separate Bookmarks collection.
- Review Recently played and Most played collections.
- Create named playlists, rename and reorder them, add favorites, and delete playlists.
- Remove tracks from a playlist without removing them from the device or Favorites.

### Player and playback

- Media3 ExoPlayer playback with a `MediaSessionService` for background audio.
- Now Playing screen with artwork, track information, seek/progress controls, and previous/next actions.
- Mini-player access from the app interface.
- Add tracks to the active queue, move queue items, remove items, and select another queued track.
- Shuffle and repeat modes.
- Playback speed presets: 0.75×, 1×, 1.25×, 1.5×, and 2×.
- Sleep timer presets: Off, 15, 30, and 60 minutes.
- Playback queue, current item, position, shuffle, and repeat state are saved for session restoration.
- Android audio focus and noisy-audio-route handling (such as pausing when headphones disconnect).

### Smart listening bookmarks and resume

Save a track or book to the Bookmarks collection, and save its current playback position separately. The player can resume from that saved position, which is useful for long lectures, chapters, and audiobooks. Saved positions are stored per track on the device.

### Listening insights and daily challenges

The **Listening insights** screen includes:

- Total recorded listening time and today's listening time.
- A seven-day listening activity chart.
- Top authors/artists and top categories, based on the metadata available for played tracks.
- An optional daily listening challenge with selectable goals (15, 30, 45, or 60 minutes).
- Progress toward the current day's goal.

Listening totals are recorded while a track is actively playing. Historical totals begin accumulating after this tracking feature is available; they are not imported from another music service.

### Offline audio

- Download supported HTTPS audio using Android's system `DownloadManager`.
- View completed downloads in the dedicated Offline library section.
- Play a completed download from the app or remove it from the download list.

Downloads are only offered for catalogue entries marked as downloadable with a supported HTTPS audio URL. Not every podcast or provider item can be downloaded from SHIAudio. Availability and storage location are managed by Android's DownloadManager.

### Audio tools and appearance

- **Emerald visual identity:** deep emerald and forest-green surfaces, restrained muted-gold accents, warm ivory light mode, and an OLED-friendly emerald-tinted AMOLED palette.
- **Hybrid interface:** a reflective Quran-inspired home feature, horizontal category shortcuts, a compact persistent mini-player with artwork and transport controls, and five primary navigation destinations. Listening Insights remains available from the Library screen.
- **Accessibility-minded contrast:** warm high-contrast text, clear muted labels, rounded touch targets, and consistent surface/border tokens.


- **Themes:** Light, Dark, and AMOLED display modes.
- **Accent colors:** multiple selectable accent palettes.
- **Artwork colors:** optionally derive the app accent from current artwork.
- **Animated player background:** subtle accent-colored motion while audio is playing.
- **Equalizer:** device-supported bands and presets connected to the active audio session.
- **Visualizer:** live frequency response when enabled and permitted by Android.
- **Lyrics:** paste or edit timestamped `.LRC` text and highlight the active line during playback.

Equalizer and visualizer capabilities depend on the Android device, audio session, and platform audio-effect implementation. The visualizer requires the app's audio-recording permission.

## Technology

| Area | Implementation |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Playback | AndroidX Media3 / ExoPlayer |
| Background playback | Media3 `MediaSessionService` |
| Networking | OkHttp |
| JSON / feed parsing | Android JSON APIs and XML Pull Parser |
| Artwork colors | AndroidX Palette |
| Local feature persistence | SharedPreferences with JSON |
| Offline downloads | Android `DownloadManager` |
| Build | Gradle, Android Gradle Plugin, Kotlin Compose compiler plugin |

The current feature stores use app-private **SharedPreferences and JSON** for playlists, bookmarks, playback positions, analytics, challenge settings, offline-download references, lyrics, and playback-session state. Although Room libraries are present in the Gradle dependencies, these features do **not** currently use a Room database.

## Requirements

- Android **8.0 (API 26)** or newer.
- Internet connection for online discovery and streaming.
- Device storage access through Android's system picker for importing local audio.
- JDK **21** for building from source.

The app's current Gradle configuration uses compile SDK 36 and target SDK 35.

## Build from source

1. Clone the repository:

   ```bash
   git clone https://github.com/jawadrazaxxx-dev/JD-musax-nativeapp.git
   cd SHIAudio-nativeapp
   ```

2. Make sure JDK 21 and the Android SDK required by the project are installed.

3. Build the debug APK:

   ```bash
   chmod +x ./gradlew
   ./gradlew assembleDebug --no-daemon
   ```

4. Find the generated APK at:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Build with GitHub Actions

The repository includes **Android CI** and **Build SHIAudio APK** workflows.

1. Open the repository's [Actions page](https://github.com/jawadrazaxxx-dev/JD-musax-nativeapp/actions).
2. Open a completed run for the relevant workflow.
3. Confirm that the build job succeeded.
4. Download the `SHIAudio-debug-apk` artifact and extract the APK.

The workflow artifact is a **debug build**, not a signed production release. Release signing, store listing, and Play Store distribution are separate tasks. GitHub Actions artifacts may expire according to the workflow's retention settings.

## Repository layout

```
.
├── app/
│   └── src/main/
│       ├── java/com/jdmusax/app/
│       │   ├── MainActivity.kt
│       │   ├── PlaybackService.kt
│       │   ├── AudioLibraryRepository.kt
│       │   ├── AudioEffectsController.kt
│       │   ├── ListeningAnalytics.kt
│       │   ├── ListeningFeaturesStore.kt
│       │   ├── ListeningInsightsScreen.kt
│       │   ├── BookmarkStore.kt
│       │   ├── SettingsScreen.kt
│       │   └── Track.kt
│       └── AndroidManifest.xml
├── .github/workflows/
├── gradle/
├── reference/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

The `reference/` directory contains visual/reference material. It is not used as the app's UI runtime; the application interface is implemented in Kotlin and Compose.

## Current limitations and notes

- Online catalogues and stream URLs are provided by third parties. Their uptime, content, metadata, and availability are outside this app's control.

- Lyrics are entered or pasted by the user as timed `.LRC` text; automatic lyric-provider lookup is not implemented.
- Download support is provider- and track-dependent; the app does not provide a universal downloader for every stream.
- Listening insights are local to this app installation and are not synchronised across devices.
- Persistence currently uses SharedPreferences/JSON rather than Room or cloud sync.
- Equalizer and visualizer support varies by device and audio implementation.
- A signed release pipeline, Play Store publication, Android Auto browsing, crossfade, and cloud account synchronisation are not documented as implemented features.

## License

This project is distributed under the [MIT License](LICENSE).
