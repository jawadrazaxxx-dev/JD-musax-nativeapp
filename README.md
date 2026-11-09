# JD musax

JD musax is a native Android music player built with Kotlin, Jetpack Compose, and AndroidX Media3. It is an in-progress project focused on a polished listening experience, Audius discovery, and reliable local playback.

## Current features

### Discovery and library
- Browse trending music and search the Audius catalog.
- Explore music by the categories exposed in the app.
- Import audio files from device storage through Android's document picker.
- Save tracks to your library and manage favorites.
- Browse recently played and most-played history.

### Playback
- Media3 ExoPlayer with a MediaSessionService for background playback and Android media controls.
- Queue management, track selection, previous/next navigation, and shuffle-aware playback.
- Repeat modes: Off, All, and One.
- Seekable playback progress with elapsed and total time.
- Playback speed presets from 0.75× to 2×.
- Sleep timer presets.
- Restore the playback queue, current track, listening position, shuffle, and repeat settings after the playback service is recreated.
- Audio focus handling and pause when the audio route becomes noisy (for example, headphones disconnect).

### Now Playing and appearance
- Mini-player and expanded Now Playing screen with artwork and queue.
- Manually editable, timestamped .LRC lyrics with active-line highlighting.
- Light, dark, and AMOLED appearance modes.
- Multiple accent colors and optional artwork-derived accent color.

## Build and install (phone-friendly)

The repository includes a GitHub Actions workflow named **Build JD musax APK**.

1. Open the repository's **Actions** tab.
2. Select **Build JD musax APK** and run the workflow, or open a successful workflow run triggered by a pull request or push to main.
3. In the completed run, download the **JD-musax-debug-apk** artifact.
4. Extract the APK from the ZIP and install it on an Android device. Android may ask you to allow installation from that source.

The workflow runs:

```bash
./gradlew assembleDebug --no-daemon
```

This produces a **debug APK**, not a signed production release. A release signing configuration and Play Store distribution are separate tasks.

## Implementation notes and current limitations

- The app uses app-private SharedPreferences/JSON for library data, lyrics, listening history, and playback-session persistence; it does not currently use Room.
- Lyrics are entered or pasted by the user as timed .LRC text; automatic lyric-provider lookup is not implemented.
- Audius streaming depends on the availability of the Audius API and stream resolution.
- An in-app equalizer, audio visualizer, crossfade, Android Auto browsing, and smart playlists are not currently implemented.
- Android controls and notifications are presented through the platform's Media3 media-session integration; the system controls their exact appearance.

## Project structure

- app/ — Android application source and resources.
- .github/workflows/ — GitHub Actions APK build workflow.
- reference/ — legacy/reference material retained for design and feature comparison; it is not embedded as the native app UI.

## Status

JD musax is actively developed. The feature list above describes functionality present in the current codebase; it should not be read as a claim of complete Spotify feature parity.
