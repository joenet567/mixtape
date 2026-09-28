"""Mixtape sync in the system tray: the Wi-Fi sync server without a console window.

install-autosync.ps1 starts this at login. It serves the library to the phone exactly like sync.bat
(the phone finds it with "Find PC", or syncs by itself at night), and its menu can run the playlist
update on demand. Everything it prints goes to logs\\sync.log.
"""
from __future__ import annotations

import os
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOGS = HERE / "logs"
sys.path.insert(0, str(HERE))
import ytimport as yi  # noqa: E402  (after the path tweak)

sys.stdout = sys.stderr = yi.open_log(LOGS / "sync.log")

import pystray  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

# The app's palette (ui/Theme.kt)
INK, CREAM, ORANGE, TAPE = "#14110E", "#F3E9DC", "#FF6B35", "#3F2718"


def cassette_icon(size: int = 64) -> Image.Image:
    """The app's cassette, drawn bold enough to read at 16 px in the tray."""
    s = size / 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((2 * s, 12 * s, 62 * s, 54 * s), radius=6 * s, fill=CREAM, outline=INK, width=round(3 * s))
    d.rectangle((8 * s, 17 * s, 56 * s, 22 * s), fill=ORANGE)                       # label stripe
    d.rounded_rectangle((14 * s, 26 * s, 50 * s, 42 * s), radius=8 * s, fill=INK)   # window
    for cx in (23, 41):                                                             # reels
        d.ellipse(((cx - 6) * s, 28 * s, (cx + 6) * s, 40 * s), fill=TAPE if cx == 23 else INK, outline=CREAM, width=round(2 * s))
        d.ellipse(((cx - 2) * s, 32 * s, (cx + 2) * s, 36 * s), fill=CREAM)
    d.polygon([(18 * s, 54 * s), (22 * s, 47 * s), (42 * s, 47 * s), (46 * s, 54 * s)], fill=INK)  # head opening
    return img


def song_count(library: Path) -> int:
    return sum(1 for _ in library.glob("*/*.mp3")) if library.is_dir() else 0


class Tray:
    def __init__(self) -> None:
        self.library = yi.DEFAULT_LIBRARY.resolve()
        self.port = yi.HTTP_PORT
        self.address = next(iter(yi.lan_addresses()), "?")
        self.last = "No phone has synced yet"
        self.updating = False
        self.icon: pystray.Icon | None = None

    def title(self) -> str:
        return f"Mixtape sync: {song_count(self.library)} songs at {self.address}:{self.port}"

    def on_phone(self, message: str) -> None:
        self.last = f"{time.strftime('%H:%M')}  {message[0].upper()}{message[1:]}"
        if self.icon:
            self.icon.title = self.title()
            self.icon.update_menu()

    def update_now(self) -> None:
        if self.updating:
            return
        self.updating = True
        self.icon.update_menu()

        def work():
            before = song_count(self.library)
            try:
                pythonw = Path(sys.executable).with_name("pythonw.exe")
                yi.run_quiet([str(pythonw if pythonw.exists() else sys.executable), str(HERE / "ytimport.py"),
                              "update", "--log", str(LOGS / "update.log")], cwd=HERE)
            finally:
                self.updating = False
                added = song_count(self.library) - before
                self.icon.title = self.title()
                self.icon.update_menu()
                self.icon.notify(f"{added} new song{'s' if added != 1 else ''} ready for the phone" if added
                                 else "Your playlists are up to date", "Mixtape")

        threading.Thread(target=work, daemon=True).start()

    def quit(self) -> None:
        self.server.shutdown()
        self.icon.stop()

    def run(self) -> None:
        try:
            self.server = yi.start_server(self.library, self.port)
        except OSError:
            print(f"Port {self.port} is busy (sync.bat or another tray copy is already serving); exiting.")
            return
        print(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')}  tray sync server on {self.address}:{self.port}, "
              f"{song_count(self.library)} songs in {self.library}")
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        yi.on_phone_event = self.on_phone

        menu = pystray.Menu(
            pystray.MenuItem(lambda _: self.title(), None, enabled=False),
            pystray.MenuItem(lambda _: self.last, None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda _: "Updating playlists…" if self.updating else "Update playlists now",
                             self.update_now, enabled=lambda _: not self.updating),
            pystray.MenuItem("Open music folder", lambda: os.startfile(self.library)),
            pystray.MenuItem("Open logs", lambda: os.startfile(LOGS)),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", self.quit),
        )
        self.icon = pystray.Icon("mixtape-sync", cassette_icon(), self.title(), menu)
        self.icon.run()


if __name__ == "__main__":
    Tray().run()
