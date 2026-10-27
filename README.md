<div align="center">
  <img src="readme-res/transient-logo.png" width="35%" alt="Transient" />
  <h1>Transient</h1>
  <p>A modern Android music player with <b>bit-perfect USB DAC output</b>, a live <b>DAC signal-path</b> view and an on-device <b>AI</b> that learns your taste.</p>
</div>

> ## App size and supported phones
> Transient is built for **64-bit ARM (arm64-v8a) only**. This is deliberate: the native audio libraries (FFmpeg decoder,
> FLAC decoder and USB driver) used to be packaged for four CPU types, which made the APK very large, and only one of them
> is ever used on a phone. Every phone made since about 2019 is arm64.
>
> - **Not supported:** very old 32-bit-only phones and x86 / x86_64 emulators.
> - **Need them?** Build with `./gradlew assembleDebug -Ptransient.abis=arm64-v8a,armeabi-v7a,x86_64` (comma separated).
> - Debug builds are bigger than release builds (no code or resource shrinking). For the smallest file use `./gradlew assembleRelease`.
> - To see what is inside an APK: `unzip -lv Transient*.apk | sort -k1 -nr | head -15`

## Features

### Playback and audio
- **USB bit-perfect output** to USB Audio Class 2.0 DACs: no Android mixer, no resampling, automatic sample-rate switching (44.1 kHz to 384 kHz)
- **DAC signal path** screen: source, decoder, processing, USB driver, DAC, with a bit-perfect verdict
- Native FLAC engine; FFmpeg decodes every other format without truncating to 16-bit
- **AIFF / AIF support** at native bit depth on speaker, Bluetooth and wired output (played through ExoPlayer); song lengths are read from the file header when Android reports 0:00
- **Automatic fallback:** if Android's player cannot decode a file, Transient retries it with ExoPlayer and FFmpeg before skipping it
- Equalizer, bass boost, reverb, spatial audio, crossfade and speed/pitch on the normal output
- Audio focus recovery: volume is restored after other apps (calls, voice, video) use the speaker, and playback re-takes focus when you return to the app
- Songs without artwork borrow the cover of another song from the same album

### Library and browsing
- **Aurora Glass design**: gradient background, glass player, floating navigation pill and cards, named colour palettes (Aurora, Violet, Coral, Sage, Ocean, Lavender, Amber), light / dark / auto theme
- Home with **Jump back in** and **Recently added**, plus Mixes AI, All songs, Albums, Artists, Genres, Playlists, Folders and Favourites tabs
- **Pinch to zoom** on Albums, Artists and Genres (1 to 5 columns) and on All songs (list, then 2 to 4 columns); text and buttons scale with the tile size
- **Always-visible A to Z index** on grids, lists and detail screens: tap or slide to jump to the first item for a letter (letters with no items jump to the next one)
- Album, playlist and folder screens with a square, rounded cover
- Header that shows the current **title, artist and album** while music plays (can be turned off in Customization)
- Mini player with a **spinning CD cover**; round covers in the full player also spin like a disc
- **Jewel case cover** in the full player and in the *Top artist* card on the home screen: the album art sits inside a CD jewel case (ribbed spine, clear plastic, soft reflection). Full player only, can be switched off in Customization; when it is on it replaces the cover shape. An optional **3D sway** (off by default) tilts the case a few degrees as you move the phone, and three sliders (strength, fade, blur) control the reflection under every case, with a live preview
- **Top Artists with real photos**: one entry per singer; photos and short bios come from Wikipedia (and Deezer for pictures), or add your own photo. Tap an artist for a photo, a short bio and every song of theirs in your library (play all / shuffle). Name variants such as initials and short forms ("SP Balu" for S. P. Balasubrahmanyam) are matched automatically, and *Search by another name* lets you point an artist at the right Wikipedia name
- Full player three-dot menu: **Lyrics, Meta data (edit song information), Go to artist, Show album, Delete**
- Reliable song deletion through Android's system delete request (a song never comes back while its file is still on the phone)

### Lyrics
- Live, synced lyrics from `.lrc` files (next to the song or in a `Lyrics` folder, also through the music folder you pick in setup) and from embedded tags
- Built-in lyrics editor with timing offset
- Sync follows the real player position, so it works even for songs whose stored length is unknown

### On-device AI (offline)
- Learns which singers, albums, genres and eras you like from how you really listen (plays, skips, repeats, favourites, playlists)
- **Smart Shuffle**, **Smart Radio** and **Discovery**: songs flow into each other (shared singers, album, era, language, mood) without long runs of one artist
- Generated mixes: Daily Flow, Energy, Chill, time-of-day, Forgotten Gems, Artist radio, **More like your favourite album**, **Heavy Rotation**
- Everything runs on the phone; nothing is uploaded

### Setup and privacy
- Three-page first-run setup: theme and colours, all permissions on one page with **Allow all** (the prompts appear one after the other), and the music folder
- **Internet is used for one thing only:** looking up artist photos and bios (the artist's name is sent to Wikipedia and Deezer, nothing else). It is **off by default**: turn on *Artist photos & info from the internet* in Customization to use it. While it is off the app makes no network requests. Photos you add yourself stay on the phone
- No ads, no analytics, no donation prompts, no microphone permission

> While USB bit-perfect output is active, the equalizer, effects, crossfade and speed/pitch are bypassed by design,
> and volume is controlled on the DAC. Requires Android 10+ for USB output. DSD is not supported yet.

## Build
Needs JDK 21, the Android SDK, **NDK 29.0.14206865** and CMake 3.22.1. The first build clones libFLAC (needs `git` + network).

```bash
./gradlew assembleDebug      # debug APK (arm64-v8a only, see the note at the top)
./gradlew assembleRelease    # smallest APK (code and resource shrinking on)
```
Or run the **Build Transient Debug APK** workflow from the GitHub *Actions* tab and download the artifact.

## Credits & licence
Transient is a derivative work, licensed under **GPL-3.0** (see [LICENSE](LICENSE)).

- Based on **[Lune](https://github.com/MrDemonc/Lune)** by MrDemonc (GPL-3.0)
- USB audio driver, Media3 sink and FLAC decoder from **[decent-player](https://github.com/Ma145/decent-player)** by Marcelo Silva (MIT) — see `decent/LICENSE-decent-player-MIT`
- libFLAC by Xiph.Org (BSD), FFmpeg decoder extension by Jellyfin (LGPL)

Details of the USB integration: [USB_INTEGRATION_NOTES.md](USB_INTEGRATION_NOTES.md).
