# Respira

A calm Android app for guided breathing, meditation listening, and a mixable ambience
board. Spanish by default, English available in-app. Fully offline — no accounts, no
network, no ads.

## Features

- **Breathe** — Calm (4-0-6), Box (4-4-4-4), 4-7-8 and Coherent (5.5-5.5) patterns,
  a breathing orb driven by a drift-free clock, haptics and soft chimes, 1/3/5/10 min
  or open sessions.
- **Meditate** — five original ambient tracks, plus your own audio through the system
  file picker (persisted read grants). Sleep timer shared with the soundscapes.
- **Soundscapes** — ten seamless nature loops (rain, storm, wind, stream, ocean, fire,
  night forest, birds, deep hum) mixed live, six presets, per-layer and master volume,
  sleep timer.
- Background playback through a foreground media service with a media notification.

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

## Status

`0.1.0-alpha02` — first test build for real-device feedback.
