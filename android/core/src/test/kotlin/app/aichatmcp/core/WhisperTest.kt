package app.aichatmcp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperTest {
    private fun tempDir(): File = kotlin.io.path.createTempDirectory("whisper").toFile()

    private fun put(store: WhisperStore, folder: String, name: String) = store.importFile(folder, name, "x".byteInputStream())

    @Test
    fun storeNeedsEncoderDecoderAndTokens() {
        val store = WhisperStore(tempDir())
        assertTrue(store.list().isEmpty())
        put(store, "sherpa-onnx-whisper-small", "small-encoder.int8.onnx")
        assertTrue(store.problem("sherpa-onnx-whisper-small")!!.contains("Decoder"))
        put(store, "sherpa-onnx-whisper-small", "small-decoder.int8.onnx")
        assertTrue(store.problem("sherpa-onnx-whisper-small")!!.contains("tokens"))
        assertNull(store.files("sherpa-onnx-whisper-small"))
        put(store, "sherpa-onnx-whisper-small", "small-tokens.txt")
        assertNull(store.problem("sherpa-onnx-whisper-small"))
        val files = assertNotNull(store.files("sherpa-onnx-whisper-small"))
        assertEquals("small-encoder.int8.onnx", files.encoder.name)
        assertEquals(listOf(WhisperModel("sherpa-onnx-whisper-small", false)), store.list())
        assertEquals("Whisper small", store.list().single().title)
        store.delete("sherpa-onnx-whisper-small")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun int8FilesWinOverTheBigOnes() {
        val store = WhisperStore(tempDir())
        for (n in listOf("m-encoder.onnx", "m-encoder.int8.onnx", "m-decoder.onnx", "m-decoder.int8.onnx", "m-tokens.txt")) put(store, "m", n)
        val files = store.files("m")!!
        assertEquals("m-encoder.int8.onnx", files.encoder.name)
        assertEquals("m-decoder.int8.onnx", files.decoder.name)
        // nur die große Fassung vorhanden: sie wird genommen
        for (n in listOf("b-encoder.onnx", "b-decoder.onnx", "b-tokens.txt")) put(store, "b", n)
        assertEquals("b-encoder.onnx", store.files("b")!!.encoder.name)
    }

    @Test
    fun redundantBigFilesAreFoundInAFolderListing() {
        val names = listOf("small-encoder.onnx", "small-encoder.int8.onnx", "small-decoder.onnx", "small-decoder.int8.onnx", "small-tokens.txt")
        assertEquals(setOf("small-encoder.onnx", "small-decoder.onnx"), WhisperNames.redundant(names))
        assertEquals(emptySet(), WhisperNames.redundant(listOf("a-encoder.onnx", "a-decoder.onnx")))
    }

    @Test
    fun importTakesOnlyModelFilesFromTheTopFolderAndStaysInside() {
        val root = tempDir()
        val store = WhisperStore(root)
        assertTrue(put(store, "x", "a-encoder.onnx"))
        assertTrue(put(store, "x", "a-tokens.txt"))
        assertFalse(put(store, "x", "test_wavs/0.wav"))
        assertFalse(put(store, "x", "README.md"))
        assertFalse(put(store, "x", "sub/a-decoder.onnx"))
        assertFalse(put(store, "x", "../boese.onnx"))
        assertFalse(put(store, "x", ""))
        assertEquals(listOf("a-encoder.onnx", "a-tokens.txt"), File(root, "x").list()!!.sorted())
    }

    @Test
    fun resolvePrefersTheNamedModelElseTheFirst() {
        val store = WhisperStore(tempDir())
        for (f in listOf("a", "b")) for (n in listOf("$f-encoder.onnx", "$f-decoder.onnx", "$f-tokens.txt")) put(store, f, n)
        assertEquals("b", store.resolve("b")!!.name)
        assertEquals("a", store.resolve("gibt-es-nicht")!!.name)
        assertEquals("a", store.resolve("")!!.name)
        assertNull(WhisperStore(tempDir()).resolve("a"))
    }

    @Test
    fun languagesAndTitles() {
        assertEquals("de", WhisperNames.languageOf("de-DE"))
        assertEquals("en", WhisperNames.languageOf("en_US"))
        assertEquals("ja", WhisperNames.languageOf("ja"))
        assertNull(WhisperNames.languageOf(null))
        assertNull(WhisperNames.languageOf(""))
        assertTrue(WhisperNames.isEnglishOnly("sherpa-onnx-whisper-small.en"))
        assertTrue(WhisperNames.isEnglishOnly("whisper-base-en"))
        assertFalse(WhisperNames.isEnglishOnly("sherpa-onnx-whisper-small"))
        assertFalse(WhisperNames.isEnglishOnly("sherpa-onnx-whisper-medium"))
        assertEquals("Whisper medium", WhisperNames.title("sherpa-onnx-whisper-medium"))
        assertEquals("Whisper tiny.en", WhisperNames.title("sherpa-onnx-whisper-tiny.en"))
    }

    @Test
    fun silenceIsRecognizedBeforeWhisperInventsText() {
        assertTrue(WhisperAudio.isSilent(FloatArray(0)))
        assertTrue(WhisperAudio.isSilent(FloatArray(16000))) // 1 s Stille
        assertTrue(WhisperAudio.isSilent(FloatArray(1000) { 0.5f })) // zu kurz
        assertTrue(WhisperAudio.isSilent(FloatArray(16000) { if (it % 2 == 0) 0.004f else -0.004f })) // leises Rauschen
        assertFalse(WhisperAudio.isSilent(FloatArray(16000) { if (it % 2 == 0) 0.3f else -0.3f }))
    }

    @Test
    fun recordingEndsAfterAPauseFollowingSpeechButNotBefore() {
        val speech = FloatArray(1600) { if (it % 2 == 0) 0.2f else -0.2f } // 0,1 s
        val quiet = FloatArray(1600)
        val d = SilenceDetector()
        repeat(30) { assertFalse(d.feed(quiet)) } // 3 s Stille am Anfang: noch kein Ende (der Nutzer hat nicht angefangen)
        repeat(5) { assertFalse(d.feed(speech)) }
        repeat(15) { assertFalse(d.feed(quiet)) } // 1,5 s Pause
        assertFalse(d.feed(speech)) // Sprache setzt die Pause zurück
        repeat(15) { assertFalse(d.feed(quiet)) }
        assertTrue(d.feed(quiet)) // 1,6 s
        assertFalse(SilenceDetector().feed(FloatArray(0)))
    }

    @Test
    fun melBandsFollowTheModelGeneration() {
        assertEquals(80, WhisperNames.featureDim("sherpa-onnx-whisper-small"))
        assertEquals(80, WhisperNames.featureDim("sherpa-onnx-whisper-medium"))
        assertEquals(128, WhisperNames.featureDim("sherpa-onnx-whisper-large-v3"))
        assertEquals(128, WhisperNames.featureDim("sherpa-onnx-whisper-turbo"))
        assertEquals(80, WhisperNames.featureDim("sherpa-onnx-whisper-large-v2"))
    }

    @Test
    fun pcm16BecomesFloats() {
        val bytes = byteArrayOf(0, 0, 0, 0x40, 0, (0xC0).toByte(), 0xFF.toByte(), 0x7F)
        val f = WhisperAudio.fromPcm16(bytes)
        assertEquals(4, f.size)
        assertEquals(0f, f[0])
        assertEquals(0.5f, f[1])
        assertEquals(-0.5f, f[2])
        assertTrue(f[3] > 0.999f)
        assertEquals(2, WhisperAudio.fromPcm16(bytes, 4).size)
    }

    @Test
    fun knownHallucinationsAreDropped() {
        assertEquals("", WhisperAudio.clean(" Untertitel der Amara.org-Community "))
        assertEquals("", WhisperAudio.clean("Thanks for watching!"))
        assertEquals("Wie wird das Wetter morgen?", WhisperAudio.clean(" Wie wird das Wetter morgen? "))
        val long = "Das Wort Untertitel kommt hier vor, aber der Satz ist ein echter, längerer Satz, den jemand gesagt hat, wirklich."
        assertEquals(long, WhisperAudio.clean(long))
    }

    @Test
    fun engineChoiceIsStoredInTheConfig() {
        val c = Config(speechEngine = ENGINE_WHISPER, whisperModel = "sherpa-onnx-whisper-small")
        val back = AppJson.decodeFromString(Config.serializer(), AppJson.encodeToString(Config.serializer(), c))
        assertEquals(ENGINE_WHISPER, back.speechEngine)
        assertEquals("sherpa-onnx-whisper-small", back.whisperModel)
        assertEquals(ENGINE_GOOGLE, Config().speechEngine)
    }
}
