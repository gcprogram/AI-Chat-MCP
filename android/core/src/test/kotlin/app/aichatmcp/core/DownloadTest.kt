package app.aichatmcp.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTest {
    private val data = ByteArray(700_000) { (it * 31 % 251).toByte() }
    private val requests = mutableListOf<String>() // "Pfad Range"
    private var ranges = true
    private var stall = false // hält die Verbindung nach cutAfter Bytes offen, statt sie zu schließen (totes WLAN)
    private var cutAfter = -1 // bricht die Antwort nach so vielen Bytes ab (simuliert Verbindungsabbruch)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val dir = kotlin.io.path.createTempDirectory("dl").toFile()

    @AfterTest
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        requests += "${ex.requestURI.path} ${range.orEmpty()}"
        when (ex.requestURI.path) {
            "/missing" -> ex.sendResponseHeaders(404, -1)
            "/protected" -> ex.sendResponseHeaders(401, -1)
            else -> {
                val partial = ranges && range != null
                val start = if (partial) range!!.removePrefix("bytes=").substringBefore('-').toInt() else 0
                if (partial) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                val length = data.size - start
                ex.sendResponseHeaders(if (partial) 206 else 200, length.toLong())
                ex.responseBody.use { out ->
                    if (cutAfter in 0 until length) {
                        out.write(data, start, cutAfter)
                        out.flush()
                        if (stall) Thread.sleep(4000)
                        ex.close() // Verbindung bricht mitten in der Datei ab
                    } else {
                        out.write(data, start, length)
                    }
                }
            }
        }
    }

    private fun file(path: String = "/model.gguf", name: String = "model.gguf", bytes: Long = data.size.toLong()) = RemoteFile(base + path, name, bytes)

    @Test
    fun downloadsAFileWithProgressAndSkipsItNextTime() {
        val progress = mutableListOf<Downloader.Progress>()
        Downloader().download(listOf(file()), dir) { progress += it }
        assertContentEquals(data, File(dir, "model.gguf").readBytes())
        assertFalse(File(dir, "model.gguf.part").exists())
        assertTrue(progress.last().doneBytes == progress.last().totalBytes && progress.size > 2)
        requests.clear()
        Downloader().download(listOf(file()), dir) // vollständig: kein neuer Aufruf
        assertEquals(emptyList(), requests)
    }

    @Test
    fun resumesAnInterruptedDownloadWithARangeRequest() {
        cutAfter = 300_000
        assertFailsWith<IOException> { Downloader().download(listOf(file()), dir) }
        val part = File(dir, "model.gguf.part")
        val have = part.length()
        assertTrue(have in 1 until data.size) // der Anfang bleibt liegen
        cutAfter = -1
        requests.clear()
        Downloader().download(listOf(file()), dir)
        assertContentEquals(data, File(dir, "model.gguf").readBytes())
        assertEquals("/model.gguf bytes=$have-", requests.single()) // nur der Rest wurde geholt
    }

    @Test
    fun aDroppedConnectionIsReportedAsInterruptedNotAsAnError() {
        cutAfter = 300_000
        assertFailsWith<DownloadInterrupted> { Downloader().download(listOf(file()), dir) }
        server.stop(0)
        assertFailsWith<DownloadInterrupted> { Downloader().download(listOf(file()), dir) } // gar nicht erst verbunden
    }

    @Test
    fun abortClosesAStalledConnectionAtOnce() {
        cutAfter = 100_000
        stall = true
        val downloader = Downloader()
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        var failure: Throwable? = null
        val worker = Thread { failure = runCatching { downloader.download(listOf(file()), dir, isCancelled = { stopFlag.get() }) }.exceptionOrNull() }
        worker.start()
        Thread.sleep(800) // der Server hat die ersten Bytes geschickt und hängt
        stopFlag.set(true)
        downloader.abort()
        worker.join(3000)
        assertFalse(worker.isAlive, "abort() muss die hängende Verbindung beenden: " + worker.stackTrace.take(12).joinToString(" | "))
        assertTrue(failure is DownloadCancelled, failure.toString())
        assertTrue(File(dir, "model.gguf.part").length() > 0) // der Anfang bleibt für das Fortsetzen
    }

    @Test
    fun networkPolicyKeepsMobileDataOutUnlessAllowed() {
        assertEquals(NetAction.RUN, NetworkPolicy.action(NetKind.WIFI, allowMobile = false))
        assertEquals(NetAction.ASK, NetworkPolicy.action(NetKind.MOBILE, allowMobile = false))
        assertEquals(NetAction.RUN, NetworkPolicy.action(NetKind.MOBILE, allowMobile = true))
        assertEquals(NetAction.WAIT, NetworkPolicy.action(NetKind.NONE, allowMobile = true))
        assertEquals(NetAction.WAIT, NetworkPolicy.action(NetKind.NONE, allowMobile = false))
    }

    @Test
    fun restartsWhenTheServerCannotDoRanges() {
        cutAfter = 200_000
        assertFailsWith<IOException> { Downloader().download(listOf(file()), dir) }
        cutAfter = -1
        ranges = false // der Server schickt trotz Range-Anfrage alles: die angefangene Datei wird verworfen
        Downloader().download(listOf(file()), dir)
        assertContentEquals(data, File(dir, "model.gguf").readBytes())
    }

    @Test
    fun cancelKeepsThePartForLater() {
        var calls = 0
        assertFailsWith<DownloadCancelled> { Downloader().download(listOf(file()), dir, isCancelled = { ++calls > 2 }) }
        assertFalse(File(dir, "model.gguf").exists())
        assertTrue(File(dir, "model.gguf.part").exists())
    }

    @Test
    fun errorsAreReadable() {
        assertTrue("404" in assertFailsWith<IOException> { Downloader().download(listOf(file("/missing")), dir) }.message!!)
        val denied = assertFailsWith<IOException> { Downloader().download(listOf(file("/protected")), dir) }.message!!
        assertTrue("Zugangsschlüssel" in denied, denied)
        server.stop(0)
        assertTrue("Keine Verbindung" in assertFailsWith<IOException> { Downloader().download(listOf(file()), dir) }.message!!)
    }

    @Test
    fun severalFilesAndSubfoldersAreDownloadedInOrder() {
        val files = listOf(file("/a", "voice/a.onnx"), file("/b", "voice/espeak/b.bin"))
        Downloader().download(files, dir)
        assertTrue(File(dir, "voice/a.onnx").isFile && File(dir, "voice/espeak/b.bin").isFile)
    }

    @Test
    fun storageCheckCountsOnlyWhatIsMissing() {
        val downloader = Downloader()
        val files = listOf(RemoteFile("x", "done.bin", 1000), RemoteFile("y", "half.bin", 1000), RemoteFile("z", "new.bin", 1000))
        File(dir, "done.bin").writeBytes(ByteArray(1000))
        File(dir, "half.bin.part").writeBytes(ByteArray(400))
        assertEquals(1600L, downloader.remainingBytes(files, dir))
        val check = downloader.checkStorage(files, File(dir, "not/yet/there"), reserveBytes = 0)
        assertTrue(check.freeBytes > 0)
        assertTrue(StorageCheck(neededBytes = 1_000, freeBytes = 1_200, reserveBytes = 300).let { !it.ok && it.missingBytes == 100L })
        assertTrue(StorageCheck(neededBytes = 1_000, freeBytes = 1_300, reserveBytes = 300).ok)
    }

    @Test
    fun catalogIsSane() {
        for (e in ModelCatalog.all) {
            assertTrue(e.files.isNotEmpty() && e.bytes > 0, e.id)
            assertTrue(e.files.all { it.url.startsWith("https://huggingface.co/") && "resolve/main" in it.url }, e.id)
            assertTrue(e.files.map { it.name }.distinct().size == e.files.size, e.id)
        }
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.distinct().size)
        assertTrue(ModelCatalog.chat.first().recommended)
        assertEquals("model.gguf", ModelCatalog.vision.single().files[0].name) // so erwartet es das Bildmodell der App
        assertTrue(ModelCatalog.whisper.single().files.any { it.name.endsWith("tokens.txt") })
        assertNull(ModelCatalog.byId("gibt-es-nicht"))
    }
}
