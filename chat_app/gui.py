"""Tkinter-Oberfläche. Streaming-Thread und Werkzeug-Thread sprechen mit der GUI ausschließlich über eine Queue.

Ablauf einer Antwort ("Runde"): Stream -> Bereinigung -> falls die Antwort einen Werkzeugaufruf enthält:
Werkzeug ausführen -> Ergebnis als Nachricht zurück -> nächste Runde (höchstens Config.max_tool_calls)."""
from __future__ import annotations

import dataclasses
import logging
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk

from . import chatlog
from .avatar import AvatarView
from .config import CHATS_DIR, CONFIG_PATH, MCP_CONFIG_PATH, Config
from .context import TOOL_RESULT, TOOL_SHOWN, Message
from .llm_client import ChatClient, LLMError, StreamEvent
from .persona import PERSONAS_DIR, list_personas, load_persona
from .persona_editor import PersonaEditor
from .reply import Reply, StreamGuard, finalize_reply
from .session import ChatSession
from .tools import (RESULT_BOTH, RESULT_DISCARD, RESULT_SHOW, ToolCall, ToolOutcome, ToolRegistry, find_call, normalize_name,
                    split_segments)

log = logging.getLogger(__name__)
POLL_MS = 50
RESULT_PREVIEW_CHARS = 300


class ChatApp:
    def __init__(self, root: tk.Tk, *, config: Config | None = None, config_path: Path = CONFIG_PATH,
                 personas_dir: Path = PERSONAS_DIR, chats_dir: Path = CHATS_DIR,
                 registry: ToolRegistry | None = None):
        self.root = root
        self.config_path, self.personas_dir, self.chats_dir = config_path, personas_dir, chats_dir
        self.config = config or Config.load(config_path)
        self.registry = registry if registry is not None else ToolRegistry()
        self.session = ChatSession(self.config, load_persona(self.config.persona, personas_dir), self.registry)
        self.events: queue.Queue = queue.Queue()
        self.cancel = threading.Event()
        self.streaming = False
        self.block: str | None = None  # gerade ausgegebener Block: "assistant" | "reasoning"
        self.last_usage: dict | None = None
        self.sent_messages: list[dict] = []
        self.tool_rounds = 0
        self.native_args: dict[tuple[str, str], dict] = {}  # echte Argumente nativer Aufrufe (Schlüssel: Name, Marker-Text)
        self.settings_win: tk.Toplevel | None = None
        self.editor: PersonaEditor | None = None
        self.model_combo: ttk.Combobox | None = None

        root.title("AI-Chat")
        root.geometry("820x700")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self._build_toolbar()
        self._build_transcript()
        self._build_input()
        self._build_statusbar()
        self._render_history()
        self._poll_job = root.after(POLL_MS, self._poll)
        root.bind("<Destroy>", lambda e: root.after_cancel(self._poll_job) if e.widget is root else None)
        if registry is None and MCP_CONFIG_PATH.exists():  # MCP-Server im Hintergrund starten
            self.status.set("Werkzeuge werden gestartet…")
            threading.Thread(target=self._load_tools, daemon=True).start()

    # --- Aufbau ------------------------------------------------------------------------------

    def _build_toolbar(self) -> None:
        bar = ttk.Frame(self.root)
        bar.grid(row=0, column=0, sticky="we", padx=10, pady=8)
        bar.columnconfigure(0, weight=1)
        left = ttk.Frame(bar)
        left.grid(row=0, column=0, sticky="w")

        line1 = ttk.Frame(left)
        line1.pack(anchor="w")
        ttk.Label(line1, text="Companion:").pack(side=tk.LEFT)
        self.persona_combo = ttk.Combobox(line1, values=list_personas(self.personas_dir), state="readonly", width=16)
        self.persona_combo.set(self.session.persona.id)
        self.persona_combo.pack(side=tk.LEFT, padx=5)
        self.persona_combo.bind("<<ComboboxSelected>>", lambda _e: self._persona_changed())
        self.edit_btn = ttk.Button(line1, text="Companions…", command=self.open_persona_editor)
        self.edit_btn.pack(side=tk.LEFT, padx=5)
        self.new_btn = ttk.Button(line1, text="Neuer Chat", command=self.new_chat)
        self.new_btn.pack(side=tk.LEFT, padx=5)

        line2 = ttk.Frame(left)
        line2.pack(anchor="w", pady=(6, 0))
        self.load_btn = ttk.Button(line2, text="Chat laden…", command=self.load_chat)
        self.load_btn.pack(side=tk.LEFT)
        ttk.Button(line2, text="Chat speichern…", command=self.save_chat).pack(side=tk.LEFT, padx=5)
        ttk.Button(line2, text="Einstellungen", command=self.open_settings).pack(side=tk.LEFT, padx=5)

        self.avatar = AvatarView(bar)  # Avatar des aktiven Companions, obere rechte Ecke
        self.avatar.grid(row=0, column=1, sticky="ne", padx=(10, 0))
        self.avatar.show(self.session.persona, self.personas_dir)

    def _build_transcript(self) -> None:
        self.chat = scrolledtext.ScrolledText(self.root, wrap=tk.WORD, state=tk.DISABLED)
        self.chat.grid(row=1, column=0, sticky="nsew", padx=10)
        self.chat.tag_configure("name", font=("Arial", 10, "bold"))
        self.chat.tag_configure("user", background="#e8f0fe")
        self.chat.tag_configure("assistant", background="#f1f1f1")
        self.chat.tag_configure("reasoning", background="#fafafa", foreground="#888888")
        self.chat.tag_configure("system", foreground="#1a56c4", font=("Arial", 10, "italic"))
        self.chat.tag_configure("tool", foreground="#0b6b3a", font=("Arial", 10, "italic"))
        self.chat.tag_configure("toolresult", foreground="#555555", font=("Arial", 9))
        self.chat.tag_configure("toolshown", foreground="#222222", font=("Consolas", 10), lmargin1=12, lmargin2=12)
        self.chat.tag_configure("error", foreground="#c0392b")

    def _build_input(self) -> None:
        frame = ttk.Frame(self.root)
        frame.grid(row=2, column=0, sticky="we", padx=10, pady=8)
        frame.columnconfigure(0, weight=1)
        self.input = tk.Text(frame, height=3, wrap=tk.WORD)
        self.input.grid(row=0, column=0, rowspan=2, sticky="we")
        self.input.bind("<Return>", self._on_return)
        self.input.bind("<Shift-Return>", lambda _e: (self.input.insert(tk.INSERT, "\n"), "break")[1])
        self.send_btn = ttk.Button(frame, text="Senden", command=self.send)
        self.send_btn.grid(row=0, column=1, padx=(8, 0), sticky="we")
        self.stop_btn = ttk.Button(frame, text="Stopp", command=self.cancel.set, state=tk.DISABLED)
        self.stop_btn.grid(row=1, column=1, padx=(8, 0), sticky="we")
        self.input.focus_set()

    def _build_statusbar(self) -> None:
        frame = ttk.Frame(self.root, relief=tk.SUNKEN)
        frame.grid(row=3, column=0, sticky="we", padx=10, pady=(0, 8))
        frame.columnconfigure(0, weight=1)
        self.status = tk.StringVar(value="Bereit")
        self.context_label = tk.StringVar()
        ttk.Label(frame, textvariable=self.status).grid(row=0, column=0, sticky="w", padx=5)
        ttk.Label(frame, textvariable=self.context_label).grid(row=0, column=1, sticky="e", padx=5)

    # --- Transkript --------------------------------------------------------------------------

    def _append(self, text: str, *tags: str) -> None:
        self.chat.configure(state=tk.NORMAL)
        self.chat.insert(tk.END, text, tags)
        self.chat.configure(state=tk.DISABLED)
        self.chat.see(tk.END)

    def _insert_assistant(self, text: str) -> None:
        """Antworttext; Werkzeugaufruf-Marker erscheinen als eigene, farbige Zeile."""
        for kind, part in split_segments(text):
            if kind == "call":
                self._append(f"» Werkzeug {part}\n", "tool")
            else:
                self._append(part, "assistant")

    def _show_message(self, message: Message) -> None:
        persona = self.session.persona
        if message.kind == TOOL_SHOWN:  # Klartext im Chat, das Modell antwortet nicht darauf
            self._append(f"{message.content}\n\n", "toolshown")
        elif message.is_tool:
            preview = " ".join(message.content.split())
            if len(preview) > RESULT_PREVIEW_CHARS:
                preview = preview[:RESULT_PREVIEW_CHARS].rstrip() + "…"
            label = "Ergebnis" if message.kind == TOOL_RESULT else "Fehler"
            self._append(f"« {label} von {message.tool or 'Werkzeug'}: {preview}\n\n", "toolresult")
        elif message.role == "user":
            self._append(f"{self.config.user_name}: ", "name", "user")
            self._append(f"{message.content}\n\n", "user")
        else:
            self._append(f"{persona.name}: ", "name", "assistant")
            self._insert_assistant(message.content)
            self._append("\n\n")

    def _render_history(self) -> None:
        """Zeichnet Begrüßung und Verlauf neu (neuer Chat, geladener Chat)."""
        self.chat.configure(state=tk.NORMAL)
        self.chat.delete("1.0", tk.END)
        self.chat.configure(state=tk.DISABLED)
        self.block = None
        persona = self.session.persona
        self._append(f"{persona.name}: ", "name", "assistant")
        if persona.greeting:
            self._append(f"{persona.greeting}\n\n", "assistant")
        for message in self.session.history:
            self._show_message(message)
        self._update_context_label()

    def _update_context_label(self) -> None:
        text = f"Kontext ≈ {self.session.estimated_tokens()}/{self.config.context_tokens} Tokens"
        if self.session.dropped:
            text += f" · {self.session.dropped} ältere Nachrichten ausgeblendet"
        self.context_label.set(text)

    # --- Chat-Aktionen -----------------------------------------------------------------------

    def _on_return(self, _event) -> str:
        self.send()
        return "break"

    def send(self) -> None:
        text = self.input.get("1.0", tk.END).strip()
        if self.streaming or not text:
            return
        if not self.config.model:
            messagebox.showwarning("Kein Modell", "Bitte zuerst in den Einstellungen ein Modell wählen.")
            return
        self.input.delete("1.0", tk.END)
        self.session.add_user(text)
        self._show_message(self.session.history[-1])
        self.tool_rounds = 0
        self.native_args.clear()
        self.cancel.clear()
        self._set_streaming(True)
        self._start_round()

    def _start_round(self) -> None:
        """Eine Anfrage an das Modell mit dem aktuellen Verlauf (nach der Nutzernachricht oder einem Werkzeugergebnis)."""
        self.sent_messages = self.session.request_messages()
        self.block, self.last_usage = None, None
        self._update_context_label()
        self.status.set("Warte auf Modell…")
        threading.Thread(target=self._stream, daemon=True, args=(
            dataclasses.replace(self.config), self.sent_messages, self.session.stop_sequences,
            self.session.previous_replies(), self.session.tool_names, self.session.persona.name,
            self.session.native_tools,
        )).start()

    def _set_streaming(self, streaming: bool) -> None:
        self.streaming = streaming
        state = tk.DISABLED if streaming else tk.NORMAL
        for button in (self.send_btn, self.new_btn, self.load_btn, self.edit_btn):
            button.configure(state=state)
        self.persona_combo.configure(state=tk.DISABLED if streaming else "readonly")
        self.stop_btn.configure(state=tk.NORMAL if streaming else tk.DISABLED)

    def _stream(self, config: Config, messages: list[dict], stop: list[str], previous: list[str],
                tool_names: list[str], persona_name: str, native_tools: list[dict]) -> None:
        reply: list[str] = []
        guard = StreamGuard(config.user_name, persona_name, tool_names)
        try:
            client = ChatClient(config.base_url, config.api_key)
            stream = client.stream_chat(config.model, messages, temperature=config.temperature,
                                        max_tokens=config.max_reply_tokens, stop=stop, cancel=self.cancel,
                                        extra=config.sampling_params(), tools=native_tools or None)
            try:
                for event in stream:
                    if event.kind == "tool_call":  # nativer Aufruf: ab hier wie ein Marker behandeln
                        marker, arguments = self.registry.native_call(event.text, event.arguments)
                        self.native_args[self._call_key(marker)] = arguments
                        event = StreamEvent("content", (" " if reply else "") + marker)
                    if event.kind == "content":
                        reply.append(event.text)
                    self.events.put((event.kind, event))
                    if event.kind == "content" and guard.feed(event.text):
                        break  # Schleife/Markup/fertiger Werkzeugaufruf: Rest wäre Müll oder erfundenes Ergebnis
            finally:
                stream.close()
            self.events.put(("done", finalize_reply(
                "".join(reply), user_name=config.user_name, persona_name=persona_name, previous=previous,
                tool_names=tool_names)))
        except LLMError as exc:
            self.events.put(("error", str(exc)))
        except Exception as exc:  # Programmfehler nicht verschlucken, aber GUI entsperren
            log.exception("Unerwarteter Fehler im Streaming")
            self.events.put(("error", f"Unerwarteter Fehler: {exc}"))

    def _poll(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                getattr(self, f"_on_{kind}")(payload)
        except queue.Empty:
            pass
        self._poll_job = self.root.after(POLL_MS, self._poll)

    def _switch_block(self, block: str) -> None:
        if block == self.block:
            return
        if self.block is not None:
            self._append("\n\n")
        if block == "reasoning":
            self._append("Denkt nach…\n", "system")
        else:
            self._append(f"{self.session.persona.name}: ", "name", "assistant")
            self.chat.mark_set("blockstart", "end-1c")  # ab hier wird die Antwort am Ende bereinigt neu gezeichnet
            self.chat.mark_gravity("blockstart", tk.LEFT)
        self.block = block
        self.status.set("Modell schreibt…")

    def _on_content(self, event) -> None:
        self._switch_block("assistant")
        self._append(event.text, "assistant")

    def _on_reasoning(self, event) -> None:
        self._switch_block("reasoning")
        self._append(event.text, "reasoning")

    def _on_usage(self, event) -> None:
        self.last_usage = event.usage

    def _render_reply(self, text: str) -> None:
        """Ersetzt den gestreamten Roh-Text durch die bereinigte Fassung (Wiederholungen, Markup, Aufruf-Zeile)."""
        if self.block == "assistant":
            self.chat.configure(state=tk.NORMAL)
            self.chat.delete("blockstart", tk.END)
            self.chat.configure(state=tk.DISABLED)
            self._insert_assistant(text)
        if self.block is not None:
            self._append("\n\n")

    def _on_done(self, result: Reply) -> None:
        cancelled = self.cancel.is_set()
        text = result.text.strip()
        self._render_reply(text)
        if not text:
            self._set_streaming(False)
            self._restore_failed_input()
            self._append("[Keine Antwort erhalten]\n\n", "system")
            self.status.set("Abgebrochen" if cancelled else "Leere Antwort")
            self.block = None
            self._update_context_label()
            return
        self.session.add_assistant(text)  # nur der bereinigte Antworttext, nie das Reasoning
        if result.call is None:
            self._warn_disabled_tool(text)
        if self.last_usage:
            self.session.record_usage(self.sent_messages, self.last_usage.get("prompt_tokens", 0))
        note = f" ({'; '.join(result.notes)})" if result.notes else ""
        if result.call and not cancelled:
            self._handle_call(result.call, note)
        else:
            self._finish(("Abgebrochen (Teilantwort gespeichert)" if cancelled else "Bereit") + note)

    def _finish(self, status: str) -> None:
        self._set_streaming(False)
        self.block = None
        self.status.set(status)
        self._update_context_label()

    def _on_error(self, message: str) -> None:
        self._set_streaming(False)
        self._restore_failed_input()
        self.block = None
        self._append(f"[Fehler] {message}\n\n", "error")
        self.status.set("Fehler")
        self._update_context_label()

    def _restore_failed_input(self) -> None:
        """Die Frage ohne Antwort aus dem Verlauf nehmen und wieder ins Eingabefeld legen
        (ein Werkzeugergebnis ohne Antwort wird nur verworfen)."""
        text = self.session.discard_last_user()
        if text and not self.input.get("1.0", tk.END).strip():
            self.input.insert("1.0", text)

    # --- Werkzeuge ---------------------------------------------------------------------------

    @staticmethod
    def _call_key(marker: str) -> tuple[str, str]:
        call = find_call(marker)
        return (normalize_name(call.name), call.args) if call else ("", marker)

    def _warn_disabled_tool(self, text: str) -> None:
        """Marker für ein vorhandenes, aber für diesen Companion nicht freigeschaltetes Werkzeug: sichtbar melden,
        sonst sieht es aus, als wäre der Aufruf einfach wirkungslos."""
        call = find_call(text)
        name = normalize_name(call.name) if call else ""
        if name in self.registry.tools and name not in self.session.tool_names:
            self._append(f"[Werkzeug '{name}' ist für {self.session.persona.name} nicht freigeschaltet – "
                         "unter Companions… ankreuzen und einen neuen Chat beginnen]\n\n", "system")

    def _handle_call(self, call: ToolCall, note: str) -> None:
        arguments = self.native_args.pop(self._call_key(call.raw), None)  # nativer Aufruf: echte Argumente
        if arguments is not None:
            call = dataclasses.replace(call, arguments=arguments)
        self.tool_rounds += 1
        if self.tool_rounds > self.config.max_tool_calls:
            self._append("[Limit für Werkzeugaufrufe erreicht]\n\n", "system")
            self._finish("Bereit (Werkzeug-Limit erreicht)" + note)
            return
        allowed = self.session.tool_names
        known = normalize_name(call.name) in allowed
        spec = self.registry.tools.get(normalize_name(call.name))
        ask = self.config.confirm_tools if spec is None or spec.confirm is None else spec.confirm
        if normalize_name(call.name) in {normalize_name(a) for a in self.session.persona.approved_tools}:
            ask = False  # beim Companion vorab genehmigt: keine Rückfrage
        if known and ask and not messagebox.askyesno(
                "Werkzeug ausführen?",
                f"{self.session.persona.name} möchte das Werkzeug '{call.name}' ausführen.\n\n"
                f"Argumente: {call.args or '(keine)'}\n\nAusführen?"):
            self._on_tool(ToolOutcome(call.name, False, "Der Nutzer hat die Ausführung abgelehnt."))
            return
        self.status.set(f"Führe Werkzeug '{call.name}' aus…{note}")
        threading.Thread(target=self._run_tool, daemon=True,
                         args=(call, allowed, dataclasses.replace(self.config))).start()

    def _run_tool(self, call: ToolCall, allowed: list[str], config: Config) -> None:
        try:
            outcome = self.registry.run(call, allowed, max_chars=config.tool_result_chars,
                                        timeout=config.tool_timeout, choices=self.session.persona.tool_choices)
        except Exception as exc:  # ein Werkzeugfehler darf die GUI nie blockieren
            log.exception("Werkzeug '%s' fehlgeschlagen", call.name)
            outcome = ToolOutcome(call.name, False, f"Interner Fehler: {exc}")
        self.events.put(("tool", outcome))

    def _on_tool(self, outcome: ToolOutcome) -> None:
        if self.cancel.is_set():
            self._finish("Abgebrochen")
            return
        if outcome.ok and outcome.mode == RESULT_SHOW:  # Klartext in den Chat, keine weitere Modellrunde
            shown = (self.session.add_tool_shown(outcome.tool, outcome.text) if outcome.remember
                     else Message("user", outcome.text, TOOL_SHOWN, outcome.tool))
            self._show_message(shown)
            self._finish("Bereit")
            return
        if outcome.ok and outcome.mode == RESULT_DISCARD:  # Ergebnis verwerfen, nur Statuszeile
            if outcome.remember:
                self.session.add_tool_shown(outcome.tool, outcome.status)
            self._append(f"✔ {outcome.status}\n\n", "tool")
            self._finish("Bereit")
            return
        if outcome.ok and outcome.mode == RESULT_BOTH:  # Klartext im Chat (nur Anzeige) UND das Modell antwortet darauf
            self._show_message(Message("user", outcome.text, TOOL_SHOWN, outcome.tool))
            self.session.add_tool_result(outcome.tool, outcome.text, True)
            self._start_round()
            return
        self._show_message(self.session.add_tool_result(outcome.tool, outcome.text, outcome.ok))
        self._start_round()

    def _load_tools(self) -> None:
        self.registry.start_from_config(MCP_CONFIG_PATH)
        self.events.put(("tools_ready", None))

    def _on_tools_ready(self, _payload) -> None:
        if self.registry.errors:
            self.status.set("Werkzeuge: " + " | ".join(self.registry.errors))
        else:
            self.status.set(f"Werkzeuge bereit: {', '.join(self.registry.available()) or 'keine'}")
        self._update_context_label()

    # --- Chats und Companions ----------------------------------------------------------------

    def new_chat(self) -> None:
        if self.session.history and not messagebox.askyesno("Neuer Chat", "Aktuellen Chat verwerfen?"):
            return
        self.session.reset()
        self._render_history()
        self.status.set("Bereit")

    def _switch_persona(self, persona_id: str) -> None:
        self.config.persona = persona_id
        self.config.save(self.config_path)
        self.session.persona = load_persona(persona_id, self.personas_dir)
        self.persona_combo.set(persona_id)
        self.avatar.show(self.session.persona, self.personas_dir)

    def _persona_changed(self) -> None:
        name = self.persona_combo.get()
        if self.session.history and not messagebox.askyesno(
                "Companion wechseln", "Der aktuelle Chat wird dabei verworfen. Fortfahren?"):
            self.persona_combo.set(self.session.persona.id)
            return
        self._switch_persona(name)
        self.session.reset()
        self._render_history()

    def open_persona_editor(self) -> None:
        if self.editor is not None and self.editor.winfo_exists():
            self.editor.lift()
            return
        self.editor = PersonaEditor(self, select=self.session.persona.id)

    def on_personas_changed(self, saved: str | None = None, deleted: str | None = None) -> None:
        """Vom Editor aufgerufen: Auswahl aktualisieren; der aktive Companion wird bei Änderung neu geladen."""
        ids = list_personas(self.personas_dir)
        self.persona_combo.configure(values=ids)
        active = self.session.persona.id
        if deleted and deleted == active and ids:
            self._switch_persona(ids[0])
            self.session.reset()
            self._render_history()
        elif saved and saved == active:
            self.session.persona = load_persona(active, self.personas_dir)  # neuer Prompt gilt ab der nächsten Anfrage
            self.avatar.show(self.session.persona, self.personas_dir)
            self._update_context_label()

    def save_chat(self) -> None:
        if not self.session.history:
            messagebox.showwarning("Nichts zu speichern", "Der Chat ist noch leer.")
            return
        try:
            self.chats_dir.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
        path = filedialog.asksaveasfilename(
            initialdir=self.chats_dir, initialfile=chatlog.default_filename(self.session.persona.id),
            defaultextension=chatlog.CHAT_EXTENSION,
            filetypes=[("Chat (vollständig)", "*.aichat"), ("Text (lesbar)", "*.txt"), ("Chat (JSON, älteres Format)", "*.json")])
        if path:
            self.save_chat_to(path)

    def save_chat_to(self, path: Path | str) -> bool:
        try:
            if str(path).lower().endswith(".txt"):
                chatlog.save_text(path, self.session.history, self.config.user_name, self.session.persona.name)
            else:
                chatlog.save_json(path, self.session.history, self.session.persona.id, self.config.user_name)
        except OSError as exc:
            messagebox.showerror("Fehler", f"Speichern fehlgeschlagen: {exc}")
            return False
        self.status.set(f"Chat gespeichert: {Path(path).name}")
        return True

    def load_chat(self) -> None:
        if self.streaming:
            return
        path = filedialog.askopenfilename(initialdir=self.chats_dir, filetypes=[
            ("Chats", "*.aichat *.json *.txt"), ("Alle Dateien", "*.*")])
        if path:
            self.load_chat_from(path)

    def load_chat_from(self, path: Path | str, *, confirm: bool = True) -> bool:
        """Lädt einen Chat als reinen Verlauf. Werkzeugaufrufe darin werden NICHT erneut ausgeführt."""
        if self.session.history and confirm and not messagebox.askyesno(
                "Chat laden", "Der aktuelle Chat wird dabei verworfen. Fortfahren?"):
            return False
        names = {load_persona(i, self.personas_dir).name for i in list_personas(self.personas_dir)}
        try:
            loaded = chatlog.load_chat(path, {self.config.user_name}, names | {self.session.persona.name})
        except chatlog.ChatFileError as exc:
            messagebox.showerror("Chat laden", str(exc))
            return False
        notice = ""
        if loaded.persona and loaded.persona != self.session.persona.id:
            if loaded.persona in list_personas(self.personas_dir):
                self._switch_persona(loaded.persona)
            else:
                notice = f" (Companion '{loaded.persona}' nicht vorhanden - '{self.session.persona.id}' bleibt aktiv)"
        pending = self.session.load(loaded.messages)
        self._render_history()
        self._append(f"— Chat geladen: {Path(path).name}{notice} —\n\n", "system")
        if pending and not self.input.get("1.0", tk.END).strip():
            self.input.insert("1.0", pending)  # unbeantwortete letzte Frage zum erneuten Senden
        self.status.set(f"Chat geladen: {len(self.session.history)} Nachrichten")
        return True

    def close(self) -> None:
        self.cancel.set()
        self.registry.shutdown()
        self.root.destroy()

    # --- Einstellungen -----------------------------------------------------------------------

    def _on_models(self, result: tuple[list[str] | None, str]) -> None:
        models, error = result
        if models is None:
            self.status.set(error)
            return
        self.config.models = models
        self.status.set(f"{len(models)} Modelle geladen")
        if self.model_combo is not None and self.model_combo.winfo_exists():
            self.model_combo.configure(values=models)
            if models and not self.model_combo.get():
                self.model_combo.set(models[0])

    def open_settings(self) -> None:
        if self.settings_win is not None and self.settings_win.winfo_exists():
            self.settings_win.lift()
            return
        win = self.settings_win = tk.Toplevel(self.root)
        win.title("Einstellungen")
        win.columnconfigure(1, weight=1)
        cfg = self.config

        def entry(label: str, row: int, value, **kw) -> ttk.Entry:
            ttk.Label(win, text=label).grid(row=row, column=0, sticky="w", padx=10, pady=4)
            widget = ttk.Entry(win, width=44, **kw)
            widget.insert(0, str(value))
            widget.grid(row=row, column=1, sticky="we", padx=10, pady=4)
            return widget

        url = entry("Server-URL (inkl. /v1):", 0, cfg.base_url)
        key = entry("API-Schlüssel:", 1, cfg.api_key, show="*")
        ttk.Label(win, text="Modell:").grid(row=2, column=0, sticky="w", padx=10, pady=4)
        model = self.model_combo = ttk.Combobox(win, values=cfg.models)
        model.set(cfg.model)
        model.grid(row=2, column=1, sticky="we", padx=10, pady=4)
        user = entry("Dein Name im Chat:", 3, cfg.user_name)
        temp = entry("Temperatur:", 4, cfg.temperature)
        reply = entry("Max. Antwort-Tokens:", 5, cfg.max_reply_tokens)
        ctx = entry("Kontextfenster (Tokens):", 6, cfg.context_tokens)
        top_p = entry("Top-p:", 7, cfg.top_p)
        freq = entry("Frequency-Penalty (gegen Wiederholung):", 8, cfg.frequency_penalty)
        pres = entry("Presence-Penalty:", 9, cfg.presence_penalty)
        rep = entry("Repeat-Penalty (LM Studio; 0 = aus, bei Online-Diensten):", 10, cfg.repeat_penalty)
        calls = entry("Max. Werkzeugaufrufe pro Nachricht (0 = aus):", 11, cfg.max_tool_calls)
        confirm = tk.BooleanVar(value=cfg.confirm_tools)
        ttk.Checkbutton(win, text="Vor jedem Werkzeugaufruf nachfragen", variable=confirm).grid(
            row=12, column=1, sticky="w", padx=10, pady=4)

        def load_models() -> None:
            client = ChatClient(url.get().strip(), key.get())

            def work() -> None:
                try:
                    self.events.put(("models", (client.list_models(), "")))
                except Exception as exc:
                    self.events.put(("models", (None, f"Modelle laden fehlgeschlagen: {exc}")))

            self.status.set("Lade Modellliste…")
            threading.Thread(target=work, daemon=True).start()

        def save() -> None:
            def number(widget: ttk.Entry) -> float:
                return float(widget.get().replace(",", "."))

            try:
                values = dict(temperature=number(temp), max_reply_tokens=int(reply.get()),
                              context_tokens=int(ctx.get()), top_p=number(top_p), frequency_penalty=number(freq),
                              presence_penalty=number(pres), repeat_penalty=number(rep),
                              max_tool_calls=int(calls.get()))
            except ValueError:
                messagebox.showerror("Ungültiger Wert", "Zahlen eingeben (Tokens und Aufrufe: ganze Zahlen).",
                                     parent=win)
                return
            cfg.base_url, cfg.api_key, cfg.model = url.get(), key.get(), model.get().strip()
            cfg.user_name = user.get()
            cfg.confirm_tools = confirm.get()
            for name, value in values.items():
                setattr(cfg, name, value)
            cfg.save(self.config_path)
            self._update_context_label()
            win.destroy()

        buttons = ttk.Frame(win)
        buttons.grid(row=13, column=0, columnspan=2, pady=15)
        ttk.Button(buttons, text="Modelle laden", command=load_models).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Speichern", command=save).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Schließen", command=win.destroy).pack(side=tk.LEFT, padx=5)


def run() -> None:
    root = tk.Tk()
    ChatApp(root)
    root.mainloop()
