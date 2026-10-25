# Transient — USB audio integration notes

Status: written but NOT yet compiled or tested on hardware. Expect to fix a few compile errors on first build.

## What was added
- `decent/` — the three MIT libraries from decent-player (driver, Media3 sink, libFLAC decoder), kept as siblings
  (the driver's CMake reads the FLAC module's libFLAC checkout). `decent/LICENSE-decent-player-MIT` is the original license.
  Only change inside them: additive `UsbOutputStatus` + `UsbAudioSink.outputStatus/onOutputStatusChanged`.
- `tools/usb/` — `UsbPlaybackEngine` (ExoPlayer + UsbAudioSink), `UsbDacSupport`, `UsbSignalPathStore`.
- `MusicService` — routes playSong/restorePlayback/pause/resume/seek/position to the USB engine when a DAC is ready;
  otherwise the original MediaPlayer path is untouched. Per-track fallback + DAC-unplug handling.
- UI: `SignalPathActivity` (DAC signal path), USB section in Audio settings, bypass banner on the equalizer screen.
- Build: media3 pinned to 1.9.3; Jellyfin FFmpeg decoder 1.9.0+1; `fetchLibflac` Gradle task clones xiph/flac
  (needs git + network); CI installs NDK 29.0.14206865 + CMake; new `Transient_Debug_Build.yml`.

## First build checklist
1. Push, run **Build Lune Debug APK** in Actions (or `./gradlew assembleDebug` locally with NDK 29.0.14206865 + CMake).
2. If `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` doesn't resolve, check Maven Central for a 1.9.x build.
3. Test: enable Settings > Audio > USB bit-perfect output, plug a UAC2 DAC, allow USB access, play FLAC / MP3 / 24-bit WAV,
   then open DAC signal path.

## Known limits
USB mode disables EQ/effects/crossfade/speed-pitch/visualizer; volume is on the DAC; notification sounds pause playback.
UAC2 DACs only. No DSD. FFmpeg is a prebuilt binary and libFLAC is cloned at build time, so the README's
"pure Gradle / no prebuilt binaries" F-Droid claim no longer holds. No INTERNET permission was added.
