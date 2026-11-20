# glick

A metronome for practice, written in Kotlin Multiplatform with a Compose UI.
It runs on Linux, Windows and Android from one code base.

The click is modelled on the Boss Dr. Beat DB-33 and is placed sample by
sample by the audio thread, so its timing does not depend on the UI.

## Features

- Tempo from 5 to 5000 BPM on a jog wheel, with tap tempo.
- Any time signature: any number of beats over a note length from 1 to 128.
- A one-bar pattern grid with the click, three subdivision lanes and four
  polyrhythm voices. Tap a step to cycle it between normal, accent and mute;
  `M` mutes a whole lane.
- Saved patterns, each stored with its time signature.
- A circle timer that loops in bars or seconds, plays a sound on completion and
  can change any parameter each time (for example raise the tempo by 5 BPM).
- A session timer that pauses with the click and can stop it when time is up.
- Random beat silence with an adjustable probability.
- A sleep mode: a plain 40 BPM click.
- An activity log of every run.
- A choice of audio output device, with a dropout counter.
- Everything is remembered: the app reopens with the settings, timer progress,
  window and song it was closed with.
- A song view: load a WAV, FLAC or MP3 file and give it beats. If you know
  its tempo, it gets a steady grid that you slide onto the first beat; if
  not, you record the tempo by tapping along while it plays. Taps can be
  snapped to the nearest attacks or smoothed. Automatic detection is there
  as an option. Move, add or delete beats, set bar starts and signature
  changes, and loop any range of bars. Then either the click follows the
  song's tempo, or the song is time-stretched (without changing its pitch)
  to the click. Tempo changes, including those made by the circle timer,
  keep both in sync. Each song's map is saved automatically.

MIDI clock sync has a panel but is not connected yet.

## Install

Download the file for your system from the
[latest release](https://github.com/traxrobustus/glick/releases/latest).

| System | File | How |
|---|---|---|
| Windows | `glick-<version>-windows-x64.msi` | Run it. |
| Debian, Ubuntu | `glick-<version>-linux-amd64.deb` | `sudo apt install ./glick-<version>-linux-amd64.deb` |
| Fedora, openSUSE | `glick-<version>-linux-x86_64.rpm` | `sudo dnf install ./glick-<version>-linux-x86_64.rpm` |
| Any Linux, no root | `glick-<version>-linux-x64.run` | `bash glick-<version>-linux-x64.run` (add `--uninstall` to remove) |
| Android 8 or later | `glick-<version>-android.apk` | Open it on the phone and allow the install. |

The `.aab` file in a release is the bundle to upload to Google Play.

## Build from source

You need a JDK 21 that includes `jlink` and `jpackage` (for example Temurin).
The Android SDK is only needed for the Android app.

```bash
./gradlew :app:run                     # run the desktop app
./gradlew check                        # lint and all tests
./gradlew :app:packageDeb              # or packageRpm, packageMsi (on Windows)
./gradlew :androidApp:installDebug     # install on a connected phone
```

The modules:

- `core` holds the metronome itself (state, transport, click synth, audio
  engine) and the song tools (beat detection, time stretching). It is plain Kotlin and also compiles to Kotlin/Native for Linux and
  Windows.
- `app` is the Compose UI, shared by desktop and Android, plus the desktop app.
- `androidApp` is the Android app.

## Releasing

1. Set `glick.version` (and raise `glick.versionCode`) in `gradle.properties`.
2. Commit, then push a tag `v<version>`.

The Release workflow builds every installer and publishes the GitHub release.
To sign the Android files it needs four repository secrets:
`GLICK_KEYSTORE_BASE64`, `GLICK_KEYSTORE_PASSWORD`, `GLICK_KEY_ALIAS` and
`GLICK_KEY_PASSWORD`. Without them the APK is signed with a debug key and no
Play Store bundle is published. For a local release build, put
`GLICK_KEYSTORE_FILE` and the other three values in
`~/.gradle/gradle.properties`.

## Credits

glick was conceived and directed by [@traxrobustus](https://github.com/traxrobustus),
and written with Claude Code.

## Licence

MIT. See [LICENSE](LICENSE).
