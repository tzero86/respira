# Respira

A calm Android app for guided breathing, meditation listening, and a mixable ambience
board. Spanish by default, English available in-app. No accounts, no ads. Everything
plays offline; the network is used only when you import audio from a link or check for
updates.

## Features

- **Breathe** — Calm (4-0-6), Box (4-4-4-4), 4-7-8 and Coherent (5.5-5.5) patterns,
  a breathing orb driven by a drift-free clock, haptics and soft chimes, 1/3/5/10 min
  or open sessions.
- **Meditate** — five original ambient tracks, plus your own audio through the system
  file picker, a direct audio/podcast/YouTube link, or by sharing an audio file into
  Respira. Sleep timer shared with the soundscapes.
- **Covers from YouTube** — link imports keep the video thumbnail next to the title on
  the list, in the player and on the Home cards.
- **Your pick and your favorites** — heart any track for the quick collection at the
  top of Home, and set one as preferred to take the big "Para esta noche" card.
  Library removals clear both markers.
- **Bedtime routine** — three minutes of 4-7-8 breathing, then her preferred track
  starts on its own. One tap from Home or from the Breathe screen.
- **Keep sleeping** — Home shows the last track with the position it stopped at, so
  it resumes there instead of from the start. Tracks fade in, and fade out on their
  own at the end.
- **Soundscapes** — ten seamless nature loops (rain, storm, wind, stream, ocean, fire,
  night forest, birds, deep hum) mixed live, six presets, per-layer and master volume,
  sleep timer.
- Background playback through a foreground media service with a media notification.
- **Self-updates** — release builds poll the GitHub releases feed, download the newer
  APK with progress, verify package, version and signing certificate, and hand it to
  the system installer. A manual check lives in the Home footer.

## Importing audio

Meditate accepts audio four ways:

1. **Built-in tracks** — the five generated pieces.
2. **From device** — the system file picker, with persisted read grants.
3. **From a link** — paste a direct audio URL (MP3, M4A, OGG, Opus, WAV, FLAC, AAC), a
   YouTube video (watch, `youtu.be`, Shorts, embed, live) or a podcast RSS/Atom feed,
   pick an episode, and the app downloads it into private app storage with progress,
   cancellation and a 300 MB cap. The title can be edited before downloading.
   YouTube links pick a size: **Ahorrar espacio** takes the smallest audio stream
   (roughly 30 kbps, so a three-hour video is around 50 MB) and **Máxima calidad**
   keeps the original bitrate (around 190 MB for the same video).
4. **Share** — share an audio file from any app into Respira; it is copied into the
   library and appears on the Meditate screen.

YouTube extraction is done on-device against the player API with the Android client,
so no third-party service is involved: the title, duration and best audio track are
resolved from the video, and only that audio is downloaded. Videos that need sign-in
or are region-locked say so; playlist links ask you to open a single video.

## Build

Requirements: JDK 21, Android SDK 36, Android SDK build-tools 35+.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`minSdk` is 26 (Android 8.0), `targetSdk` 36.

### Release signing

Release builds are signed when a `keystore.properties` file exists at the repo root:

```properties
storeFile=keystore/respira-release.jks
storePassword=…
keyAlias=respira
keyPassword=…
```

The keystore and properties file are intentionally ignored by git. Without them,
`assembleRelease` still builds, unsigned.

```bash
./gradlew :app:assembleRelease
```

## Generated audio

Every bundled sound is original and generated offline by `tools/generate_audio.py`
(numpy + scipy, encoded with ffmpeg). Ambience layers are 40-second seamless loops;
meditation tracks are long-form pads. Regenerate everything with:

```bash
python tools/generate_audio.py
```

Outputs land in `app/src/main/res/raw/`; intermediate WAVs go to `tools/out/`.

## Localization

Spanish is the default locale (`app/src/main/res/values/`) regardless of the device
language. English lives in `values-en/`. The in-app switcher is in the Home header and
persists across launches.

## Landing page

The GitHub Pages site lives in `docs/` and is published from `master` (`/docs`):
<https://tzero86.github.io/respira/>. It is one hand-written page — no build step — with
an interactive breathing demo, a Web Audio sound mixer and Spanish/English copy.

```bash
python -m http.server 8123 --directory docs
```

## Status

`0.1.0-alpha11` — a bedtime routine (3 minutes of 4-7-8, then her preferred
track), a "Seguir durmiendo" card on Home that resumes where playback stopped, a
fade-in on every track and an automatic fade-out at the end of the track, and a
storage report for the library with a cleanup action.

Downloads now come in two sizes: "Ahorrar espacio" takes the small Opus stream
(a 3-hour video is ~50 MB instead of ~190 MB) and "Máxima calidad" keeps the
original bitrate.

`0.1.0-alpha10` — YouTube imports keep their thumbnail as cover art, tracks can be
hearted into a quick collection that leads Home, one track can be preferred to take the
"Para esta noche" card, and Breathe is a proper tab that keeps the dock. Downloads
still run in a foreground service over four parallel ranged connections.
