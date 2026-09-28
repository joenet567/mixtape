# Mixtape — YouTube playlist → MP3 → phone music player

Two parts:

| Part | Where | What it does |
|---|---|---|
| **Importer** (`importer/`) | Windows PC | Downloads every song in a YouTube playlist as a tagged MP3 with square cover art. Re-running it only fetches songs added since last time. Also runs a small Wi-Fi server the app syncs from. |
| **Mixtape app** (`android/` → `Mixtape.apk`) | Android 10+ phone | Music player for the MP3s on the phone. Keeps playing in the background, with lock-screen / notification / Bluetooth / headset controls. Has a **Sync from PC** button that pulls new songs over Wi-Fi. |

The importer runs on the PC on purpose. YouTube now needs yt-dlp plus a JavaScript runtime (deno) plus ffmpeg, and yt-dlp needs frequent updates. All of that is easy to keep current on a PC and fragile inside an APK.

## Quick start

1. **Install the app.** Copy `Mixtape.apk` to the phone and open it (allow "install unknown apps" for your file manager when asked). Or, with USB debugging on:
   `powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 -Install`
2. **Import a playlist.** Double-click `importer\import.bat` and paste the playlist URL. The first run sets itself up (Python venv, yt-dlp, deno, ffmpeg: about 400 MB, all inside `importer\`). Songs land in `library\<playlist name>\`.
3. **Sync to the phone.** Double-click `importer\sync.bat`. If Windows Firewall asks, allow Python on **private** networks. In the app, tap the **Wi-Fi icon**, then **Find PC**, then **Sync now**. New songs go to the phone's `Music/<playlist name>/` folder.

After that, playlists appear under the app's **Playlists** tab, one per folder.

## Importer

```
import.bat <playlist-url> [more urls]     download / top up these playlists
import.bat update                         re-check every playlist imported before
import.bat                                interactive: paste URLs, u = update all, s = start sync
sync.bat                                  Wi-Fi sync server for the app
```

- Works with `youtube.com/playlist?list=…`, `watch?v=…&list=…`, and YouTube Music playlist URLs. A plain video URL goes into a `Singles` folder.
- Files are named `Title [videoId].mp3`. The video id is how both the importer and the app know what you already have, so **don't rename the files**. Moving them around is fine.
- Tags: title / artist come from YouTube Music metadata when the video has it, or from an `Artist - Song` title otherwise ("(Official Video)" and similar get stripped). Album = playlist name, track number = position in the playlist.
- Songs removed from the YouTube playlist are **not** deleted locally. Private and deleted videos are skipped.
- yt-dlp updates itself at most once a day. If YouTube downloads suddenly break, run `import.bat update` again tomorrow, or delete `importer\.venv\.last-update` to force an update now.

Options (append to `import.bat …`):

| Option | Meaning |
|---|---|
| `--quality 0` | MP3 quality. VBR level 0 = best … 9, or a bitrate like `320K`. Default `2` (about 190 kbps; the YouTube source is about 130 kbps Opus, so higher adds size, not detail). |
| `--cookies-from-browser firefox` | For **private** playlists and **Liked videos**. Log into YouTube in Firefox first. Chrome's cookies can't be read on Windows any more, so use Firefox, or `--cookies cookies.txt` exported with a browser extension. |
| `--trim` | Cut non-music intros and outros from music videos using SponsorBlock. |
| `--max-new N` | Only download N new songs this run (good for trying things out). |
| `--pause 2` | Seconds to wait between songs (default 2). Raise it if YouTube starts answering "Sign in to confirm you're not a bot". |
| `--library D:\Music` | Put the library somewhere else. Pass the same option to `sync.bat`. |

## App

The look is a cassette deck. It's always dark: espresso-black shell, cream "paper" text, tape-orange accent. Headings are condensed label type, times use a monospace tape counter, and playlist names are handwritten on the tape labels.

- **Playlists are tapes.** Each one is drawn as a cassette with its own label color, its name handwritten on the label, and its first cover as a sticker. The playlist that's playing has turning reels.
- **Now playing** shows the tape transport. The reels wind tape from left to right as the song plays, and the emptier reel spins faster, like a real deck. Tap the cassette (or the disc button) to flip to the full cover art. The background is tinted from the cover's colors.
- **Sync** "records" onto a tape: the reels run and the tape winds across as songs arrive.
- **Icons:** the app icon, the notification icon and the placeholder for songs without covers are all cassettes.

- **Background playback**: a Media3 `MediaSessionService` runs as a foreground media service. Music keeps going with the screen off, the app backgrounded, or the app swiped out of recents while playing. It pauses for calls and other apps' audio, and when headphones are unplugged.
- **Controls**: notification, lock screen, quick-settings media player, Bluetooth and headset buttons. Pressing play on a headset after the app was closed resumes the last queue.
- **Library**: every MP3 on the phone. That includes files synced over Wi-Fi and anything copied into `Music/` by USB. Tabs: **Songs** (search, Shuffle all) and **Playlists** (one per folder, in playlist order).
- **Player**: seek bar, shuffle, repeat off / all / one, and a queue sheet in real play order (shuffle-aware; tap a song to jump). The queue, position, shuffle and repeat survive app restarts.
- **Sync**: only downloads songs the phone doesn't have yet and never deletes anything. Uses ports 47811/TCP and 47810/UDP.

On phones that kill background apps aggressively (some Xiaomi, Samsung and Huawei models), set Mixtape's battery usage to **Unrestricted** if music stops after a while.

## Troubleshooting

| Problem | Fix |
|---|---|
| **Find PC** finds nothing | Phone and PC must be on the same Wi-Fi. Guest networks often block device-to-device traffic. Windows must treat that network as **Private**, and Python must be allowed through the firewall. Otherwise type the `ip:port` that `sync.bat` prints (the first line is the likely one; others are VPN or virtual adapters). |
| App shows no songs | Grant the music permission (the app asks; otherwise Settings → Apps → Mixtape → Permissions → Music and audio). |
| `Sign in to confirm you're not a bot` | YouTube is rate-limiting you. Wait a while, raise `--pause`, or use `--cookies-from-browser firefox`. |
| A song failed | Re-run the same command. Finished songs are skipped and failed ones retried. Region-locked or age-restricted videos need cookies. |

## Building the APK yourself

The toolchain lives in `D:\Android` and changes nothing system-wide:

```
powershell -ExecutionPolicy Bypass -File android\setup-toolchain.ps1   # once: JDK 17, Android SDK, Gradle (~1.5 GB, accepts the Android SDK license)
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1         # -> Mixtape.apk
```

The release APK is signed with the local debug key in `D:\Android\user-home\debug.keystore`. Keep that file: an APK signed with a different key can't install over the old one (you'd have to uninstall first, which loses the saved queue but none of your music).

Stack: Kotlin 2.1, Jetpack Compose (Material 3 with a custom palette, system condensed/handwritten/mono fonts, nothing downloaded), Media3 1.5 ExoPlayer + MediaSession, AndroidX Palette, minSdk 29 / targetSdk 35. Source is in `android/app/src/main/java/com/joenet/mixtape/`:

| File | Role |
|---|---|
| `PlaybackService.kt` | Player + media session; background playback, resume-from-headset, skip unreadable files |
| `PlayerUi.kt` | Compose-observable MediaController wrapper used by the screens |
| `Library.kt` | MediaStore query → songs, grouped into folder playlists |
| `SyncManager.kt` | LAN discovery + download into `Music/<playlist>/` via MediaStore |
| `QueueStore.kt` | Persists queue / position / shuffle / repeat |
| `ui/Cassette.kt` | The Canvas-drawn cassette with tape-accurate spinning reels |
| `ui/Theme.kt` | Palette, fonts, shapes |
| `ui/*Screen*.kt`, `ui/Components.kt` | Library, playlist, now-playing, sync screens and shared pieces |

This project is vibe-coded**
## A note on YouTube's terms

Downloading from YouTube is against YouTube's Terms of Service except where YouTube offers a download option, and the music itself is usually copyrighted. Use this for content you have the right to keep offline (your own uploads, Creative Commons or public-domain music, and so on), for personal listening only.
