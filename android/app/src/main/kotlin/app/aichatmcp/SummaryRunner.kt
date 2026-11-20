package app.aichatmcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aichatmcp.core.SummaryData
import app.aichatmcp.core.SummaryException
import app.aichatmcp.core.SummaryLevel
import app.aichatmcp.core.Summaries
import app.aichatmcp.core.WireMessage
import app.aichatmcp.ui.tNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fasst ein geladenes Buch in Stufen zusammen (6 Sätze, ausführlich, Kapitel für Kapitel). Der Stand wird nach jeder Anfrage gespeichert
 * ([AppStorage.saveSummary]): Ein Abbruch kostet nichts, und eine zweite Stufe nutzt die schon fertigen Teile.
 */
class SummaryRunner(
    private val scope: CoroutineScope,
    private val storage: AppStorage,
    private val ask: suspend (List<WireMessage>, Int) -> String?,
    private val budgetChars: (SummaryLevel) -> Int,
    private val keepAlive: (Boolean) -> Unit,
) {
    var busy by mutableStateOf(false)
        private set
    var level by mutableStateOf<SummaryLevel?>(null)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var what by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    /** Die Ergebnisse zum gerade gewählten Buch (aus dem Zwischenspeicher oder vom laufenden Lauf). */
    var data by mutableStateOf<SummaryData?>(null)
        private set

    private var job: Job? = null

    /** Zeigt, was zu diesem Buch schon zusammengefasst wurde. */
    fun show(name: String, text: String) {
        if (busy) return
        error = ""
        data = storage.loadSummary(Summaries.key(name, text))
    }

    /** Wie viele Anfragen die Stufe noch braucht (schon Gespeichertes zählt nicht). */
    fun requests(name: String, text: String, level: SummaryLevel): Int =
        Summaries.requests(Summaries.parts(text), level, storage.loadSummary(Summaries.key(name, text)) ?: SummaryData())

    fun start(name: String, text: String, level: SummaryLevel, language: String) {
        if (busy) return
        busy = true
        this.level = level
        error = ""
        done = 0
        total = 0
        what = ""
        keepAlive(true)
        job = scope.launch {
            try {
                val parts = withContext(Dispatchers.Default) { Summaries.parts(text) }
                val key = Summaries.key(name, text)
                val begin = storage.loadSummary(key) ?: SummaryData(key = key)
                data = begin
                data = Summaries.run(
                    parts, level, language, begin, budgetChars(level), ask,
                    onProgress = { d, t, w -> done = d; total = t; what = w },
                    onData = { d -> data = d; runCatching { storage.saveSummary(d) } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SummaryException) {
                error = e.message.orEmpty()
            } catch (e: Throwable) {
                error = e.message ?: e::class.simpleName.orEmpty()
            } finally {
                busy = false
                this@SummaryRunner.level = null
                what = ""
                keepAlive(false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        error = tNow("Stopped. What is done is kept.", "Angehalten. Was fertig ist, bleibt erhalten.")
    }
}
