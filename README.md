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
- **Soundscapes** — ten seamless nature loops (rain, downpour, thunder, wind, stream,
  ocean, fire, night forest, birds, deep hum) mixed live, six presets, per-layer and
  master volume, sleep timer. Nine of the ten are real public-domain field recordings
  (rain on leaves, rain on a tent, a thunderstorm, wind through a birch tree, sea waves,
  a campfire, an alpine stream, nightingale song, night ambience); only the low hum is
  synthesised. Every layer runs through a room reverb and is placed on a stereo stage.
  Named mixes can be saved and reloaded.
- **Three tabs** — Inicio, Respirar and Biblioteca. Biblioteca holds the tracks and the
  sound mixer behind one switch. The mini player opens the full player from anywhere.
- **A home screen that knows the hour** — after 22:00 it becomes a bedtime screen: the
  routine first, then "Seguir durmiendo" and "Para esta noche". The browsing cards
  disappear until morning.
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
resolved from the video, and only that audio is downloaded. When a video publishes
several audio tracks (YouTube auto-dubbing), Respira imports the original track by
default, and the link dialog offers a track selector to take a dub instead. Videos
that need sign-in or are region-locked say so; playlist links ask you to open a
single video.

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

`0.1.0-alpha16` — YouTube videos that publish several audio tracks (its automatic
dubbing) no longer import the wrong language. The player API now lists one audio
stream per track — English and Portuguese dubs first, the original Spanish track
last — and the import used to take the first stream matching the wanted bitrate,
which was the dub. Respira now always prefers the original track, and the link
dialog shows a **Pista de audio** selector when a video has more than one, so a dub
can still be chosen deliberately. Both the inspected size and the download use the
picked track.

`0.1.0-alpha15` — the app now survives the night. The audio service is sticky,
so when Android or an OEM battery manager stops the process mid-session the mix
is rebuilt from disk and playback resumes by itself; a mix that was paused
stays paused instead of starting on its own. The mixer also asks once, and only
while a timer of an hour or more is running, for the battery exemption that
stops those managers from killing playback in the first place.

`0.1.0-alpha14` — nine of the ten soundscapes are now real public-domain field
recordings instead of synthesis; only the low hum is generated. The fire was the
reason: its crackle band was 94 % silence and every crackle had the same
timbre, which is a geiger counter rather than a fire. A new measurement
(`tools/check_layers.py`) checks that each layer has a body, irregular events,
varied amplitudes and more than one click colour, so the fault cannot come
back silently.

`0.1.0-alpha13` — the soundscapes were rebuilt a second time, because the
first rebuild had the right spectrum and still did not sound like anything.
The fire ticked like a geiger counter: its crackle band was 94 % silence, so
every crackle stood alone. Five layers are now real public-domain field
recordings (rain on leaves, rain on a tent, sea waves, a campfire, an alpine
stream), the loops run 40–120 seconds instead of 40, and two new checks keep
the layers honest: one measures whether the loop point is audible, the other
whether each layer still has a body, irregular events and varied amplitudes.

`0.1.0-alpha12` — three tabs instead of four (Inicio, Respirar, Biblioteca), a Home
screen that reshapes itself after 22:00 into a bedtime screen, a mini player that
opens the full player from any tab, named saved mixes in the mixer and a sleep timer
that reaches eight hours.

The ambience layers were rebuilt from their physical mechanisms: rain's spectral
flatness dropped from 0.44 (hiss) to 0.06, fire now carries audible crackle, the
ocean has foam, and every layer runs through a synthesized room reverb. Loudness
spread across the beds went from 13.3 LU to 0.2 LU, so the mixer slider means the
same thing on every layer.

Five of the ten layers are now **real field recordings** rather than synthesis:
rain on leaves, rain on a tent, sea waves, a campfire and an alpine stream. A
spectrum can be right and the sound still wrong — the fire had the crackle
frequency but its crackle band was 94 % silence, so it ticked like a geiger
counter. Recordings carry the parts no filter can reconstruct: the roar of the
gas, the hiss of the flame, the stone hearth around them. All five are public
domain; `tools/SOURCES.md` lists them. The loops are 40–120 seconds long, so a
night does not repeat itself, and `tools/check_layers.py` checks that every
layer still has a body, irregular events and varied amplitudes.

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
