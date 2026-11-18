package app.aichatmcp.core

import java.io.IOException

/** Art der gerade aktiven Verbindung (aus der Sicht eines großen Downloads). */
enum class NetKind {
    /** WLAN oder Kabel, nicht getaktet. */
    WIFI,

    /** Mobilfunk oder getaktete Verbindung (z. B. WLAN-Hotspot eines Handys): kostet Datenvolumen. */
    MOBILE,

    /** Kein Netz. */
    NONE,
}

/** Was ein laufender oder unterbrochener Download bei der aktuellen Verbindung tun soll. */
enum class NetAction {
    /** Weiter laden. */
    RUN,

    /** Nur Mobilfunk da, aber der Nutzer hat ihn nicht erlaubt: anhalten und nachfragen. */
    ASK,

    /** Gar kein Netz: anhalten und warten. */
    WAIT,
}

/**
 * Regel für große Downloads: Sie laufen im WLAN. Mobilfunk gilt nur, wenn der Nutzer ihn erlaubt hat (beim Start ohne WLAN oder nach der
 * Rückfrage "Über Mobilfunk fortsetzen"). Fällt das WLAN während des Ladens weg, wird angehalten und nachgefragt; ohne Netz wird gewartet.
 */
object NetworkPolicy {
    fun action(kind: NetKind, allowMobile: Boolean): NetAction = when (kind) {
        NetKind.WIFI -> NetAction.RUN
        NetKind.MOBILE -> if (allowMobile) NetAction.RUN else NetAction.ASK
        NetKind.NONE -> NetAction.WAIT
    }
}

/** Die Verbindung riss mitten im Download ab (die angefangene Datei bleibt für das Fortsetzen liegen). */
class DownloadInterrupted(message: String) : IOException(message)
