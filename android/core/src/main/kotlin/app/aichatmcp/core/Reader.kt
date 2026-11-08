package app.aichatmcp.core

/** Was beim Vorlesen gerade geschieht. */
enum class ReaderState { STOPPED, PLAYING, PAUSED }

/** Ausgabe des Vorlesers: sprechen (mit Warteschlange) und anhalten. Die Rückmeldungen gehen über [Reader.utteranceDone]/[Reader.utteranceFailed]. */
interface ReaderOutput {
    /** Spricht [text] in [language] (Sprach-Tag oder null). [flush] ersetzt alles Laufende, sonst wird angehängt. */
    fun speak(text: String, id: String, language: String?, flush: Boolean)

    /**
     * Wie [speak], aber der Abschnitt ist in Erzähler- und Rede-Stücke zerlegt (jedes mit seiner [Role]); die Rückmeldung kommt erst nach dem
     * letzten Stück. Ohne eigene Umsetzung wird alles zusammen mit einer Stimme gesprochen.
     */
    fun speakParts(parts: List<Utterance>, id: String, language: String?, flush: Boolean) =
        speak(parts.joinToString(" ") { it.text }, id, language, flush)

    /** Bricht das Sprechen sofort ab (auch Angehängtes). */
    fun stopSpeaking()
}

/** Bilder im Text: Beschreibung holen (aus dem Zwischenspeicher oder vom Bildmodell) und vorab bestellen. */
interface ReaderPictures {
    /** Liefert die Beschreibung (null = nicht möglich) und ruft [done] danach auf dem Hauptthread auf. */
    fun describe(picture: ReadItem.Picture, done: (String?) -> Unit)

    /** Beschreibung schon während des vorigen Abschnitts berechnen (ohne Rückruf), damit hinterher keine Pause entsteht. */
    fun prefetch(picture: ReadItem.Picture)

    /** Laufende Beschreibung abbrechen. */
    fun cancel()

    companion object {
        /** Antwort von [describe]: das Bild ist Zierde (Rahmenteil, Symbol), weder beschreiben noch den Ersatztext vorlesen. */
        const val SKIP = "\u0000skip"
    }
}

/** Meldungen des Vorlesers an die Oberfläche. Alle auf dem Hauptthread. */
interface ReaderEvents {
    fun onState(state: ReaderState) {}

    /** Der Abschnitt [index] von [total] ist jetzt dran (Anzeige und Vorlese-Marke). */
    fun onPosition(index: Int, total: Int) {}

    /** Ein Text wird jetzt gesprochen (Abschnitt oder Bildbeschreibung); [fromImage] bei Bildbeschreibungen. */
    fun onSpoken(text: String, fromImage: Boolean) {}

    /** Wird gerade ein Bild beschrieben? */
    fun onDescribing(active: Boolean) {}

    fun onProblem(message: String) {}

    /** Der letzte Abschnitt ist gesprochen. */
    fun onFinished() {}
}

/**
 * Der Vorleser: spielt einen [ReadPlan] Abschnitt für Abschnitt ab und kennt Play, Pause, Stop und Sprünge. Er ist eine reine
 * Zustandsmaschine (ohne Android), damit sie auf der JVM testbar ist; Sprachausgabe, Bildmodell und Tonfokus hängen als
 * Schnittstellen dran. Immer auf dem Hauptthread benutzen.
 *
 * Bilder: Steht ein Bild im Text, wird es nach dem Absatz beschrieben und die Beschreibung ([pictureLanguage]) vorgelesen; danach
 * geht der Text weiter. Die Beschreibung wird schon beim Abschnitt davor bestellt.
 */
class Reader(
    val plan: ReadPlan,
    private val output: ReaderOutput,
    private val pictures: ReaderPictures,
    private val events: ReaderEvents,
    private val textLanguage: String?,
    private val pictureLanguage: String? = "en-US",
    startIndex: Int = 0,
) {
    var state = ReaderState.STOPPED
        private set

    /** Der Abschnitt, der gerade gesprochen wird (oder bei Pause: bei dem es weitergeht). */
    var index = startIndex.coerceIn(0, maxOf(plan.size - 1, 0))
        private set

    private var generation = 0 // macht späte Rückmeldungen alter Abschnitte unschädlich
    private var queuedUpTo = -1 // der letzte Abschnitt, der schon an die Sprachausgabe übergeben ist
    private var autoPaused = false // pausiert, weil ein Anruf oder anderer Ton dran ist: danach geht es von selbst weiter
    private var describing = false

    val total: Int get() = plan.size
    val page: Int get() = plan.pageOf(index) + 1
    val pageCount: Int get() = plan.pageStarts.size

    // --- Tasten -----------------------------------------------------------------------------------------------

    fun play() {
        if (plan.size == 0 || state == ReaderState.PLAYING) return
        autoPaused = false
        if (index >= plan.size) index = 0
        state = ReaderState.PLAYING
        events.onState(state)
        startAt(index)
    }

    fun pause() {
        if (state != ReaderState.PLAYING) return
        halt(ReaderState.PAUSED)
    }

    /** Der Ton ist beim Start noch belegt (z. B. Gespräch läuft): nicht sprechen, sondern warten; sobald er frei ist, startet es. */
    fun waitForFocus() {
        if (plan.size == 0 || state == ReaderState.PLAYING) return
        halt(ReaderState.PAUSED)
        autoPaused = true
    }

    /** Beendet das Vorlesen an der aktuellen Stelle (die Marke merkt sich die Oberfläche über [onPosition]). */
    fun stop() {
        if (state == ReaderState.STOPPED) return
        halt(ReaderState.STOPPED)
    }

    /** Springt zum Abschnitt [target]; läuft das Vorlesen, geht es dort sofort weiter. */
    fun seekTo(target: Int) {
        if (plan.size == 0) return
        val was = state
        halt(was) // alles Laufende beenden, Zustand behalten
        index = target.coerceIn(0, plan.size - 1)
        events.onPosition(index, total)
        if (was == ReaderState.PLAYING) startAt(index)
    }

    /** "<": ein Abschnitt zurück. */
    fun previous() = seekTo(index - 1)

    /** ">": ein Abschnitt vor. Hinter dem letzten Abschnitt ist das Ende. */
    fun next() {
        if (index + 1 >= plan.size) {
            finish()
        } else {
            seekTo(index + 1)
        }
    }

    /** "<<": Seite zurück, ohne Seiten 10 Abschnitte zurück. */
    fun jumpBack() = seekTo(plan.backTarget(index))

    /** ">>": Seite vor, ohne Seiten 10 Abschnitte vor. Hinter dem Ende ist das Ende. */
    fun jumpForward() {
        val target = plan.forwardTarget(index)
        if (target == null) finish() else seekTo(target)
    }

    // --- Tonfokus (Anruf, anderes Audio) ------------------------------------------------------------------------

    /** Vorübergehend verloren (Anruf, Benachrichtigung): anhalten, danach von selbst weiter. */
    fun focusLostTemporarily() {
        if (state != ReaderState.PLAYING) return
        halt(ReaderState.PAUSED)
        autoPaused = true
    }

    /** Dauerhaft verloren (andere App spielt Ton): anhalten, ohne von selbst weiterzumachen. */
    fun focusLostPermanently() {
        autoPaused = false
        pause()
    }

    /** Der Ton ist wieder frei: ging es wegen [focusLostTemporarily] aus, läuft das Vorlesen weiter. */
    fun focusGained() {
        if (autoPaused && state == ReaderState.PAUSED) play()
    }

    // --- Rückmeldungen der Sprachausgabe ----------------------------------------------------------------------

    fun utteranceDone(id: String) {
        val parts = id.split(':')
        if (parts.size < 3 || parts[0] != "r" || parts[1] != generation.toString() || state != ReaderState.PLAYING) return
        val finished = parts[2].toIntOrNull() ?: return
        if (finished != index) return
        advanceFrom(finished)
    }

    fun utteranceFailed(id: String) {
        if (!id.startsWith("r:$generation:") || state != ReaderState.PLAYING) return
        halt(ReaderState.PAUSED)
        events.onProblem("Die Sprachausgabe ist beim Vorlesen ausgefallen.")
    }

    // --- Ablauf ---------------------------------------------------------------------------------------------------

    private fun halt(newState: ReaderState) {
        generation++
        queuedUpTo = -1
        output.stopSpeaking()
        pictures.cancel()
        if (describing) {
            describing = false
            events.onDescribing(false)
        }
        if (newState != state) {
            state = newState
            events.onState(state)
        }
        events.onPosition(index, total) // Marke merken (bei Pause und Stopp an der aktuellen Stelle)
    }

    private fun finish() {
        halt(ReaderState.STOPPED)
        index = plan.size
        events.onPosition(index, total)
        events.onFinished()
    }

    private fun startAt(i: Int) {
        index = i
        events.onPosition(index, total)
        when (val item = plan.items[i]) {
            is ReadItem.Speech -> {
                speak(i, item.text, textLanguage, flush = true, fromImage = false, parts = item.parts)
                queueNext(i)
                prefetchPictureAfter(i)
            }
            is ReadItem.Picture -> if (item.decorative) advanceFrom(i) else describePicture(i, item)
        }
    }

    /** Hängt den nächsten Textabschnitt schon an, damit zwischen den Abschnitten keine Lücke entsteht. */
    private fun queueNext(i: Int) {
        val n = i + 1
        val item = plan.items.getOrNull(n) as? ReadItem.Speech ?: return
        if (n <= queuedUpTo) return
        speak(n, item.text, textLanguage, flush = false, fromImage = false, announce = false, parts = item.parts)
    }

    private fun speak(
        i: Int, text: String, language: String?, flush: Boolean, fromImage: Boolean, announce: Boolean = true,
        parts: List<Utterance> = emptyList(),
    ) {
        queuedUpTo = i
        if (announce) events.onSpoken(text, fromImage)
        if (parts.isEmpty()) output.speak(text, "r:$generation:$i", language, flush)
        else output.speakParts(parts, "r:$generation:$i", language, flush)
    }

    private fun advanceFrom(done: Int) {
        val n = done + 1
        if (n >= plan.size) {
            finish()
            return
        }
        index = n
        events.onPosition(index, total)
        when (val item = plan.items[n]) {
            is ReadItem.Speech -> {
                // war schon angehängt (läuft bereits); sonst jetzt sprechen
                if (queuedUpTo < n) speak(n, item.text, textLanguage, flush = true, fromImage = false, parts = item.parts) else events.onSpoken(item.text, false)
                queueNext(n)
                prefetchPictureAfter(n)
            }
            is ReadItem.Picture -> if (item.decorative) advanceFrom(n) else describePicture(n, item)
        }
    }

    /** Bestellt höchstens [PREFETCH_PICTURES] Bilder aus den nächsten [PREFETCH_ITEMS] Elementen (Zierde zählt nicht, Rest später). */
    private fun prefetchPictureAfter(i: Int) {
        var ordered = 0
        for (k in 1..PREFETCH_ITEMS) {
            val picture = plan.items.getOrNull(i + k) as? ReadItem.Picture ?: continue
            if (picture.decorative) continue
            pictures.prefetch(picture)
            if (++ordered >= PREFETCH_PICTURES) break
        }
    }

    companion object {
        /** So viele Elemente im Voraus werden Bildbeschreibungen bestellt (5 x ca. 25 s: auch ein größeres Bildmodell ist rechtzeitig fertig). */
        const val PREFETCH_ITEMS = 5

        /** Nie mehr als so viele Bilder auf einmal vorbestellt: bei vielen Bildern am Stück würde das Bildmodell sonst minutenlang rechnen. */
        const val PREFETCH_PICTURES = 2
    }

    private fun describePicture(i: Int, picture: ReadItem.Picture) {
        val gen = generation
        describing = true
        events.onDescribing(true)
        pictures.describe(picture) { description ->
            if (gen != generation || state != ReaderState.PLAYING) return@describe // zwischendurch angehalten oder gesprungen
            describing = false
            events.onDescribing(false)
            if (description == ReaderPictures.SKIP) return@describe advanceFrom(i) // erst am Bild selbst als Zierde erkannt
            val text = description?.takeIf { it.isNotBlank() }
            val spoken = text ?: picture.alt.takeIf { it.isNotBlank() }
            if (spoken == null) {
                advanceFrom(i) // nichts zu sagen: weiter im Text
            } else {
                speak(i, spoken, if (text != null) pictureLanguage else textLanguage, flush = true, fromImage = text != null)
            }
        }
    }
}
