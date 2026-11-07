package app.aichatmcp.core.studio

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun tone(seconds: Double, rate: Int = TARGET_RATE, amp: Double = 0.4, lead: Double = 0.0, tail: Double = 0.0, freq: Double = 220.0): FloatArray {
    val body = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        (amp * sin(2 * PI * freq * t) * (0.6 + 0.4 * sin(2 * PI * 3 * t))).toFloat()
    }
    return FloatArray((lead * rate).toInt()) + body + FloatArray((tail * rate).toInt())
}

private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)

class SentenceSplitterTest {
    @Test
    fun bookTextBecomesRecordableSentencesLikeInPython() {
        val text = "Das war Dr. Meier, wie er leibt und lebt. „Kommst du heute Abend?“, fragte sie leise. Er antwortete nicht! " +
            "Warum auch,\ndachte er; es war ohnehin alles gesagt. Am 12. Mai war es so weit. Ein Hund, z. B. ein Dackel, " +
            "bellte laut im Hof.\n\nNach der Rück-\nkehr aß man zu Abend. Ja. Nach der Rückkehr aß man zu Abend."
        // Erwartung = Ausgabe von split_sentences in tools/voice/voicelib.py
        assertEquals(
            listOf(
                "Das war Dr. Meier, wie er leibt und lebt.", "Kommst du heute Abend? fragte sie leise.",
                "Warum auch, dachte er; es war ohnehin alles gesagt.", "Ein Hund, z. B. ein Dackel, bellte laut im Hof.",
                "Nach der Rückkehr aß man zu Abend.",
            ),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun longSentencesAreSplitAtSemicolonsOrDropped() {
        val long = "Eins zwei drei vier; fünf sechs sieben acht " + List(40) { "wort" }.joinToString(" ") + "."
        assertEquals(listOf("Eins zwei drei vier."), SentenceSplitter.split(long))
        assertEquals(emptyList(), SentenceSplitter.split("Wort ".repeat(40) + "Ende."))
    }
}

class AudioTest {
    @Test
    fun wavRoundtripAndHeader() {
        val x = AudioMath.toShort(tone(0.5))
        val bytes = Wav.encode(x, TARGET_RATE)
        assertEquals(44 + x.size * 2, bytes.size)
        val back = Wav.decode(bytes)
        assertEquals(TARGET_RATE, back.rate)
        assertEquals(1, back.channels)
        assertTrue(back.samples.contentEquals(x))
        // Rate steht ab Byte 24 (so liest sie auch StudioProject.durationSeconds)
        val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16)
        assertEquals(TARGET_RATE, rate)
    }

    @Test
    fun resamplingKeepsLengthAndRemovesAliasing() {
        val speech = tone(1.0, 44100, amp = 0.5, freq = 300.0)
        val down = AudioMath.resample(speech, 44100, TARGET_RATE)
        assertEquals(TARGET_RATE.toDouble(), down.size.toDouble(), 2.0)
        assertEquals(rms(speech), rms(down), 0.03) // Sprache bleibt erhalten
        val whistle = FloatArray(44100) { i -> (0.5 * sin(2 * PI * 15000.0 * i / 44100)).toFloat() } // 15 kHz liegt über der neuen Grenze
        assertTrue(rms(AudioMath.resample(whistle, 44100, TARGET_RATE)) < 0.02, "15 kHz muss weg, sonst wird er als Pfeifton zurückgefaltet")
        val up = AudioMath.resample(tone(1.0, 16000), 16000, TARGET_RATE)
        assertEquals(TARGET_RATE.toDouble(), up.size.toDouble(), 2.0)
    }

    @Test
    fun trimKeepsMarginAndStatsFindSilenceAndClipping() {
        val x = tone(2.0, lead = 3.0, tail = 3.0)
        assertEquals(2.5, AudioMath.trimSilence(x, TARGET_RATE).size.toDouble() / TARGET_RATE, 0.1)
        val silent = FloatArray(1000)
        assertEquals(1000, AudioMath.trimSilence(silent, TARGET_RATE).size)
        val s = AudioMath.stats(tone(2.0, lead = 1.0, tail = 0.5), TARGET_RATE)
        assertEquals(1.0, s.lead, 0.05)
        assertEquals(0.5, s.tail, 0.05)
        val loud = tone(1.0, amp = 3.0).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        assertTrue(AudioMath.stats(loud, TARGET_RATE).clip > 0.001)
    }
}

class StudioProjectTest {
    private fun temp(): File = kotlin.io.path.createTempDirectory("studio").toFile()

    private fun record(p: StudioProject, text: String, seconds: Double = 3.0, rate: Int = TARGET_RATE, amp: Double = 0.4) =
        p.save(text, AudioMath.toShort(tone(seconds, rate, amp, lead = 0.2, tail = 0.2)), rate)

    @Test
    fun sentencesRecordingsAndFileFormat() {
        val p = StudioProject(File(temp(), "meine-stimme"))
        assertEquals(3, p.addSentences(listOf("Eins zwei drei vier.", "Fünf sechs sieben acht.", "Neun zehn elf zwölf.", "Eins zwei drei vier.")))
        assertEquals("Eins zwei drei vier.", p.nextSentence())
        val rec = record(p, "Eins zwei drei vier.")
        assertEquals("0001.wav", rec.name)
        assertEquals(listOf("Fünf sechs sieben acht.", "Neun zehn elf zwölf."), p.pending())
        assertEquals("0001.wav|Eins zwei drei vier.\n", File(p.dir, "metadata.csv").readText()) // gleiches Format wie das Python-Werkzeug
        assertEquals(0, p.addSentences(listOf("Eins zwei drei vier."))) // schon aufgenommen: nicht noch einmal anbieten
        p.skip("Fünf sechs sieben acht.")
        assertEquals(listOf("Neun zehn elf zwölf.", "Fünf sechs sieben acht."), p.pending())
        record(p, "Neun zehn elf zwölf.")
        assertEquals("0002.wav", p.recordings().last().name)
        p.delete("0001.wav") // Satz kommt vorn zurück, Nummern werden nicht neu vergeben
        assertEquals("Eins zwei drei vier.", p.nextSentence())
        assertFalse(p.wavFile("0001.wav").exists())
        assertEquals("0003.wav", record(p, "Eins zwei drei vier.").name)
        assertEquals(6.8, p.minutes() * 60, 0.1) // zwei Aufnahmen à 3,4 s (3 s Ton + 0,2 s Rand je Seite)
    }

    @Test
    fun exportZipHasMetadataAndWavs() {
        val p = StudioProject(File(temp(), "x"))
        record(p, "Erster Satz hier.")
        record(p, "Zweiter Satz hier.")
        val out = ByteArrayOutputStream()
        assertEquals(2, p.exportZip(out))
        val names = mutableListOf<String>()
        var meta = ""
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                names += e.name
                if (e.name == "metadata.csv") meta = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(listOf("metadata.csv", "wavs/0001.wav", "wavs/0002.wav"), names)
        assertEquals("0001.wav|Erster Satz hier.\n0002.wav|Zweiter Satz hier.\n", meta)
    }

    @Test
    fun importZipTakesRecordingsFromThePythonToolAndSkipsKnownSentences() {
        // ZIP wie vom Python-Werkzeug: metadata.csv + wavs/…, eine Aufnahme mit 44.100 Hz, ein Eintrag ohne Datei, ein Pfadtrick
        val zip = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry() }
            put("metadata.csv", "0001.wav|Erster Satz hier.\n0002.wav|Zweiter Satz hier.\n0003.wav|Fehlt in der ZIP.\n0004|Vierter Satz hier.\n".toByteArray())
            put("wavs/0001.wav", wavBytes(AudioMath.toShort(tone(2.0)), TARGET_RATE))
            put("wavs/0002.wav", wavBytes(AudioMath.toShort(tone(2.0, 44100)), 44100))
            put("../../boese/0004.wav", wavBytes(AudioMath.toShort(tone(1.0)), TARGET_RATE)) // landet nur als Dateiname im Temp-Ordner
        }
        val p = StudioProject(File(temp(), "ziel"))
        record(p, "Erster Satz hier.") // gibt es schon: wird nicht doppelt übernommen
        assertEquals(2, p.importZip(zip.toByteArray().inputStream())) // Zweiter + Vierter
        assertEquals(listOf("Erster Satz hier.", "Zweiter Satz hier.", "Vierter Satz hier."), p.recordings().map { it.text })
        assertEquals(listOf("0001.wav", "0002.wav", "0003.wav"), p.recordings().map { it.name })
        val second = Wav.decode(p.wavFile("0002.wav").readBytes())
        assertEquals(TARGET_RATE, second.rate) // auf 22.050 Hz umgerechnet
        assertEquals(2.0, second.samples.size.toDouble() / TARGET_RATE, 0.05)
        assertFalse(File(p.dir, ".import").exists())
        assertFalse(File(p.dir.parentFile.parentFile, "boese").exists())
    }

    @Test
    fun checkReportsTheSameProblemsAsThePythonTool() {
        val good = StudioProject(File(temp(), "gut"))
        repeat(400) { record(good, "Satz Nummer $it hier.", seconds = 4.0) } // ~27 Minuten
        val ok = good.check()
        assertEquals(emptyList(), ok.errors)
        assertEquals(400, ok.count)
        assertTrue(ok.notes.any { "knapp" in it && "NEUE Sätze" in it })

        val bad = StudioProject(File(temp(), "schlecht"))
        record(bad, "Satz eins hier.", rate = 44100)
        bad.save("Laut hier gesprochen.", AudioMath.toShort(tone(2.0, amp = 3.0).map { it.coerceIn(-1f, 1f) }.toFloatArray()))
        File(bad.dir, "metadata.csv").appendText("0009.wav|Fehlt.\n")
        val report = bad.check()
        val all = (report.errors + report.notes).joinToString("\n")
        assertTrue("44100 Hz statt 22050 Hz" in all, all)
        assertTrue("übersteuert" in all, all)
        assertTrue("0009.wav: Datei fehlt" in all, all)
        assertTrue("zu wenig" in all, all)
    }

    @Test
    fun storeCreatesListsAndDeletesProjects() {
        val store = StudioStore(temp())
        store.create("Meine Stimme!")
        store.create("zweite")
        assertEquals(listOf("Meine_Stimme", "zweite"), store.list())
        store.delete("zweite")
        assertEquals(listOf("Meine_Stimme"), store.list())
        assertEquals("stimme", StudioStore.safe("../.."))
        assertTrue(abs(1.0) > 0)
    }
}
