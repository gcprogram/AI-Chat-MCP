"""Avatar-Bilder der Companions: Kopieren ins Persona-Verzeichnis, Laden fürs Tk-Fenster, Platzhalter.

Mit Pillow (optional) gehen alle gängigen Formate und das Bild wird quadratisch zugeschnitten;
ohne Pillow kann Tk nur PNG und GIF."""
from __future__ import annotations

import base64
import io
import shutil
import tkinter as tk
from pathlib import Path

from .persona import AVATAR_SUBDIR, Persona

try:
    from PIL import Image, ImageOps, ImageTk  # type: ignore[import-not-found]
except ImportError:  # Pillow ist optional
    Image = None

AVATAR_SIZE = 64
FILE_TYPES = [("Bilder", "*.png *.gif *.jpg *.jpeg *.bmp *.webp"), ("Alle Dateien", "*.*")]


class AvatarError(Exception):
    pass


def store_avatar(source: Path | str, persona_id: str, personas_dir: Path) -> str:
    """Kopiert das Bild nach personas/avatars/<id>.<ext> (der Companion bleibt dadurch in sich geschlossen)
    und liefert den relativen Pfad für die Persona-Datei."""
    source = Path(source)
    if not source.is_file():
        raise AvatarError(f"Bild nicht gefunden: {source}")
    target_dir = personas_dir / AVATAR_SUBDIR
    target_dir.mkdir(parents=True, exist_ok=True)
    for old in target_dir.glob(f"{persona_id}.*"):  # altes Bild mit anderer Endung ersetzen
        if old.resolve() != source.resolve():
            old.unlink(missing_ok=True)
    target = target_dir / f"{persona_id}{source.suffix.lower()}"
    if source.resolve() != target.resolve():
        shutil.copyfile(source, target)
    return f"{AVATAR_SUBDIR}/{target.name}"


# Das eingebettete Bild dient der kleinen Anzeige (64 Pixel) und der Vollbildansicht. Es wird so groß wie möglich gespeichert, aber höchstens
# BUDGET_KB als Base64 in der Companion-Datei: erst die größte Kantenlänge mit gutem JPEG, dann kleiner, dann schlechtere Qualität.
SIZES = (768, 704, 640, 576, 512, 448, 384, 320, 256)
QUALITIES = (75, 70, 65, 60, 55)
BUDGET_KB = 100


def encode_avatar(source: Path | str, budget_kb: int = BUDGET_KB) -> str:
    """Bild für die Companion-Datei: quadratisch zugeschnitten, JPEG in Base64 mit höchstens budget_kb (Standard 100 KB, also 512 bis 768 Pixel
    je nach Bild). Kleine Bilder werden nicht hochgerechnet. Braucht Pillow (steht in requirements.txt)."""
    source = Path(source)
    if not source.is_file():
        raise AvatarError(f"Bild nicht gefunden: {source}")
    if Image is None:
        raise AvatarError("Zum Einbetten des Bildes wird Pillow gebraucht: pip install pillow")
    try:
        with Image.open(source) as img:
            rgba = img.convert("RGBA")
    except (OSError, ValueError) as exc:
        raise AvatarError(f"Bild nicht lesbar: {exc}") from exc
    side = min(rgba.size)
    background = Image.new("RGB", rgba.size, (255, 255, 255))  # Transparenz auf Weiß, JPEG kennt sie nicht
    background.paste(rgba, mask=rgba.split()[3])
    best = b""
    for size in [s for s in SIZES if s <= side] or [side]:
        fitted = ImageOps.fit(background, (size, size), Image.LANCZOS)
        for quality in QUALITIES:
            buffer = io.BytesIO()
            fitted.save(buffer, "JPEG", quality=quality, optimize=True, progressive=True)
            best = buffer.getvalue()
            if len(base64.b64encode(best)) <= budget_kb * 1024:
                return base64.b64encode(best).decode("ascii")
    return base64.b64encode(best).decode("ascii")  # selbst die kleinste Stufe sprengt das Budget: trotzdem die kleinste nehmen


def load_photo(path: Path, size: int = AVATAR_SIZE) -> tk.PhotoImage:
    """Bild als quadratisches PhotoImage der Kantenlänge 'size'. Ein Tk-Fenster muss existieren."""
    try:
        if Image is not None:
            with Image.open(path) as img:
                return ImageTk.PhotoImage(ImageOps.fit(img.convert("RGBA"), (size, size), Image.LANCZOS))
        photo = tk.PhotoImage(file=str(path))
        factor = max(1, min(photo.width(), photo.height()) // size)  # Tk kann nur ganzzahlig verkleinern
        return photo.subsample(factor) if factor > 1 else photo
    except (OSError, tk.TclError, ValueError) as exc:
        hint = "" if Image is not None else " (ohne Pillow nur PNG/GIF: pip install pillow)"
        raise AvatarError(f"Bild nicht ladbar{hint}: {exc}") from exc


class AvatarView(tk.Canvas):
    """Quadratische Avatar-Anzeige; ohne Bild ein farbiger Kreis mit dem Anfangsbuchstaben."""

    def __init__(self, master, size: int = AVATAR_SIZE):
        super().__init__(master, width=size, height=size, highlightthickness=1, highlightbackground="#b0b0b0")
        self.size = size
        self._photo: tk.PhotoImage | None = None  # Referenz halten, sonst verschwindet das Bild
        self._path: Path | None = None  # Datei des angezeigten Bildes (für die Vollbildansicht)
        self._name = ""
        self._full: tk.Toplevel | None = None
        self.bind("<Button-1>", lambda _e: self.show_full())

    def show(self, persona: Persona, directory: Path) -> None:
        self.show_path(persona.avatar_path(directory), persona.name)

    def show_full(self) -> bool:
        """Klick auf das Bild: größere Ansicht in einem eigenen Fenster (Klick oder Esc schließt). False, wenn es kein Bild gibt."""
        if self._path is None:
            return False
        if self._full is not None and self._full.winfo_exists():
            self._full.destroy()
        side = max(200, min(768, self.winfo_screenheight() - 160))
        try:
            photo = load_photo(self._path, side)
        except AvatarError:
            return False
        window = tk.Toplevel(self)
        window.title(self._name or "Avatar")
        window.resizable(False, False)
        label = tk.Label(window, image=photo, borderwidth=0)
        label.image = photo  # Referenz halten
        label.pack()
        for widget in (window, label):
            widget.bind("<Button-1>", lambda _e: window.destroy())
        window.bind("<Escape>", lambda _e: window.destroy())
        window.focus_set()
        self._full = window
        return True

    def show_path(self, path: Path | None, name: str) -> None:
        self.delete("all")
        self._photo = None
        self._path, self._name = path, name
        if path is not None:
            try:
                self._photo = load_photo(path, self.size)
                self.create_image(self.size // 2, self.size // 2, image=self._photo)  # Rand wird abgeschnitten
                return
            except AvatarError:
                pass  # kaputtes Bild: Platzhalter zeigen
        color = "#%02x%02x%02x" % tuple(90 + (sum(map(ord, name)) * m) % 110 for m in (7, 13, 29))
        pad = 4
        self.create_oval(pad, pad, self.size - pad, self.size - pad, fill=color, outline="")
        self.create_text(self.size // 2, self.size // 2, text=(name.strip()[:1] or "?").upper(),
                         fill="white", font=("Arial", self.size // 2, "bold"))
