"""Fenster zum Anlegen, Bearbeiten und Löschen von Companions (personas/*.json) inkl. Avatar und Werkzeug-Auswahl."""
from __future__ import annotations

import re
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from .avatar import FILE_TYPES, AvatarError, AvatarView, encode_avatar, load_photo
from .persona import (Persona, delete_persona, list_personas, load_persona, remove_avatar_file, save_persona,
                      unique_id)


# Antwortsprachen zur Auswahl (wie in der Android-App)
LANGUAGES = ["Deutsch", "English", "English (UK)", "Français", "Español", "Italiano", "Japanese"]
MODE_LABELS = {"marker": "Textmarker (für kleine Modelle)", "native": "Nativ (tools-Parameter der API)"}


class PersonaEditor(tk.Toplevel):
    def __init__(self, app, select: str | None = None):
        super().__init__(app.root)
        self.app = app
        self.title("Companions")
        self.columnconfigure(1, weight=1)
        self.current_id = ""  # leer = neuer, noch nicht gespeicherter Companion
        self.avatar_new: Path | None = None  # frisch gewähltes Bild (wird beim Speichern kopiert)
        self.avatar_saved = ""  # bereits gespeicherter relativer Pfad (älteres Format)
        self.avatar_data_saved = ""  # bereits eingebettetes Bild (Base64)
        self.hidden_tools: list[str] = []  # freigeschaltet, aber gerade nicht verfügbar - nicht verlieren
        self.tool_vars: dict[str, tk.BooleanVar] = {}
        self.approve_vars: dict[str, tk.BooleanVar] = {}  # vorab genehmigt (ohne Rückfrage)
        self.hidden_approved: list[str] = []
        self.choice_vars: dict[str, tk.StringVar] = {}  # Tastensatz-Werkzeug -> erlaubte Tasten (kommagetrennt, leer = alle)
        self.hidden_choices: dict[str, list[str]] = {}

        top = ttk.Frame(self)
        top.grid(row=0, column=0, columnspan=2, sticky="we", padx=10, pady=8)
        ttk.Label(top, text="Companion:").pack(side=tk.LEFT)
        self.combo = ttk.Combobox(top, state="readonly", width=24)
        self.combo.pack(side=tk.LEFT, padx=5)
        self.combo.bind("<<ComboboxSelected>>", lambda _e: self._load(self.combo.get()))
        ttk.Button(top, text="Neu", command=self.new).pack(side=tk.LEFT, padx=5)
        self.delete_btn = ttk.Button(top, text="Löschen", command=self.delete)
        self.delete_btn.pack(side=tk.LEFT)

        self.name = self._entry("Name:", 1)
        self.description = self._text("Beschreibung:", 2, 5)
        ttk.Label(self, text="Sprache:").grid(row=3, column=0, sticky="nw", padx=10, pady=5)
        self.language = ttk.Combobox(self, state="readonly", width=24, values=LANGUAGES)
        self.language.grid(row=3, column=1, sticky="w", padx=10, pady=5)
        self.greeting = self._text("Begrüßung:", 4, 3)
        self.rules = self._text("Regeln (eine pro Zeile):", 5, 4)

        ttk.Label(self, text="Avatar:").grid(row=6, column=0, sticky="nw", padx=10, pady=5)
        avatar = ttk.Frame(self)
        avatar.grid(row=6, column=1, sticky="w", padx=10, pady=5)
        self.avatar_view = AvatarView(avatar)
        self.avatar_view.pack(side=tk.LEFT)
        ttk.Button(avatar, text="Bild wählen…", command=self.choose_avatar).pack(side=tk.LEFT, padx=8)
        ttk.Button(avatar, text="Entfernen", command=self.clear_avatar).pack(side=tk.LEFT)

        ttk.Label(self, text="Werkzeuge:").grid(row=7, column=0, sticky="nw", padx=10, pady=5)
        self.tools_frame = ttk.Frame(self)
        self.tools_frame.grid(row=7, column=1, sticky="w", padx=10, pady=5)

        ttk.Label(self, text="Werkzeug-Modus:").grid(row=8, column=0, sticky="nw", padx=10, pady=5)
        mode = ttk.Frame(self)
        mode.grid(row=8, column=1, sticky="w", padx=10, pady=5)
        self.mode = ttk.Combobox(mode, state="readonly", width=34, values=list(MODE_LABELS.values()))
        self.mode.pack(side=tk.LEFT)
        ttk.Label(mode, foreground="#666666", text="  Nativ nur für Modelle mit Tool-Unterstützung.").pack(side=tk.LEFT)

        buttons = ttk.Frame(self)
        buttons.grid(row=9, column=0, columnspan=2, pady=12)
        ttk.Button(buttons, text="Speichern", command=self.save).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Schließen", command=self.destroy).pack(side=tk.LEFT, padx=5)

        self._refresh_list()
        ids = list_personas(self.app.personas_dir)
        if select in ids:
            self._load(select)
        elif ids:
            self._load(ids[0])
        else:
            self.new()

    # --- Aufbau ----------------------------------------------------------------------------------

    def _entry(self, label: str, row: int) -> ttk.Entry:
        ttk.Label(self, text=label).grid(row=row, column=0, sticky="nw", padx=10, pady=5)
        entry = ttk.Entry(self, width=56)
        entry.grid(row=row, column=1, sticky="we", padx=10, pady=5)
        return entry

    def _text(self, label: str, row: int, height: int) -> tk.Text:
        ttk.Label(self, text=label).grid(row=row, column=0, sticky="nw", padx=10, pady=5)
        text = tk.Text(self, width=56, height=height, wrap=tk.WORD)
        text.grid(row=row, column=1, sticky="we", padx=10, pady=5)
        return text

    def _refresh_list(self, select: str | None = None) -> None:
        ids = list_personas(self.app.personas_dir)
        self.combo.configure(values=ids)
        self.combo.set(select if select in ids else "")
        self.delete_btn.configure(state=tk.NORMAL if len(ids) > 1 else tk.DISABLED)  # einer muss übrig bleiben

    def _build_tool_checks(self, enabled: list[str], approved: list[str], choices: dict[str, list[str]] | None = None) -> None:
        for child in self.tools_frame.winfo_children():
            child.destroy()
        choices = choices or {}
        running = set(self.app.registry.available())
        available = self.app.registry.selectable()  # auch Werkzeuge, deren Server gerade nicht läuft: der Companion lässt sich immer einrichten
        self.tool_vars = {a: tk.BooleanVar(value=a in enabled) for a in available}
        self.approve_vars = {a: tk.BooleanVar(value=a in approved) for a in available}
        self.hidden_tools = [t for t in enabled if t not in available]
        self.hidden_approved = [t for t in approved if t not in available]
        self.choice_vars = {}
        if available:
            ttk.Label(self.tools_frame, foreground="#666666",
                      text="Freischalten  ·  ohne Rückfrage (vorab genehmigt)").grid(row=0, column=0, columnspan=2,
                                                                                       sticky="w")
        row = 0
        for alias in available:
            row += 1
            spec = self.app.registry.tools.get(alias)
            text = f"{alias} – {spec.description}" if spec else f"{alias} – (Server derzeit nicht erreichbar)"
            ttk.Checkbutton(self.tools_frame, text=text, variable=self.tool_vars[alias]).grid(row=row, column=0, sticky="w")
            ttk.Checkbutton(self.tools_frame, text="ohne Rückfrage", variable=self.approve_vars[alias]
                            ).grid(row=row, column=1, sticky="w", padx=(12, 0))
            if spec is not None and spec.buttons and alias in running:  # Tastensatz: nur ausgewählte Tasten sind für den Companion sichtbar
                row += 1
                self.choice_vars[alias] = tk.StringVar(value=", ".join(choices.get(alias, [])))
                line = ttk.Frame(self.tools_frame)
                line.grid(row=row, column=0, columnspan=2, sticky="we", padx=(24, 0))
                ttk.Label(line, text="Erlaubte Tasten (leer = alle):", foreground="#666666").pack(side=tk.LEFT)
                ttk.Entry(line, textvariable=self.choice_vars[alias], width=40).pack(side=tk.LEFT, padx=6)
                ttk.Label(line, text=", ".join(spec.buttons), foreground="#999999").pack(side=tk.LEFT)
        self.hidden_choices = {a: v for a, v in choices.items() if a not in self.choice_vars}
        if not available:
            ttk.Label(self.tools_frame, foreground="#666666",
                      text="Keine Werkzeuge verfügbar (mcp_servers.json anlegen, siehe README).").grid(row=0, column=0)

    # --- Formular ----------------------------------------------------------------------------------

    def _fill(self, persona: Persona) -> None:
        self.current_id, self.avatar_new, self.avatar_saved = persona.id, None, persona.avatar
        self.avatar_data_saved = persona.avatar_data
        self.name.delete(0, tk.END)
        self.name.insert(0, persona.name)
        # ein bereits gespeicherter freier Wert (z. B. "Plattdeutsch") bleibt auswählbar
        self.language.configure(values=LANGUAGES + ([persona.language] if persona.language not in LANGUAGES else []))
        self.language.set(persona.language)
        for widget, value in ((self.description, persona.description), (self.greeting, persona.greeting),
                              (self.rules, "\n".join(persona.rules))):
            widget.delete("1.0", tk.END)
            widget.insert("1.0", value)
        self._build_tool_checks(persona.tools, persona.approved_tools, persona.tool_choices)
        self.mode.set(MODE_LABELS.get(persona.tool_mode, MODE_LABELS["marker"]))
        self.avatar_view.show(persona, self.app.personas_dir)

    def _collect(self) -> Persona:
        return Persona(
            name=self.name.get().strip(),
            description=self.description.get("1.0", tk.END).strip(),
            language=self.language.get().strip() or "Deutsch",
            greeting=self.greeting.get("1.0", tk.END).strip(),
            rules=[r.strip() for r in self.rules.get("1.0", tk.END).splitlines() if r.strip()],
            avatar=self.avatar_saved, avatar_data=self.avatar_data_saved,
            tools=[a for a, var in self.tool_vars.items() if var.get()] + self.hidden_tools,
            approved_tools=[a for a, var in self.approve_vars.items() if var.get() and self.tool_vars[a].get()]
            + self.hidden_approved,
            tool_choices=self._collect_choices(),
            tool_mode=next((k for k, v in MODE_LABELS.items() if v == self.mode.get()), "marker"),
            id=self.current_id)

    def _collect_choices(self) -> dict[str, list[str]]:
        """Erlaubte Tasten je freigeschaltetem Tastensatz; alle Tasten (oder keine Angabe) wird nicht gespeichert."""
        result = dict(self.hidden_choices)
        for alias, var in self.choice_vars.items():
            spec = self.app.registry.tools.get(alias)
            if spec is None or not self.tool_vars[alias].get():
                continue
            wanted = [w.strip() for w in re.split(r"[,;]", var.get()) if w.strip()]
            valid = [b for b in spec.buttons if b.lower() in {w.lower() for w in wanted}]
            if valid and len(valid) < len(spec.buttons):
                result[alias] = valid
        return result

    def _load(self, persona_id: str) -> None:
        self._fill(load_persona(persona_id, self.app.personas_dir))
        self.combo.set(persona_id)

    def new(self) -> None:
        self._fill(Persona(name="", description="Du bist eine freundliche, neugierige Gesprächspartnerin.",
                           greeting="Hallo! Schön, dass du da bist."))
        self.combo.set("")
        self.name.focus_set()

    # --- Aktionen ----------------------------------------------------------------------------------

    def choose_avatar(self) -> None:
        path = filedialog.askopenfilename(parent=self, title="Avatar-Bild wählen", filetypes=FILE_TYPES)
        if path:
            self.set_avatar(Path(path))

    def set_avatar(self, path: Path) -> bool:
        try:
            load_photo(path)  # früh prüfen, ob Tk/Pillow das Bild lesen kann
        except AvatarError as exc:
            messagebox.showerror("Avatar", str(exc), parent=self)
            return False
        self.avatar_new = path
        self.avatar_view.show_path(path, self.name.get())
        return True

    def clear_avatar(self) -> None:
        self.avatar_new, self.avatar_saved, self.avatar_data_saved = None, "", ""
        self.avatar_view.show_path(None, self.name.get())

    def save(self) -> str | None:
        persona = self._collect()
        if not persona.name:
            messagebox.showwarning("Name fehlt", "Bitte dem Companion einen Namen geben.", parent=self)
            return None
        directory = self.app.personas_dir
        persona.id = persona.id or unique_id(persona.name, directory)
        try:
            old = load_persona(persona.id, directory) if self.current_id else None
            if self.avatar_new is not None:  # neues Bild: es kommt in die Companion-Datei (ersetzt das bisherige)
                persona.avatar_data = encode_avatar(self.avatar_new)
                persona.avatar = ""
                if old is not None and old.avatar:
                    remove_avatar_file(old, directory)  # eine alte Bilddatei in avatars/ wird nicht mehr gebraucht
            elif old is not None and old.avatar and not persona.avatar:
                remove_avatar_file(old, directory)  # Avatar wurde entfernt
            save_persona(persona, directory)
        except (OSError, AvatarError) as exc:
            messagebox.showerror("Speichern fehlgeschlagen", str(exc), parent=self)
            return None
        self.current_id, self.avatar_new, self.avatar_saved = persona.id, None, persona.avatar
        self.avatar_data_saved = persona.avatar_data
        self._refresh_list(persona.id)
        self.app.on_personas_changed(saved=persona.id)
        return persona.id

    def delete(self) -> None:
        if not self.current_id:
            return
        if not messagebox.askyesno("Companion löschen", f"'{self.name.get()}' endgültig löschen?", parent=self):
            return
        deleted = self.current_id
        delete_persona(deleted, self.app.personas_dir)
        self._refresh_list()
        self.app.on_personas_changed(deleted=deleted)
        remaining = list_personas(self.app.personas_dir)
        self._load(remaining[0]) if remaining else self.new()
