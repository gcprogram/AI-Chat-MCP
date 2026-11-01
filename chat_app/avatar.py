"""Avatar-Bilder der Companions: Kopieren ins Persona-Verzeichnis, Laden fürs Tk-Fenster, Platzhalter.

Mit Pillow (optional) gehen alle gängigen Formate und das Bild wird quadratisch zugeschnitten;
ohne Pillow kann Tk nur PNG und GIF."""
from __future__ import annotations

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

    def show(self, persona: Persona, directory: Path) -> None:
        self.show_path(persona.avatar_path(directory), persona.name)

    def show_path(self, path: Path | None, name: str) -> None:
        self.delete("all")
        self._photo = None
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
