"""Tkinter-Oberfläche. Der Streaming-Thread spricht mit der GUI ausschließlich über eine Queue."""
from __future__ import annotations

import dataclasses
import json
import logging
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

from .config import Config
from .llm_client import ChatClient, LLMError
from .persona import list_personas, load_persona
from .session import ChatSession

log = logging.getLogger(__name__)
POLL_MS = 50


class ChatApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.config = Config.load()
        self.session = ChatSession(self.config, load_persona(self.config.persona))
        self.events: queue.Queue = queue.Queue()
        self.cancel = threading.Event()
        self.streaming = False
        self.block: str | None = None  # gerade ausgegebener Block: "assistant" | "reasoning"
        self.last_usage: dict | None = None
        self.sent_messages: list[dict] = []
        self.settings_win: tk.Toplevel | None = None
        self.model_combo: ttk.Combobox | None = None

        root.title("AI-Chat")
        root.geometry("760x680")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)
        self._build_toolbar()
        self._build_transcript()
        self._build_input()
        self._build_statusbar()
        self._new_chat_display()
        root.after(POLL_MS, self._poll)

    # --- Aufbau ------------------------------------------------------------------------------

    def _build_toolbar(self) -> None:
        bar = ttk.Frame(self.root)
        bar.grid(row=0, column=0, sticky="we", padx=10, pady=8)
        ttk.Label(bar, text="Persönlichkeit:").pack(side=tk.LEFT)
        self.persona_combo = ttk.Combobox(bar, values=list_personas(), state="readonly", width=16)
        self.persona_combo.set(self.config.persona)
        self.persona_combo.pack(side=tk.LEFT, padx=5)
        self.persona_combo.bind("<<ComboboxSelected>>", lambda _e: self._persona_changed())
        self.new_btn = ttk.Button(bar, text="Neuer Chat", command=self.new_chat)
        self.new_btn.pack(side=tk.LEFT, padx=5)
        ttk.Button(bar, text="Einstellungen", command=self.open_settings).pack(side=tk.RIGHT)
        ttk.Button(bar, text="Chat speichern…", command=self.save_chat).pack(side=tk.RIGHT, padx=5)

    def _build_transcript(self) -> None:
        self.chat = scrolledtext.ScrolledText(self.root, wrap=tk.WORD, state=tk.DISABLED)
        self.chat.grid(row=1, column=0, sticky="nsew", padx=10)
        self.chat.tag_configure("name", font=("Arial", 10, "bold"))
        self.chat.tag_configure("user", background="#e8f0fe")
        self.chat.tag_configure("assistant", background="#f1f1f1")
        self.chat.tag_configure("reasoning", background="#fafafa", foreground="#888888")
        self.chat.tag_configure("system", foreground="#1a56c4", font=("Arial", 10, "italic"))
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

    def _new_chat_display(self) -> None:
        self.chat.configure(state=tk.NORMAL)
        self.chat.delete("1.0", tk.END)
        self.chat.configure(state=tk.DISABLED)
        self.block = None
        persona = self.session.persona
        self._append(f"{persona.name}: ", "name", "assistant")
        if persona.greeting:
            self._append(f"{persona.greeting}\n\n", "assistant")
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
        self.sent_messages = self.session.request_messages()
        self._append(f"{self.config.user_name}: ", "name", "user")
        self._append(f"{text}\n\n", "user")
        self._update_context_label()

        self.cancel.clear()
        self.block, self.last_usage = None, None
        self._set_streaming(True)
        self.status.set("Warte auf Modell…")
        threading.Thread(target=self._stream, daemon=True, args=(
            dataclasses.replace(self.config), self.sent_messages, self.session.stop_sequences,
        )).start()

    def _set_streaming(self, streaming: bool) -> None:
        self.streaming = streaming
        self.send_btn.configure(state=tk.DISABLED if streaming else tk.NORMAL)
        self.new_btn.configure(state=tk.DISABLED if streaming else tk.NORMAL)
        self.persona_combo.configure(state=tk.DISABLED if streaming else "readonly")
        self.stop_btn.configure(state=tk.NORMAL if streaming else tk.DISABLED)

    def _stream(self, config: Config, messages: list[dict], stop: list[str]) -> None:
        reply: list[str] = []
        try:
            client = ChatClient(config.base_url, config.api_key)
            for event in client.stream_chat(config.model, messages, temperature=config.temperature,
                                            max_tokens=config.max_reply_tokens, stop=stop, cancel=self.cancel):
                if event.kind == "content":
                    reply.append(event.text)
                self.events.put((event.kind, event))
            self.events.put(("done", "".join(reply)))
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
        self.root.after(POLL_MS, self._poll)

    def _switch_block(self, block: str) -> None:
        if block == self.block:
            return
        if self.block is not None:
            self._append("\n\n")
        if block == "reasoning":
            self._append("Denkt nach…\n", "system")
        else:
            self._append(f"{self.session.persona.name}: ", "name", "assistant")
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

    def _on_done(self, reply: str) -> None:
        self._set_streaming(False)
        cancelled = self.cancel.is_set()
        if reply.strip():  # nur der Antworttext kommt in den Verlauf, nie das Reasoning
            self.session.add_assistant(reply.strip())
            if self.last_usage:
                self.session.record_usage(self.sent_messages, self.last_usage.get("prompt_tokens", 0))
            self._append("\n\n" if self.block else "")
            self.status.set("Abgebrochen (Teilantwort gespeichert)" if cancelled else "Bereit")
        else:
            self._restore_failed_input()
            self._append("[Keine Antwort erhalten]\n\n", "system")
            self.status.set("Abgebrochen" if cancelled else "Leere Antwort")
        self.block = None
        self._update_context_label()

    def _on_error(self, message: str) -> None:
        self._set_streaming(False)
        self._restore_failed_input()
        self.block = None
        self._append(f"[Fehler] {message}\n\n", "error")
        self.status.set("Fehler")
        self._update_context_label()

    def _restore_failed_input(self) -> None:
        """Die Frage ohne Antwort aus dem Verlauf nehmen und wieder ins Eingabefeld legen."""
        text = self.session.discard_last_user()
        if text and not self.input.get("1.0", tk.END).strip():
            self.input.insert("1.0", text)

    def new_chat(self) -> None:
        if self.session.history and not messagebox.askyesno("Neuer Chat", "Aktuellen Chat verwerfen?"):
            return
        self.session.reset()
        self._new_chat_display()
        self.status.set("Bereit")

    def _persona_changed(self) -> None:
        name = self.persona_combo.get()
        if self.session.history and not messagebox.askyesno(
                "Persönlichkeit wechseln", "Der aktuelle Chat wird dabei verworfen. Fortfahren?"):
            self.persona_combo.set(self.config.persona)
            return
        self.config.persona = name
        self.config.save()
        self.session.persona = load_persona(name)
        self.session.reset()
        self._new_chat_display()

    def save_chat(self) -> None:
        if not self.session.history:
            messagebox.showwarning("Nichts zu speichern", "Der Chat ist noch leer.")
            return
        path = filedialog.asksaveasfilename(defaultextension=".txt",
                                            filetypes=[("Text", "*.txt"), ("JSON", "*.json")])
        if not path:
            return
        turns = self.session.transcript()
        try:
            with open(path, "w", encoding="utf-8") as f:
                if path.lower().endswith(".json"):
                    json.dump([{"speaker": who, "text": text} for who, text in turns], f, ensure_ascii=False, indent=2)
                else:
                    f.write("\n\n".join(f"{who}: {text}" for who, text in turns) + "\n")
        except OSError as exc:
            messagebox.showerror("Fehler", f"Speichern fehlgeschlagen: {exc}")

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
            ttk.Label(win, text=label).grid(row=row, column=0, sticky="w", padx=10, pady=5)
            widget = ttk.Entry(win, width=44, **kw)
            widget.insert(0, str(value))
            widget.grid(row=row, column=1, sticky="we", padx=10, pady=5)
            return widget

        url = entry("Server-URL (inkl. /v1):", 0, cfg.base_url)
        key = entry("API-Schlüssel:", 1, cfg.api_key, show="*")
        ttk.Label(win, text="Modell:").grid(row=2, column=0, sticky="w", padx=10, pady=5)
        model = self.model_combo = ttk.Combobox(win, values=cfg.models)
        model.set(cfg.model)
        model.grid(row=2, column=1, sticky="we", padx=10, pady=5)
        user = entry("Dein Name im Chat:", 3, cfg.user_name)
        temp = entry("Temperatur:", 4, cfg.temperature)
        reply = entry("Max. Antwort-Tokens:", 5, cfg.max_reply_tokens)
        ctx = entry("Kontextfenster (Tokens):", 6, cfg.context_tokens)

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
            try:
                values = (float(temp.get().replace(",", ".")), int(reply.get()), int(ctx.get()))
            except ValueError:
                messagebox.showerror("Ungültiger Wert", "Temperatur: Zahl; Tokens: ganze Zahlen.", parent=win)
                return
            cfg.base_url, cfg.api_key, cfg.model = url.get(), key.get(), model.get().strip()
            cfg.user_name = user.get()
            cfg.temperature, cfg.max_reply_tokens, cfg.context_tokens = values
            cfg.save()
            self._update_context_label()
            win.destroy()

        buttons = ttk.Frame(win)
        buttons.grid(row=7, column=0, columnspan=2, pady=15)
        ttk.Button(buttons, text="Modelle laden", command=load_models).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Speichern", command=save).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Schließen", command=win.destroy).pack(side=tk.LEFT, padx=5)


def run() -> None:
    root = tk.Tk()
    ChatApp(root)
    root.mainloop()
