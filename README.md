<div align="center">
  <img src="readme-res/transient-logo.png" width="35%" alt="Transient" />
  <h1>Transient</h1>
  <p>A modern Android music player with <b>bit-perfect USB DAC output</b> and a live <b>DAC signal-path</b> view.</p>
</div>

## Features
- Modern Jetpack Compose / Material 3 interface, dynamic themes, live lyrics, playlists, widget
- **USB bit-perfect output** to USB Audio Class 2.0 DACs: no Android mixer, no resampling, automatic sample-rate switching (44.1 kHz – 384 kHz)
- Native FLAC engine, FFmpeg decoding for every other format
- **DAC signal path** screen: source → decoder → processing → USB driver → DAC, with a bit-perfect verdict
- Equalizer, bass boost, reverb, spatial audio, crossfade and more on the normal output (speaker / Bluetooth / wired)

> While USB bit-perfect output is active, the equalizer, effects, crossfade and speed/pitch are bypassed by design,
> and volume is controlled on the DAC. Requires Android 10+ for USB output. DSD is not supported yet.

## Build
Needs JDK 21, the Android SDK, **NDK 29.0.14206865** and CMake 3.22.1. The first build clones libFLAC (needs `git` + network).

```bash
./gradlew assembleDebug
```
Or run the **Build Transient Debug APK** workflow from the GitHub *Actions* tab and download the artifact.

## Credits & licence
Transient is a derivative work, licensed under **GPL-3.0** (see [LICENSE](LICENSE)).

- Based on **[Lune](https://github.com/MrDemonc/Lune)** by MrDemonc (GPL-3.0)
- USB audio driver, Media3 sink and FLAC decoder from **[decent-player](https://github.com/Ma145/decent-player)** by Marcelo Silva (MIT) — see `decent/LICENSE-decent-player-MIT`
- libFLAC by Xiph.Org (BSD), FFmpeg decoder extension by Jellyfin (LGPL)

Details of the USB integration: [USB_INTEGRATION_NOTES.md](USB_INTEGRATION_NOTES.md).
