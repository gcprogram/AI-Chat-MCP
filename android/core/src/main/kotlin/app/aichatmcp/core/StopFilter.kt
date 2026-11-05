package app.aichatmcp.core

/**
 * Schneidet Stop-Sequenzen aus einem Textstrom (wie der Parameter "stop" bei Servern): Text vor der Sequenz wird
 * ausgegeben, die Sequenz selbst und alles danach nicht. Das Ende, das der Anfang einer Stop-Sequenz sein könnte,
 * wird zurückgehalten, bis klar ist, ob sie vollständig wird.
 */
class StopFilter(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }
    private val holdBack = (this.stops.maxOfOrNull { it.length } ?: 1) - 1
    private val buffer = StringBuilder()

    var stopped = false
        private set

    /** Nimmt ein Textstück entgegen und liefert den Text, der ausgegeben werden darf. */
    fun feed(piece: String): String {
        if (stopped) return ""
        buffer.append(piece)
        val hit = stops.map { buffer.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (hit != null) {
            stopped = true
            return buffer.substring(0, hit).also { buffer.clear() }
        }
        val safe = buffer.length - holdBack
        if (safe <= 0) return ""
        return buffer.substring(0, safe).also { buffer.delete(0, safe) }
    }

    /** Am Ende des Stroms: der zurückgehaltene Rest (er enthält keine vollständige Stop-Sequenz). */
    fun flush(): String {
        if (stopped) return ""
        return buffer.toString().also { buffer.clear() }
    }
}
