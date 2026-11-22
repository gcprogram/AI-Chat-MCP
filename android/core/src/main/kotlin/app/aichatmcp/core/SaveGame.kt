package app.aichatmcp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ein gespeichertes Spiel ("Buch spielen"): Datei `*.savegame` (JSON). Sie enthält alles, um mitten in der Geschichte weiterzuspielen, auch ohne das Buch: die Szenen
 * mit ihrem Text, das vorbereitete Spielbuch, den Spielstand (Verwundungen, Beziehungen, gespielte Szenen), die angebotenen Entscheidungen und den Chat.
 * Spielbücher (`*.game`) sind das vorbereitete Buch ohne Spielstand ([Gamebooks]).
 */
@Serializable
data class SaveGame(
    val format: String = FORMAT,
    val version: Int = 1,
    val title: String = "",
    val savedAt: Long = 0,
    val setup: SavedSetup = SavedSetup(),
    val run: SavedRun = SavedRun(),
    /** Der Chat im Format der `.aichat`-Dateien ([ChatLog]). */
    val chat: String = "",
) {
    companion object {
        const val FORMAT = "aichat-savegame"
        const val EXTENSION = ".savegame"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        /** Gehört [text] zu einem gespeicherten Spiel (und nicht zu einem Spielbuch)? */
        fun isSaveGame(text: String): Boolean = "\"$FORMAT\"" in text.take(400)

        fun toJson(save: SaveGame): String = JSON.encodeToString(serializer(), save)

        fun parse(text: String): SaveGame? = runCatching { JSON.decodeFromString(serializer(), text.trimStart { it == '﻿' || it.isWhitespace() }) }.getOrNull()?.takeIf { it.format == FORMAT && it.setup.scenes.isNotEmpty() }

        /** Dateiname fürs Spiel: Titel ohne Zeichen, die Dateisysteme nicht mögen, plus Endung. */
        fun fileName(title: String): String = BookFileNames.clean(title.ifBlank { "game" }) + EXTENSION
    }
}

/** Dateinamen im Ordner für Spielbücher und gespeicherte Spiele. */
object BookFileNames {
    const val GAME_EXTENSION = ".game"

    fun clean(title: String): String = title.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").replace(Regex("""\s+"""), " ").take(80).trim().trim('.').ifEmpty { "game" }

    fun gameName(title: String): String = clean(title) + GAME_EXTENSION
}

@Serializable
data class SavedScene(val title: String = "", val text: String = "")

@Serializable
data class SavedDecision(val kind: DecisionKind = DecisionKind.STORY, val text: String = "", val relations: Map<String, Int> = emptyMap(), val scene: Int = -1) {
    fun toDecision() = Decision(kind, text, relations, scene)

    companion object {
        fun of(d: Decision) = SavedDecision(d.kind, d.text, d.relations, d.scene)
    }
}

@Serializable
data class SavedSetup(
    val title: String = "", val scenes: List<SavedScene> = emptyList(), val player: String = "", val firstPerson: Boolean = false, val german: Boolean = true,
    val solo: Boolean = false, val scenesPerDay: Int = Solo.DEFAULT_SCENES_PER_DAY, val known: List<String> = emptyList(), val mode: String = "classic",
    val plots: Map<Int, String> = emptyMap(), val summaryKey: String = "", val threads: Map<Int, String> = emptyMap(), val book: Gamebook? = null,
    val skip: List<Int> = emptyList(), val narrator: String = "", val verbatim: Boolean = false, val source: Boolean = false,
) {
    fun toSetup() = RoleplaySetup(
        title, scenes.map { Scene(it.title, it.text) }, player, firstPerson, german, solo, scenesPerDay, known, DecisionMode.parse(mode), plots, summaryKey, threads,
        book = book, skip = skip.toSet(), narrator = narrator, verbatim = verbatim, source = source,
    )

    companion object {
        fun of(s: RoleplaySetup) = SavedSetup(
            s.title, s.scenes.map { SavedScene(it.title, it.text) }, s.player, s.firstPerson, s.german, s.solo, s.scenesPerDay, s.known, s.mode.name.lowercase(),
            s.plots, s.summaryKey, s.threads, s.book, s.skip.sorted(), s.narrator, s.verbatim, s.source,
        )
    }
}

@Serializable
data class SavedSolo(
    val wounds: Int = 0, val wrongToday: Int = 0, val scenesPlayed: Int = 0, val day: Int = 1, val relations: Map<String, Int> = emptyMap(), val end: SoloEnd? = null,
) {
    fun toState() = SoloState(wounds, wrongToday, scenesPlayed, day, relations, end)

    companion object {
        fun of(s: SoloState) = SavedSolo(s.wounds, s.wrongToday, s.scenesPlayed, s.day, s.relations, s.end)
    }
}

@Serializable
data class SavedRun(
    val sceneIndex: Int = 0, val turns: Int = 0, val closing: Boolean = false, val solo: SavedSolo = SavedSolo(), val decisions: List<SavedDecision> = emptyList(),
    val played: List<Int> = emptyList(), val order: List<Int> = emptyList(), val answered: List<SavedDecision> = emptyList(), val chosen: Int = -1, val outcome: String = "",
)
