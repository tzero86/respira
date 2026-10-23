# Respira — design

Date: 2026-10-23
Status: approved in conversation (generated ambience loops, bundled generated tracks + import, dark atmospheric UI)

## Product

A personal Android app for breathing exercises, meditation listening, and a mixable
ambience board. One hand, one screen, no accounts, fully offline.

Three surfaces:

1. **Breathe** — guided patterns with an animated orb and optional haptics/chimes.
2. **Meditate** — five bundled ambient tracks plus the user's own audio via the system picker.
3. **Soundscapes** — ten looping nature layers mixed live, with presets and a sleep timer.

## Experience

Dark, atmospheric night theme. A single animated aurora backdrop sits behind every
screen; screens are translucent glass panels over it. Display type is Fraunces (warm
serif), UI type is Manrope. Accents are aqua, indigo, violet and amber. Motion is slow
and springy; nothing flashes.

Home has a breathing hero card, then meditation and soundscape entries. A floating
bottom bar (Home / Breathe / Meditate / Sounds) is hidden during an active breathing
session. A mini player rides above the bar whenever a track is playing.

## Architecture

- Kotlin + Jetpack Compose, single module, MVVM-free by design: state is held by a
  process-wide `AudioHub` plus screen-local `remember` state.
- `app.respira.audio.AudioHub` owns track playback (Media3 ExoPlayer), the ten
  ambience loops (one looping MediaPlayer per active layer), master volume, audio
  focus, and the sleep timer. A foreground service keeps playback alive with a media
  notification.
- `AmbienceCatalog` is the single source of layer ids, names, icons and preset levels.
- Breathing uses a monotonic-time phase engine (no drift), drives an animated orb, and
  plays short synthesized chimes/haptics on transitions.
- Meditation reads bundled raw resources and persisted SAF URIs from a small
  SharedPreferences JSON store.
- Audio assets are generated offline by `tools/generate_audio.py` (numpy/scipy) and
  encoded to OGG with ffmpeg: seamless loops for ambience, long-form pads for tracks.
  No third-party audio, no licences, no network at runtime.

## Data

- `AudioState` (StateFlow): current track, playing flags, position, active layers,
  master volume, sleep-timer deadline.
- Mixer mix and master volume in `respira_mixer` prefs; imported tracks in
  `respira_library` prefs (title + persisted content URI).

## Verification

Build with Gradle, install on the `respira_35` Pixel 7 AVD (Android 15), and exercise
each surface: breathing run, preset mixing with per-layer sliders, bundled and imported
track playback, sleep timer, background playback notification, and process-death
restore of the mixer and library. Screenshots drive the visual review.
