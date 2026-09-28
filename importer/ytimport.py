"""Mixtape importer: YouTube playlists -> tagged MP3s, plus a Wi-Fi sync server for the phone app.

    import.bat <playlist-url> [more urls]   download a playlist (re-run any time: only new songs are fetched)
    import.bat update                       re-check every playlist imported before
    sync.bat                                serve the library to the Mixtape app over Wi-Fi

Each playlist becomes library/<playlist title>/ holding "<title> [<video id>].mp3" files with
title/artist/album/track tags and square cover art embedded. The video id in the file name is how
both this script and the phone app tell which songs they already have.
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
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_LIBRARY = HERE.parent / "library"
FFMPEG_DIR = HERE / "tools" / "ffmpeg"
SOURCES_FILE = "sources.json"      # library-level: folder name -> playlist URL, for `update`
MANIFEST_FILE = ".playlist.json"   # per playlist folder: current order, read by the sync server
HTTP_PORT = 47811
DISCOVERY_PORT = 47810
ID_IN_NAME = re.compile(r"\[([A-Za-z0-9_-]{11})\]\.mp3$", re.IGNORECASE)
UNAVAILABLE_TITLES = {"[Private video]", "[Deleted video]", "[Unavailable video]"}


# --------------------------------------------------------------------------- environment

def in_venv() -> bool:
    return sys.prefix != sys.base_prefix


def auto_update() -> None:
    """YouTube breaks old yt-dlp releases often; upgrade at most once a day."""
    if not in_venv():
        return
    stamp = Path(sys.prefix) / ".last-update"
    if stamp.exists() and time.time() - stamp.stat().st_mtime < 24 * 3600:
        return
    print("Checking for yt-dlp updates...")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-U", "--disable-pip-version-check",
                        "-r", str(HERE / "requirements.txt")])
    if r.returncode == 0:
        stamp.touch()


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
        title, entries, is_playlist = list_playlist(url, args)
    except DownloadError as e:
        print(f"  Could not read the playlist: {e}")
        print("  Private playlists need --cookies-from-browser firefox (see README).")
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

    got = len(todo) - len(failed)
    print(f'Finished "{title}": {got} new, {len(failed)} failed.')
    for name, msg in failed:
        print(f"  x {name}: {msg[:200]}")
    return not failed


_last_line = 0.0


def progress_hook(d: dict) -> None:
    global _last_line
    if d["status"] == "downloading" and time.time() - _last_line > 0.3:
        _last_line = time.time()
        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        pct = f"{100 * d['downloaded_bytes'] / total:5.1f}%" if total else ""
        speed = d.get("speed")
        rate = f"  {speed / 1e6:.1f} MB/s" if speed else ""
        print(f"\r      downloading {pct}{rate}      ", end="", flush=True)


_PP_LABELS = {"ExtractAudio": "converting to mp3", "EmbedThumbnail": "adding cover art", "Metadata": "writing tags"}


def pp_hook(d: dict) -> None:
    label = _PP_LABELS.get(d["postprocessor"])
    if d["status"] == "started" and label and label != pp_hook.last:  # yt-dlp reports some stages twice
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
    try:
        sources = json.loads((library / SOURCES_FILE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        print("Nothing imported yet. Run: import.bat <playlist url>")
        return False
    ok = True
    for url in dict.fromkeys(sources.values()):
        ok = import_playlist(url, library, args) and ok
    return ok


# --------------------------------------------------------------------------- Wi-Fi sync server

def library_listing(library: Path) -> dict:
    playlists = []
    if library.is_dir():
        for folder in sorted((p for p in library.iterdir() if p.is_dir()), key=lambda p: p.name.lower()):
            files = {f.name: f for f in folder.glob("*.mp3")}
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
                tracks.append({
                    "id": m.group(1) if m else name,
                    "file": name,
                    "title": ID_IN_NAME.sub("", name).strip() or name,
                    "size": files[name].stat().st_size,
                })
            playlists.append({"name": folder.name, "tracks": tracks})
    return {"server": socket.gethostname(), "playlists": playlists}


def make_handler(library: Path):
    root = library.resolve()

    class Handler(BaseHTTPRequestHandler):
        server_version = "MixtapeSync/1"

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            if path == "/api/library":
                body = json.dumps(library_listing(root), ensure_ascii=False).encode("utf-8")
                self._send(200, "application/json; charset=utf-8", body)
                print(f"  phone {self.client_address[0]} asked for the song list")
            elif path.startswith("/files/"):
                rel = urllib.parse.unquote(path[len("/files/"):])
                target = (root / rel).resolve()
                if root not in target.parents or target.suffix.lower() != ".mp3" or not target.is_file():
                    self._send(404, "text/plain", b"not found")
                    return
                size = target.stat().st_size
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Content-Length", str(size))
                self.end_headers()
                with target.open("rb") as f:
                    while chunk := f.read(256 * 1024):
                        self.wfile.write(chunk)
                print(f"  -> {target.parent.name} / {ID_IN_NAME.sub('', target.name).strip()}")
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


def serve(library: Path, port: int) -> None:
    library.mkdir(parents=True, exist_ok=True)
    listing = library_listing(library)
    n_songs = sum(len(p["tracks"]) for p in listing["playlists"])
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(library))
    threading.Thread(target=discovery_responder, args=(port,), daemon=True).start()
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
        print("Paste a YouTube playlist URL, or type:  u = update all playlists,  s = start Wi-Fi sync,  q = quit")
        choice = input("> ").strip().strip('"')
        if choice.lower() in ("q", "quit", "exit", ""):
            return
        if choice.lower() in ("u", "update"):
            update_all(library, args)
        elif choice.lower() in ("s", "sync", "serve"):
            serve(library, args.port)
            return
        elif choice.startswith("http"):
            import_playlist(choice, library, args)
        else:
            print("That doesn't look like a URL.")
        print()


def main() -> int:
    for stream in (sys.stdout, sys.stderr):  # song titles with emoji must not crash a redirected console
        stream.reconfigure(errors="replace")
    p = argparse.ArgumentParser(description="Download YouTube playlists as MP3s and sync them to the Mixtape app.")
    p.add_argument("targets", nargs="*", help="playlist URLs, or 'update', or 'serve'")
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
    args = p.parse_args()
    library = args.library.resolve()

    if args.targets == ["serve"]:
        serve(library, args.port)
        return 0

    if not args.no_update:
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
    ok = True
    for url in args.targets:
        ok = import_playlist(url, library, args) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
