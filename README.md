# Mixtape — YouTube playlist → MP3 → phone music player

Two parts:

| Part | Where | What it does |
|---|---|---|
| **Importer** (`importer/`) | Windows PC | Downloads every song in a YouTube playlist as a tagged MP3 with square cover art, loudness (ReplayGain) tags and, when it can find them, synced lyrics. Re-running it only fetches songs added since last time. Also runs a small Wi-Fi server the app syncs from, optionally from the system tray with a nightly update. |
| **Mixtape app** (`android/` → `Mixtape.apk`) | Android 10+ phone | A music player for the MP3s on the phone, built like a cassette deck and laid out like a streaming app: Home / Search / Library, your own tapes, history, lyrics, sleep timer, Android Auto, a home-screen widget. Keeps playing in the background and pulls new songs from the PC over Wi-Fi, by itself if you let it. |

The importer runs on the PC on purpose. YouTube now needs yt-dlp plus a JavaScript runtime (deno) plus ffmpeg, and yt-dlp needs frequent updates. All of that is easy to keep current on a PC and fragile inside an APK.

## Quick start

1. **Install the app.** Copy `Mixtape.apk` to the phone and open it (allow "install unknown apps" for your file manager when asked). Or, with USB debugging on:
   `powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 -Install`
2. **Import a playlist.** Double-click `importer\import.bat` and paste the playlist URL. The first run sets itself up (Python venv, yt-dlp, deno, ffmpeg: about 400 MB, all inside `importer\`). Songs land in `library\<playlist name>\`.
3. **Sync to the phone.** Double-click `importer\sync.bat`. If Windows Firewall asks, allow Python on **private** networks. In the app: Library → **Get new songs** → **Find PC** → **Sync now**. New songs go to the phone's `Music/<playlist name>/` folder and show up as tapes.
4. **Optional: let new music arrive by itself.** Run `importer\install-autosync.ps1` once (see [Auto-sync](#auto-sync)).

## Importer

```
import.bat <playlist-url> [more urls]     download / top up these playlists
import.bat "Road trip.m3u8"               download a tape someone shared from the app
import.bat update                         re-check every playlist, then finish every song (loudness + lyrics)
import.bat normalize                      add loudness tags to songs that don't have them
import.bat lyrics                         look up lyrics for songs that don't have any
import.bat                                interactive: paste URLs or a tape file, u = update all, s = start sync
sync.bat                                  Wi-Fi sync server for the app
```

- Works with `youtube.com/playlist?list=…`, `watch?v=…&list=…`, and YouTube Music playlist URLs. A plain video URL goes into a `Singles` folder.
- Files are named `Title [videoId].mp3`. The video id is how both the importer and the app know what you already have, so **don't rename the files**. Moving them around is fine.
- Tags: title / artist come from YouTube Music metadata when the video has it, or from an `Artist - Song` title otherwise ("(Official Video)" and similar get stripped). Album = playlist name, track number = position in the playlist.
- **Loudness:** every song is measured with ffmpeg's EBU R128 meter and gets `REPLAYGAIN_TRACK_GAIN` / `_PEAK` tags. Only the tags are written; the audio isn't re-encoded, so it costs no quality and can be undone. The app uses them to turn loud songs down.
- **Lyrics:** looked up on [LRCLIB](https://lrclib.net) (free, no account) and saved as `Title [videoId].lrc` next to the MP3. Synced lyrics are only used when LRCLIB's version is within 3 s of ours, so the timing fits; otherwise plain lyrics. Songs with nothing are re-asked after 30 days, not every night.
- **Shared tapes:** "Share as a playlist file" in the app writes an `.m3u8` of YouTube links. `import.bat friend.m3u8` downloads it into its own folder under the tape's name.
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
| `--no-lyrics` | Skip the LRCLIB lookups. |
| `--log FILE` | Append all output to FILE instead of the console (the nightly task uses `logs\update.log`). |
| `--library D:\Music` | Put the library somewhere else. Pass the same option to `sync.bat`. |

## Auto-sync

`powershell -ExecutionPolicy Bypass -File importer\install-autosync.ps1 [-At 03:30]` sets up:

1. **A nightly Task Scheduler job**, "Mixtape nightly update", that runs `import.bat update` without a window at 03:30 (or as soon as the PC is on after that). Output goes to `importer\logs\update.log`.
2. **The sync server in the system tray**, started at login (a shortcut in your Startup folder) instead of a console window. Its menu shows the song count and address, the last time the phone synced, and has **Update playlists now**, **Open music folder** and **Open logs**. The first time it starts, Windows Firewall may ask about `pythonw.exe`: allow it on private networks.

On the phone, **Settings → Sync automatically** (on by default, after your first manual sync) checks the PC every few hours while the phone charges on Wi-Fi and pulls new songs, lyrics and loudness. They show up on Home under *New from your PC*.

`importer\uninstall-autosync.ps1` removes the task and the Startup shortcut and stops the tray icon. Your music and `sync.bat` are left alone.

## App

The look is cover-first, with a cassette deck's character. The cover art is the main element, and each tape keeps its identity as a small live cassette badge on the corner of its cover (the reels turn and the tape moves as it plays; the player flips to the full cassette). The theme is light, dark or follows the phone: warm paper by day, an espresso-black deck by night. Bars, the mini player, chips and dialogs are Liquid Glass, translucent surfaces that blur what scrolls beneath them (blur needs Android 12 or newer; older phones get a more opaque tint). Orange means something is live right now (the playing song, progress, the play light, REC) and is used for nothing else. Text is Figtree, an open geometric sans standing in for Spotify Mix, which is proprietary and can't be bundled; times use a monospace tape counter, printed legends (SIDE A, C-90, REC) are small tracked caps, and everything else is plain sentence case. With "Remove animations" on, the reels stand still.

**Getting around** — three tabs on the deck's function selector, each with its own back stack (tap the tab you're on to go back to its top):

- **Home** knows your habits: *Jump back in* (continues a tape where you stopped), *New from your PC*, *On repeat*, *Forgotten favourites*, *Your Rewind* (this month's listening time, top songs and artists) and *Everything*.
- **Search** ignores accents and word order ("son tung" finds "Sơn Tùng M-TP"): a top result, then songs, artists and tapes. With an empty box it shows recent searches and recently played songs.
- **Library**: Tapes (Liked songs, the ones you recorded, and one per synced folder), Artists and Songs. The gear opens Settings.

**Now playing** slides up from the mini player and follows your finger. Swipe the mini player sideways to skip.

- The tape transport: the reels wind tape across as the song plays, and the emptier reel spins faster, like a real deck. Tap the cassette to turn it over (side B is the cover); swipe it to skip.
- **Deck keys** for shuffle, rewind, play, fast-forward and repeat. Play latches down with a light; hold ◀◀ / ▶▶ to scrub.
- **Lyrics** (the lyrics key, when the song has them): the line being sung is cream, the list follows the song, and tapping a line plays from there. Pull down past the first line to fold the player away.
- **Sleep timer** (the moon): 15 / 30 / 45 / 60 minutes or the end of the song. It fades out over the last 10 seconds, and the time left shows as a tape counter.
- **Queue**: now playing, *Next in queue* (what you added), and *Next from: the tape*. Drag to reorder, swipe to remove, *Clear queue*, *Save as tape*.

**Your music**

- **Long-press any song**: Play next, Add to queue, Start radio, Like, Add to tape…, Go to artist, Go to tape.
- **Liked songs** is a tape. Like from Now Playing, the long-press sheet, or the heart in the notification.
- **Record your own tapes** from songs, the queue or search. Rename and recolour them, drag to reorder, and optionally split them into Side A and Side B with a C-60 or C-90 length (a side that runs over is flagged). *Share as a playlist file* sends a tape to a friend's importer.
- **Song radio** builds a queue from one song: the same artists, songs that share a tape with it, and songs you play in the same sittings, weighted by how much you play them and leaving out anything from the last hour.
- **Smart shuffle** never plays the same artist twice in a row and favours liked songs a little. Turning shuffle off restores the original order.

**Sound**

- **Even out loudness** (Settings, on by default) uses the importer's ReplayGain tags to turn loud songs down to about -14 LUFS, what streaming apps use. It works for songs synced before the tags existed too (the PC sends the values at sync time).
- **Equaliser** (Settings) opens the phone's own equaliser on Mixtape's audio.
- **Gapless**: live albums and DJ mixes play without gaps; the importer's MP3s carry the encoder delay and padding and the player trims them.

**Everywhere else**

- **Background playback**: a Media3 `MediaLibraryService` runs as a foreground media service. Music keeps going with the screen off, the app backgrounded, or the app swiped out of recents while playing. It pauses for calls and other apps' audio, and when headphones are unplugged.
- **Controls**: notification (with a Like button), lock screen, quick-settings media player, Bluetooth and headset buttons. Pressing play on a headset after the app was closed resumes the last queue. The queue, position, shuffle and repeat survive restarts.
- **Android Auto**: browse Home, Tapes and Artists, or search, from the car. Playing a song from a tape plays the rest of the tape after it.
- **"Hey Google, play … on Mixtape"** plays an artist, a tape or matching songs; "play music on Mixtape" carries on where you left off.
- **Home-screen widget**: the tape in the deck with its cover sticker, the song, and play/pause and next keys. Tap the tape to open the player.
- **Sync** only downloads songs the phone doesn't have yet and never deletes anything. Uses ports 47811/TCP and 47810/UDP.

On phones that kill background apps aggressively (some Xiaomi, Samsung and Huawei models), set Mixtape's battery usage to **Unrestricted** if music stops after a while.

## Troubleshooting

| Problem | Fix |
|---|---|
| **Find PC** finds nothing | Phone and PC must be on the same Wi-Fi. Guest networks often block device-to-device traffic. Windows must treat that network as **Private**, and Python (`python.exe` for `sync.bat`, `pythonw.exe` for the tray icon) must be allowed through the firewall. Otherwise type the `ip:port` that `sync.bat` or the tray menu shows. |
| `sync.bat` says the port is in use | The tray icon is already serving; you don't need `sync.bat` too. |
| New songs don't arrive by themselves | Sync by hand once (that saves the PC's address), keep **Settings → Sync automatically** on, and check the tray icon is running. The phone only checks while charging on Wi-Fi. `importer\logs\` has both logs. |
| Mixtape doesn't show up in Android Auto | Auto hides apps that weren't installed from the Play Store. In the Android Auto app: Settings → tap *Version* ten times to unlock developer settings → ⋮ → Developer settings → enable **Unknown sources**. |
| A song has no lyrics | LRCLIB didn't have a match (or only one with a different length). `import.bat lyrics` asks again. |
| App shows no songs | Grant the music permission (the app asks; otherwise Settings → Apps → Mixtape → Permissions → Music and audio). |
| `Sign in to confirm you're not a bot` | YouTube is rate-limiting you. Wait a while, raise `--pause`, or use `--cookies-from-browser firefox`. |
| A song failed | Re-run the same command. Finished songs are skipped and failed ones retried. Region-locked or age-restricted videos need cookies. |

## Building the APK yourself

The toolchain lives in `D:\Android` and changes nothing system-wide:

```
powershell -ExecutionPolicy Bypass -File android\setup-toolchain.ps1   # once: JDK 17, Android SDK, Gradle (~1.5 GB, accepts the Android SDK license)
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1         # -> Mixtape.apk
```

The release APK is signed with the local debug key in `D:\Android\user-home\debug.keystore`. Keep that file: an APK signed with a different key can't install over the old one (you'd have to uninstall first, which loses your history, likes and recorded tapes, but none of your music).

Stack: Kotlin 2.1, Jetpack Compose (Material 3 with a custom light and dark palette; one bundled OFL font, [Figtree](https://fonts.google.com/specimen/Figtree) (a variable font, about 61 KB inside the APK), so nothing is downloaded at runtime; its license is `android/app/src/main/assets/licenses/Figtree-OFL.txt`), Media3 1.5 (ExoPlayer, MediaLibrarySession), Room, WorkManager, Glance, [Reorderable](https://github.com/Calvin-LL/Reorderable), AndroidX Palette, minSdk 29 / targetSdk 35. Source is in `android/app/src/main/java/com/joenet/mixtape/`:

| File | Role |
|---|---|
| `PlaybackService.kt` | Player + library session: background playback, history, likes, loudness, sleep timer, Android Auto / voice, widget updates |
| `MixPlayer.kt` | Shuffle that reorders the queue for real (smart shuffle, restorable order) |
| `Catalog.kt` | The browse tree Android Auto sees, and what each id plays |
| `PlayerUi.kt` | Compose-observable MediaController wrapper used by the screens |
| `MainViewModel.kt` | Navigation, tapes, likes, Home shelves, Rewind, radio, settings |
| `Library.kt`, `Search.kt` | MediaStore → songs and folder tapes; accent-free search and voice picks |
| `data/Db.kt` | Room: plays, resume points, likes, your tapes |
| `Radio.kt` | Song radio from artists, shared tapes and co-plays |
| `SyncManager.kt`, `SyncWorker.kt`, `Sidecar.kt` | LAN discovery and pull into `Music/<playlist>/`; the background job; lyrics and loudness from the PC |
| `Lyrics.kt`, `ui/LyricsView.kt` | LRC parsing and the scrolling lyrics |
| `QueueStore.kt` | Persists queue / position / shuffle / repeat |
| `ui/Cassette.kt`, `ui/DeckKeys.kt`, `ui/NavBar.kt` | The Canvas-drawn cassette, the transport keys, the function selector |
| `ui/*Screen.kt`, `ui/Components.kt`, `ui/SongActions.kt`, `ui/TapeDialogs.kt` | Screens and shared pieces |
| `widget/` | The Glance home-screen widget |

This project is vibe-coded**
## A note on YouTube's terms

Downloading from YouTube is against YouTube's Terms of Service except where YouTube offers a download option, and the music itself is usually copyrighted. Use this for content you have the right to keep offline (your own uploads, Creative Commons or public-domain music, and so on), for personal listening only.
