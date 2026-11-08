# JD musax — 1st ever Spotify alternative to overcome almost every hurdle (in progress)

This is the first native Kotlin/Jetpack Compose foundation, not a completed feature-parity release.

## Included
- Native Compose AMOLED-first interface and adaptive accent state (Artwork / Custom starter modes).
- Audius track search and stream URL resolution.
- Media3 ExoPlayer + MediaSessionService foundation for background playback and Android media controls.
- Coil artwork loading.
- Source/reference copies of the latest HTML app and the three supplied visual references.

## Build with GitHub Actions (phone-friendly)
1. Upload this folder's contents to a GitHub repository (root should contain `settings.gradle.kts`, `app/`, and `.github/`).
2. Open **Actions** → **Build JD musax APK** → **Run workflow**.
3. Download the `JD-musax-debug-apk` artifact from the completed run.

## Important current limitations
This is an initial native foundation. Room schema and persistence, complete library/local-file import, playlists/favourites/history, equalizer, sleep timer, synchronized lyrics, robust Audius host discovery, and full navigation still need implementation. The Android system owns the actual notification layout; Android media notifications use the platform's standard media template, while the supplied hand-drawn visual is used as design direction for in-app controls. The custom accent selector is a starter toggle; full color picker and actual artwork color extraction are next.

The legacy HTML is preserved under `reference/` for feature parity checks, not embedded in the native app.
