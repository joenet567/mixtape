"""Mixtape importer: YouTube playlists -> tagged MP3s, plus a Wi-Fi sync server for the phone app.

    import.bat <playlist-url> [more urls]   download a playlist (re-run any time: only new songs are fetched)
    import.bat "Road trip.m3u8"             download a tape shared from the Mixtape app
    import.bat update                       re-check every playlist imported before
    import.bat normalize                    add loudness (ReplayGain) tags to songs that lack them
    import.bat lyrics                       look up lyrics for songs that don't have any yet
    sync.bat                                serve the library to the Mixtape app over Wi-Fi

Each playlist becomes library/<playlist title>/ holding "<title> [<video id>].mp3" files with
title/artist/album/track tags and square cover art embedded. The video id in the file name is how
both this script and the phone app tell which songs they already have.

Every song also gets ReplayGain tags (measured, never re-encoded: the app turns loud songs down)
and, when LRCLIB has them, lyrics in "<title> [<video id>].lrc" next to it.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_LIBRARY = HERE.parent / "library"
FFMPEG_DIR = HERE / "tools" / "ffmpeg"
SOURCES_FILE = "sources.json"      # library-level: folder name -> playlist URL, for `update`
MANIFEST_FILE = ".playlist.json"   # per playlist folder: current order, read by the sync server
LYRICS_FILE = ".lyrics.json"       # per playlist folder: what LRCLIB said, so misses aren't re-asked every night
HTTP_PORT = 47811
DISCOVERY_PORT = 47810
ID_IN_NAME = re.compile(r"\[([A-Za-z0-9_-]{11})\]\.mp3$", re.IGNORECASE)
UNAVAILABLE_TITLES = {"[Private video]", "[Deleted video]", "[Unavailable video]"}
# ReplayGain 2.0 reference level. The app adds +4 dB on playback, which lands on Spotify's -14 LUFS.
RG_REFERENCE_LUFS = -18.0
LRCLIB_API = "https://lrclib.net/api"
USER_AGENT = "Mixtape importer/1.0 (personal music player; yt-dlp playlists)"
LYRICS_RETRY_DAYS = 30
# Files younger than this may still be converting / being tagged; the sync server leaves them for next time.
FRESH_SECONDS = 90


# --------------------------------------------------------------------------- environment

def in_venv() -> bool:
    return sys.prefix != sys.base_prefix


def run_quiet(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    """subprocess.run without a console window flashing up when running from the tray or the scheduler."""
    if os.name == "nt":
        kw.setdefault("creationflags", subprocess.CREATE_NO_WINDOW)
    return subprocess.run(cmd, **kw)


def auto_update() -> None:
    """YouTube breaks old yt-dlp releases often; upgrade at most once a day."""
    if not in_venv():
        return
    stamp = Path(sys.prefix) / ".last-update"
    if stamp.exists() and time.time() - stamp.stat().st_mtime < 24 * 3600:
        return
    print("Checking for yt-dlp updates...")
    r = run_quiet([sys.executable, "-m", "pip", "install", "-q", "-U", "--disable-pip-version-check",
                   "-r", str(HERE / "requirements.txt")], capture_output=True, text=True, errors="replace")
    if r.returncode == 0:
        stamp.touch()
    else:
        print(f"  update failed, carrying on with the installed version:\n{r.stderr.strip()[-600:]}")


def open_log(path: Path):
    """Append-only log for runs without a console (the nightly task, the tray app); kept under ~1 MB."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        if path.stat().st_size > 1_000_000:
            tail = path.read_bytes()[-250_000:]
            path.write_bytes(tail[tail.find(b"\n") + 1:])
    except OSError:
        pass
    return open(path, "a", encoding="utf-8", errors="replace", buffering=1)


def console() -> bool:
    """False when output goes to a log file: progress lines that redraw with \\r would only clutter it."""
    return bool(getattr(sys.stdout, "isatty", lambda: False)())


def prepare_path() -> None:
    # deno.exe (JS runtime yt-dlp needs for YouTube) lives next to the venv's python.exe
    extra = [str(Path(sys.executable).parent), str(FFMPEG_DIR)]
    os.environ["PATH"] = os.pathsep.join(extra + [os.environ.get("PATH", "")])


def which(name: str) -> str | None:
    import shutil
    return shutil.which(name)


# --------------------------------------------------------------------------- tagging

_NOISE = re.compile(
    r"\s*[\(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|lyrics?|lyric\s*video|visuali[sz]er|"
    r"hd|hq|4k|mv|m/v|official|remastered(?:\s*\d{4})?|explicit)\s*[\)\]]",
    re.IGNORECASE,
)


def title_and_artist(info: dict) -> tuple[str, str]:
    """Best-effort song title / artist from YouTube metadata."""
    track = info.get("track")
    artists = info.get("artists") or ([info["artist"]] if info.get("artist") else None)
    if track and artists:  # YouTube Music / "Artist - Topic" uploads carry real music metadata
        return track, ", ".join(dict.fromkeys(artists))
    raw = info.get("title") or info.get("id", "")
    clean = _NOISE.sub("", raw).strip() or raw
    m = re.match(r"^(.+?)\s+[-–—]\s+(.+)$", clean)  # "Artist - Song"
    if m:
        return m.group(2).strip().strip('"'), m.group(1).strip()
    uploader = info.get("channel") or info.get("uploader") or ""
    uploader = re.sub(r"\s*-\s*Topic$|VEVO$", "", uploader).strip()
    return clean, (", ".join(artists) if artists else uploader) or "Unknown artist"


def make_tag_pp(album: str, track_numbers: dict[str, int]):
    from yt_dlp.postprocessor import PostProcessor

    class MusicTags(PostProcessor):
        """Runs before FFmpegMetadata; meta_* fields override what it would write."""

        def run(self, info):
            title, artist = title_and_artist(info)
            info["meta_title"] = title
            info["meta_artist"] = artist
            info["meta_album"] = album
            info["meta_album_artist"] = "Various Artists"
            if info.get("id") in track_numbers:
                info["meta_track"] = str(track_numbers[info["id"]])
            year = str(info.get("release_year") or (info.get("upload_date") or "")[:4])
            if year:
                info["meta_date"] = year
            info["meta_comment"] = info.get("webpage_url") or ""
            info["meta_description"] = ""  # YouTube descriptions/categories are noise in a music player
            info["meta_synopsis"] = ""
            info["meta_genre"] = ""
            return [], info

    return MusicTags()


def song_info(path: Path) -> tuple[str, str, float]:
    """(title, artist, seconds) from a song's tags, falling back to the file name."""
    from mutagen.mp3 import MP3
    title = ID_IN_NAME.sub("", path.name).strip()
    artist, length = "", 0.0
    try:
        audio = MP3(path)
        length = audio.info.length
        if audio.tags:
            title = str(audio.tags.get("TIT2") or title)
            artist = str(audio.tags.get("TPE1") or "")
    except Exception:  # unreadable tags shouldn't stop a whole library run
        pass
    return title, artist, length


# --------------------------------------------------------------------------- loudness (ReplayGain tags)

def ffmpeg_exe() -> str:
    local = FFMPEG_DIR / "ffmpeg.exe"
    return str(local) if local.exists() else "ffmpeg"


def measure_loudness(path: Path) -> tuple[float, float] | None:
    """Integrated loudness (LUFS) and true peak (dBTP) from ffmpeg's EBU R128 meter; None for silence."""
    for _ in range(2):  # a file briefly locked by a virus scan or indexer reads as nothing; try once more
        r = run_quiet([ffmpeg_exe(), "-hide_banner", "-nostats", "-i", str(path), "-map", "0:a:0",
                       "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"],
                      capture_output=True, text=True, encoding="utf-8", errors="replace")
        lufs = re.search(r"Integrated loudness:\s*I:\s*(-?[\d.]+|-inf) LUFS", r.stderr)
        peak = re.search(r"True peak:\s*Peak:\s*(-?[\d.]+|-inf) dBFS", r.stderr)
        if lufs and peak:
            if "inf" in lufs.group(1):
                return None
            return float(lufs.group(1)), float(peak.group(1)) if "inf" not in peak.group(1) else -60.0
        time.sleep(1)
    return None


def read_replaygain(path: Path) -> tuple[float, float] | None:
    """(track gain dB, track peak) if the song carries ReplayGain tags (ours or any other tool's)."""
    from mutagen.id3 import ID3, ID3NoHeaderError
    try:
        frames = {f.desc.upper(): str(f.text[0]) for f in ID3(path).getall("TXXX") if f.text}
    except (ID3NoHeaderError, OSError, ValueError):
        return None
    gain = re.match(r"\s*([-+]?\d+(?:\.\d+)?)", frames.get("REPLAYGAIN_TRACK_GAIN", ""))
    if not gain:
        return None
    peak = re.match(r"\s*(\d+(?:\.\d+)?)", frames.get("REPLAYGAIN_TRACK_PEAK", ""))
    return float(gain.group(1)), float(peak.group(1)) if peak else 1.0


def write_replaygain(path: Path, lufs: float, true_peak_db: float) -> float:
    """Tags only: the audio isn't touched, so this can be undone and never costs quality."""
    from mutagen.id3 import ID3, TXXX, ID3NoHeaderError
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        tags = ID3()
    gain = RG_REFERENCE_LUFS - lufs
    for desc, value in (("REPLAYGAIN_TRACK_GAIN", f"{gain:+.2f} dB"),
                        ("REPLAYGAIN_TRACK_PEAK", f"{10 ** (true_peak_db / 20):.6f}")):
        tags.setall(f"TXXX:{desc}", [TXXX(encoding=3, desc=desc, text=[value])])
    tags.save(path, v2_version=3)  # yt-dlp writes ID3v2.3; keep it that way for older players
    return gain


# --------------------------------------------------------------------------- lyrics (LRCLIB)

def lrclib(endpoint: str, params: dict):
    """GET an LRCLIB endpoint; None when it has no such track. Network errors propagate (= try again later)."""
    req = urllib.request.Request(f"{LRCLIB_API}/{endpoint}?{urllib.parse.urlencode(params)}",
                                 headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code < 500 and e.code != 429 or attempt == 3:
                raise
            time.sleep(5 * (attempt + 1) ** 2)  # busy / rate limited: back off (5, 20, 45 s) and retry


def _fold(s: str) -> str:
    return re.sub(r"[\W_]+", " ", s.casefold()).strip()


def find_lyrics(title: str, artist: str, seconds: float) -> tuple[str, str] | None:
    """('synced' | 'plain' | 'instrumental', text) from LRCLIB, or None if it has nothing usable.

    Synced lyrics only count when LRCLIB's track is within 3 s of ours; otherwise the timings would
    drift (music videos with a long intro), so we fall back to the plain text of a same-titled track.
    """
    bare = re.sub(r"\s*[(\[][^)\]]*[)\]]?", "", title).strip() or title  # "Song (Short Film ...)" -> "Song"
    m = re.match(r"^(.+?)\s+[-–—]\s+(.+)$", bare)  # raw video titles: "Artist - Song"
    if m:
        artist, bare = artist or m.group(1), m.group(2)
    artists = [a.strip() for a in re.split(r",|&| feat\.? | ft\.? | x ", artist, flags=re.IGNORECASE) if a.strip()]
    # 1. exact match (cheap for LRCLIB), 2. one search by the cleaned-up title, 3. a free-text search
    hit = lrclib("get", {"track_name": title, "artist_name": artist, "duration": round(seconds)})
    if hit and (hit.get("syncedLyrics") or hit.get("instrumental")):
        return ("synced", hit["syncedLyrics"]) if hit.get("syncedLyrics") else ("instrumental", "")
    hits = lrclib("search", {"track_name": bare, "artist_name": artists[0] if artists else artist}) or []
    if not hits:
        hits = lrclib("search", {"q": f"{artists[0] if artists else ''} {bare}".strip()}) or []
    if hit:
        hits.insert(0, hit)
    hits = [h for h in hits if _fold(h.get("trackName") or "").startswith(_fold(bare)) or _fold(bare) in _fold(h.get("name") or "")]
    close = sorted((h for h in hits if abs((h.get("duration") or 0) - seconds) <= 3),
                   key=lambda h: (not h.get("syncedLyrics"), abs(h["duration"] - seconds)))
    for h in close:
        if h.get("syncedLyrics"):
            return "synced", h["syncedLyrics"]
        if h.get("instrumental"):
            return "instrumental", ""
    for h in close + hits:
        if h.get("plainLyrics"):
            return "plain", h["plainLyrics"]
    return None


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


# --------------------------------------------------------------------------- finishing: loudness + lyrics

def finish_songs(folder: Path, args, retry_lyrics: bool = False) -> None:
    """Loudness tags and lyrics for every song in [folder] that doesn't have them yet."""
    songs = sorted(existing_ids(folder).items(), key=lambda kv: kv[1].name.lower())

    untagged = [(vid, p) for vid, p in songs if read_replaygain(p) is None]
    if untagged:
        print(f'  Evening out loudness in "{folder.name}": {len(untagged)} songs')
        for vid, p in untagged:
            m = measure_loudness(p)
            if m is None:
                print(f"    {ID_IN_NAME.sub('', p.name).strip()}: silent or unreadable, skipped")
                continue
            try:
                gain = write_replaygain(p, *m)
            except OSError as e:  # e.g. the file is open in another player
                print(f"    {ID_IN_NAME.sub('', p.name).strip()}: couldn't write tags ({e})")
                continue
            print(f"    {ID_IN_NAME.sub('', p.name).strip()}: {m[0]:.1f} LUFS -> {gain:+.1f} dB")

    if args.no_lyrics:
        return
    cache_path = folder / LYRICS_FILE
    cache = load_json(cache_path)
    today = time.strftime("%Y-%m-%d")
    retry_before = time.strftime("%Y-%m-%d", time.localtime(time.time() - LYRICS_RETRY_DAYS * 86400))

    def wanted(vid: str, p: Path) -> bool:
        if p.with_suffix(".lrc").exists():
            return False
        seen = cache.get(vid) or {}
        if seen.get("result") == "instrumental":
            return False
        return retry_lyrics or not seen or seen.get("checked", "") < retry_before

    todo = [(vid, p) for vid, p in songs if wanted(vid, p)]
    if not todo:
        return
    print(f'  Looking up lyrics for "{folder.name}": {len(todo)} songs')
    found = 0
    for vid, p in todo:
        title, artist, seconds = song_info(p)
        try:
            hit = find_lyrics(title, artist, seconds)
        except (OSError, ValueError) as e:  # offline / LRCLIB down: stop here, the next run carries on
            print(f"    LRCLIB unreachable ({e}); will try again next time")
            break
        if hit and hit[0] != "instrumental":
            p.with_suffix(".lrc").write_text(hit[1].strip() + "\n", encoding="utf-8")
            found += 1
        cache[vid] = {"result": hit[0] if hit else "none", "checked": today}
        print(f"    {title}: {hit[0] if hit else 'none found'}")
        time.sleep(1)  # LRCLIB is a free community service and answers bursts with 503s
    cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"  Lyrics: {found} of {len(todo)} found")


def finish_library(library: Path, args, retry_lyrics: bool = False) -> None:
    if library.is_dir():
        for folder in sorted((p for p in library.iterdir() if p.is_dir()), key=lambda p: p.name.lower()):
            finish_songs(folder, args, retry_lyrics)


# --------------------------------------------------------------------------- download

def ydl_options(folder: Path, args) -> dict:
    import yt_dlp

    cli = [
        "-f", "bestaudio/best",
        "-x", "--audio-format", "mp3", "--audio-quality", args.quality,
        "--embed-metadata",
        "--embed-thumbnail", "--convert-thumbnails", "jpg",
        # centre-crop the 16:9 thumbnail to a square cover
        "--ppa", "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -q:v 3 "
                 "-vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"",
        "-o", "%(title).90B [%(id)s].%(ext)s",
        "-P", str(folder),
        "--no-playlist", "--no-mtime",
        "--retries", "10", "--fragment-retries", "10",
    ]
    if FFMPEG_DIR.exists():
        cli += ["--ffmpeg-location", str(FFMPEG_DIR)]
    if not which("deno") and which("node"):
        cli += ["--js-runtimes", "node"]
    if args.cookies_from_browser:
        cli += ["--cookies-from-browser", args.cookies_from_browser]
    if args.cookies:
        cli += ["--cookies", args.cookies]
    if args.trim:
        cli += ["--sponsorblock-remove", "music_offtopic"]
    opts = yt_dlp.parse_options(cli).ydl_opts
    opts.update(quiet=True, noprogress=True, ignoreerrors=False)
    return opts


def list_playlist(url: str, args) -> tuple[str, list[dict], bool]:
    """Fast listing without downloading anything: (playlist title, entries in order, is a playlist)."""
    import yt_dlp

    opts = {"extract_flat": "in_playlist", "quiet": True, "skip_download": True, "noplaylist": False}
    if args.cookies_from_browser:
        opts["cookiesfrombrowser"] = (args.cookies_from_browser,)
    if args.cookies:
        opts["cookiefile"] = args.cookies
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    is_playlist = info.get("_type") in ("playlist", "multi_video")
    if is_playlist:
        entries = [e for e in info.get("entries") or [] if e and e.get("id")]
        title = info.get("title") or "Playlist"
    else:
        entries, title = [info], args.singles_folder
    seen, unique = set(), []
    for e in entries:
        if e["id"] not in seen:
            seen.add(e["id"])
            unique.append(e)
    return title, unique, is_playlist


_TAPE_ID = re.compile(r"(?:[?&]v=|youtu\.be/|/shorts/|\[)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")


def read_tape_file(path: Path) -> tuple[str, list[dict]]:
    """A tape shared from the Mixtape app: an .m3u8 of YouTube links (importer file names work too)."""
    title, entries, seen, label = path.stem, [], set(), None
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = line.strip()
        if line.upper().startswith("#PLAYLIST:"):
            title = line.split(":", 1)[1].strip() or title
        elif line.upper().startswith("#EXTINF:"):
            label = line.split(",", 1)[1].strip() if "," in line else None
        elif line and not line.startswith("#"):
            m = _TAPE_ID.search(line)
            if m and m.group(1) not in seen:
                seen.add(m.group(1))
                entries.append({"id": m.group(1), "title": label or m.group(1)})
            label = None
    return title, entries


def list_source(target: str, args) -> tuple[str, list[dict], bool]:
    """(title, entries, is a YouTube playlist) for a playlist / video URL or a shared tape file."""
    path = Path(target)
    if path.suffix.lower() in (".m3u", ".m3u8") and path.is_file():
        title, entries = read_tape_file(path)
        return title, entries, False
    return list_playlist(target, args)


def existing_ids(folder: Path) -> dict[str, Path]:
    out = {}
    if folder.is_dir():
        for f in folder.iterdir():
            m = ID_IN_NAME.search(f.name)
            if m:
                out[m.group(1)] = f
    return out


def safe_folder_name(name: str) -> str:
    from yt_dlp.utils import sanitize_filename
    name = sanitize_filename(name).strip().rstrip(". ")
    return name[:80] or "Playlist"


def import_playlist(url: str, library: Path, args) -> bool:
    import yt_dlp
    from yt_dlp.utils import DownloadError

    print(f"\nReading {url}")
    try:
        title, entries, is_playlist = list_source(url, args)
    except DownloadError as e:
        print(f"  Could not read the playlist: {e}")
        print("  Private playlists need --cookies-from-browser firefox (see README).")
        return False
    if not entries:
        print("  No YouTube songs found in it.")
        return False

    folder = library / safe_folder_name(title)
    folder.mkdir(parents=True, exist_ok=True)
    playable = [e for e in entries if (e.get("title") or "") not in UNAVAILABLE_TITLES]
    skipped_unavailable = len(entries) - len(playable)
    track_numbers = {e["id"]: i for i, e in enumerate(playable, 1)}
    have = existing_ids(folder)
    todo = [e for e in playable if e["id"] not in have]

    print(f'Playlist "{title}" -> {folder}')
    print(f"  {len(playable)} songs, {len(playable) - len(todo)} already downloaded, {len(todo)} to get"
          + (f", {skipped_unavailable} private/deleted skipped" if skipped_unavailable else ""))
    if args.max_new is not None and len(todo) > args.max_new:
        todo = todo[:args.max_new]
        print(f"  --max-new: only getting the first {len(todo)} this time")

    failed: list[tuple[str, str]] = []
    if todo:
        opts = ydl_options(folder, args)
        opts["progress_hooks"] = [progress_hook]
        opts["postprocessor_hooks"] = [pp_hook]
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.add_post_processor(make_tag_pp(title, track_numbers), when="pre_process")
            for n, e in enumerate(todo, 1):
                name = e.get("title") or e["id"]
                print(f"  [{n}/{len(todo)}] {name}")
                pp_hook.last = None
                try:
                    ydl.download([f"https://www.youtube.com/watch?v={e['id']}"])
                except DownloadError as ex:
                    msg = re.sub(r"^ERROR:\s*(\[[^\]]+\]\s*)?", "", str(ex)).strip()
                    failed.append((name, msg))
                    print(f"\r      failed: {msg[:150]}")
                    continue
                if e["id"] in existing_ids(folder):
                    print("\r      done" + " " * 40)
                else:
                    failed.append((name, "no mp3 was produced"))
                if n < len(todo) and args.pause > 0:
                    time.sleep(args.pause)  # be gentle; YouTube throttles rapid bulk downloads

    write_manifest(folder, title, url, playable)
    if is_playlist:
        remember_source(library, folder.name, url)
    finish_songs(folder, args)

    got = len(todo) - len(failed)
    print(f'Finished "{title}": {got} new, {len(failed)} failed.')
    for name, msg in failed:
        print(f"  x {name}: {msg[:200]}")
    return not failed


_last_line = 0.0


def progress_hook(d: dict) -> None:
    global _last_line
    if d["status"] == "downloading" and time.time() - _last_line > 0.3 and console():
        _last_line = time.time()
        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        pct = f"{100 * d['downloaded_bytes'] / total:5.1f}%" if total else ""
        speed = d.get("speed")
        rate = f"  {speed / 1e6:.1f} MB/s" if speed else ""
        print(f"\r      downloading {pct}{rate}      ", end="", flush=True)


_PP_LABELS = {"ExtractAudio": "converting to mp3", "EmbedThumbnail": "adding cover art", "Metadata": "writing tags"}


def pp_hook(d: dict) -> None:
    label = _PP_LABELS.get(d["postprocessor"])
    if d["status"] == "started" and label and label != pp_hook.last and console():  # yt-dlp reports some stages twice
        pp_hook.last = label
        print(f"\r      {label}...                    ", end="", flush=True)


pp_hook.last = None


def write_manifest(folder: Path, title: str, url: str, playable: list[dict]) -> None:
    """Playlist order for the sync server and an .m3u8 for PC players (VLC etc.)."""
    have = existing_ids(folder)
    ordered = [e["id"] for e in playable if e["id"] in have]
    # songs since removed from the YouTube playlist stay on disk; keep them at the end
    ordered += sorted(set(have) - set(ordered), key=lambda i: have[i].name.lower())
    tracks = [{"id": i, "file": have[i].name} for i in ordered]
    (folder / MANIFEST_FILE).write_text(json.dumps(
        {"title": title, "url": url, "updated": time.strftime("%Y-%m-%d %H:%M"), "tracks": tracks},
        ensure_ascii=False, indent=1), encoding="utf-8")
    (folder / "playlist.m3u8").write_text(
        "#EXTM3U\n" + "".join(f"{t['file']}\n" for t in tracks), encoding="utf-8")


def remember_source(library: Path, folder_name: str, url: str) -> None:
    path = library / SOURCES_FILE
    try:
        sources = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        sources = {}
    sources[folder_name] = url
    path.write_text(json.dumps(sources, ensure_ascii=False, indent=1), encoding="utf-8")


def update_all(library: Path, args) -> bool:
    sources = load_json(library / SOURCES_FILE)
    if not sources:
        print("No playlists to re-check yet (import.bat <playlist url> adds one).")
    ok = True
    for url in dict.fromkeys(sources.values()):
        ok = import_playlist(url, library, args) and ok
    print("\nChecking every song for loudness tags and lyrics...")
    finish_library(library, args)  # singles, shared tapes and songs from before these features
    return ok


# --------------------------------------------------------------------------- Wi-Fi sync server

_extras_cache: dict[str, tuple[int, int, dict]] = {}


def song_extras(path: Path) -> dict:
    """What the phone can't get from MediaStore: the ReplayGain values (cached until the file changes).

    Sending them in the listing means songs the phone already has get evened out too, without
    downloading them again.
    """
    st = path.stat()
    hit = _extras_cache.get(str(path))
    if hit and hit[0] == st.st_mtime_ns and hit[1] == st.st_size:
        return hit[2]
    rg = read_replaygain(path)
    extras = {"gain": rg[0], "peak": rg[1]} if rg else {}
    _extras_cache[str(path)] = (st.st_mtime_ns, st.st_size, extras)
    return extras


def library_listing(library: Path) -> dict:
    playlists = []
    now = time.time()
    if library.is_dir():
        for folder in sorted((p for p in library.iterdir() if p.is_dir()), key=lambda p: p.name.lower()):
            files = {f.name: f for f in folder.glob("*.mp3") if now - f.stat().st_mtime > FRESH_SECONDS}
            if not files:
                continue
            order = []
            try:
                manifest = json.loads((folder / MANIFEST_FILE).read_text(encoding="utf-8"))
                order = [t["file"] for t in manifest.get("tracks", []) if t["file"] in files]
            except (OSError, ValueError, KeyError, TypeError):
                pass
            order += sorted(set(files) - set(order), key=str.lower)
            tracks = []
            for name in order:
                m = ID_IN_NAME.search(name)
                track = {
                    "id": m.group(1) if m else name,
                    "file": name,
                    "title": ID_IN_NAME.sub("", name).strip() or name,
                    "size": files[name].stat().st_size,
                    **song_extras(files[name]),
                }
                lrc = files[name].with_suffix(".lrc")
                if lrc.is_file():
                    track["lrc"] = lrc.stat().st_size  # fetched as /files/<folder>/<name>.lrc
                tracks.append(track)
            playlists.append({"name": folder.name, "tracks": tracks})
    return {"server": socket.gethostname(), "playlists": playlists}


# Set by the tray app to show the last phone visit in its menu.
on_phone_event = None


def phone_event(message: str) -> None:
    print(f"  {time.strftime('%H:%M:%S')} {message}")
    if on_phone_event:
        on_phone_event(message)


_SERVED_TYPES = {".mp3": "audio/mpeg", ".lrc": "text/plain; charset=utf-8"}


def make_handler(library: Path):
    root = library.resolve()

    class Handler(BaseHTTPRequestHandler):
        server_version = "MixtapeSync/1"

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            if path == "/api/library":
                body = json.dumps(library_listing(root), ensure_ascii=False).encode("utf-8")
                self._send(200, "application/json; charset=utf-8", body)
                phone_event(f"phone {self.client_address[0]} asked for the song list")
            elif path.startswith("/files/"):
                rel = urllib.parse.unquote(path[len("/files/"):])
                target = (root / rel).resolve()
                ctype = _SERVED_TYPES.get(target.suffix.lower())
                if root not in target.parents or not ctype or not target.is_file():
                    self._send(404, "text/plain", b"not found")
                    return
                size = target.stat().st_size
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(size))
                self.end_headers()
                with target.open("rb") as f:
                    while chunk := f.read(256 * 1024):
                        self.wfile.write(chunk)
                if target.suffix.lower() == ".mp3":
                    phone_event(f"sent {target.parent.name} / {ID_IN_NAME.sub('', target.name).strip()}")
            else:
                self._send(200, "text/plain; charset=utf-8",
                           "Mixtape sync server. Open the Mixtape app > Sync from PC.".encode())

        def _send(self, code, ctype, body):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *a):  # the prints above are friendlier than access logs
            pass

    return Handler


def discovery_responder(http_port: int) -> None:
    """Answers the app's 'Find PC' broadcast so nobody has to type an IP address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVERY_PORT))
    reply = f"MIXTAPE {http_port} {socket.gethostname()}".encode()
    while True:
        data, addr = s.recvfrom(512)
        if data.strip() == b"MIXTAPE_DISCOVER":
            s.sendto(reply, addr)
            print(f"  found by phone {addr[0]}")


def lan_addresses() -> list[str]:
    ips = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the LAN interface
            ips.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        ips += socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        pass
    return [ip for ip in dict.fromkeys(ips) if not ip.startswith(("127.", "169.254."))]


class SyncServer(ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second copy bind the same port silently; fail loudly instead.
    allow_reuse_address = os.name != "nt"


def start_server(library: Path, port: int) -> SyncServer:
    """Binds the HTTP port (OSError if something already serves it) and starts answering 'Find PC'."""
    library.mkdir(parents=True, exist_ok=True)
    server = SyncServer(("0.0.0.0", port), make_handler(library))
    threading.Thread(target=discovery_responder, args=(port,), daemon=True).start()
    return server


def serve(library: Path, port: int) -> None:
    try:
        server = start_server(library, port)
    except OSError:
        print(f"Port {port} is already in use: the sync server is probably running already")
        print("(look for the Mixtape cassette in the system tray). Nothing to do.")
        return
    listing = library_listing(library)
    n_songs = sum(len(p["tracks"]) for p in listing["playlists"])
    print(f"Mixtape sync server: {n_songs} songs in {len(listing['playlists'])} playlists ({library})")
    print("On the phone: Mixtape > Wi-Fi icon > Find PC, then Sync now.")
    for n, ip in enumerate(lan_addresses()):
        hint = "  (most likely)" if n == 0 else "  (other network adapter / VPN)"
        print(f"  address to type if Find PC doesn't work:  {ip}:{port}{hint}")
    print("If Windows Firewall asks, allow Python on private networks. Ctrl+C to stop.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopped.")


# --------------------------------------------------------------------------- CLI

def interactive(library: Path, args) -> None:
    print("Mixtape importer")
    print(f"Library: {library}\n")
    while True:
        print("Paste a YouTube playlist URL or a shared tape (.m3u8) path, or type:")
        print("  u = update all playlists,  s = start Wi-Fi sync,  q = quit")
        choice = input("> ").strip().strip('"')
        if choice.lower() in ("q", "quit", "exit", ""):
            return
        if choice.lower() in ("u", "update"):
            update_all(library, args)
        elif choice.lower() in ("s", "sync", "serve"):
            serve(library, args.port)
            return
        elif choice.startswith("http") or Path(choice).suffix.lower() in (".m3u", ".m3u8"):
            import_playlist(choice, library, args)
        else:
            print("That doesn't look like a URL or a tape file.")
        print()


def main() -> int:
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name)
        if stream is None:  # pythonw (tray / scheduled task) has no console at all
            setattr(sys, name, open(os.devnull, "w", encoding="utf-8"))
        else:  # song titles with emoji must not crash a redirected console
            stream.reconfigure(errors="replace")
    p = argparse.ArgumentParser(description="Download YouTube playlists as MP3s and sync them to the Mixtape app.")
    p.add_argument("targets", nargs="*",
                   help="playlist URLs or shared tape files (.m3u8), or one of: update, normalize, lyrics, serve")
    p.add_argument("--library", type=Path, default=DEFAULT_LIBRARY, help=f"music folder (default {DEFAULT_LIBRARY})")
    p.add_argument("--quality", default="2", help="LAME VBR level 0 (best) - 9, or a bitrate like 192K (default 2, ~190 kbps)")
    p.add_argument("--cookies-from-browser", metavar="BROWSER", help="for private playlists, e.g. firefox")
    p.add_argument("--cookies", metavar="FILE", help="Netscape cookies.txt for private playlists")
    p.add_argument("--trim", action="store_true", help="cut non-music intros/outros using SponsorBlock")
    p.add_argument("--pause", type=float, default=2.0, help="seconds between songs (default 2)")
    p.add_argument("--max-new", type=int, metavar="N", help="download at most N new songs per playlist this run")
    p.add_argument("--singles-folder", default="Singles", help="folder for single-video URLs")
    p.add_argument("--port", type=int, default=HTTP_PORT, help=f"sync server port (default {HTTP_PORT})")
    p.add_argument("--no-update", action="store_true", help="skip the daily yt-dlp update check")
    p.add_argument("--no-lyrics", action="store_true", help="don't look up lyrics on LRCLIB")
    p.add_argument("--log", type=Path, metavar="FILE", help="append all output to FILE (used by the nightly task)")
    args = p.parse_args()
    library = args.library.resolve()
    if args.log:
        sys.stdout = sys.stderr = open_log(args.log)
        print(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')}  ytimport {' '.join(sys.argv[1:])}")

    if args.targets == ["serve"]:
        serve(library, args.port)
        return 0

    commands = {"update", "normalize", "lyrics"}
    if len(args.targets) > 1 and commands & set(args.targets):
        print(f"{'/'.join(sorted(commands))} can't be combined with other targets.")
        return 2
    for t in args.targets:
        if t not in commands and not t.startswith("http") and not Path(t).is_file():
            print(f"Not a URL, a tape file or a command: {t}")
            return 2

    if not args.no_update and args.targets not in (["normalize"], ["lyrics"]):
        auto_update()
    prepare_path()
    if not (FFMPEG_DIR / "ffmpeg.exe").exists() and not which("ffmpeg"):
        print("ffmpeg not found - run setup.ps1 (import.bat does this automatically).")
        return 1

    if not args.targets:
        interactive(library, args)
        return 0
    if args.targets == ["update"]:
        return 0 if update_all(library, args) else 1
    if args.targets == ["normalize"]:
        args.no_lyrics = True
        finish_library(library, args)
        return 0
    if args.targets == ["lyrics"]:
        finish_library(library, args, retry_lyrics=True)
        return 0
    ok = True
    for url in args.targets:
        ok = import_playlist(url, library, args) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
