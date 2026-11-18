package app.aichatmcp.core

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ein Ordner im Speicher, der sich wie ein Systemordner (Storage Access Framework) verhält: nur Schreiben/Anhängen, Umbenennen ohne Überschreiben. */
private class MemStore(private val freeSpace: Long = -1L) : FileStore {
    val files = linkedMapOf<String, ByteArray>()
    val times = mutableMapOf<String, Long>()
    private var clock = 1000L

    override val label = "mem"
    override fun size(name: String) = files[name]?.size?.toLong() ?: -1L

    override fun write(name: String, append: Boolean): OutputStream {
        val start = if (append) files[name] ?: ByteArray(0) else ByteArray(0)
        files[name] = start
        times[name] = clock++
        return object : ByteArrayOutputStream() {
            init { write(start) }
            override fun write(b: ByteArray, off: Int, len: Int) { super.write(b, off, len); files[name] = toByteArray() }
            override fun write(b: Int) { super.write(b); files[name] = toByteArray() }
        }
    }

    override fun read(name: String): InputStream? = files[name]?.let { ByteArrayInputStream(it) }
    override fun delete(name: String) = files.remove(name) != null
    override fun rename(from: String, to: String): Boolean {
        if (to in files) return false
        files[to] = files.remove(from) ?: return false
        times[to] = times.remove(from) ?: 0L
        return true
    }
    override fun list() = files.map { (n, b) -> StoredFile(n, b.size.toLong(), "mem://$n", times[n] ?: 0L) }
    override fun freeBytes() = freeSpace
    override fun sub(name: String, create: Boolean): FileStore? = null
}

class FileStoreTest {
    private val data = ByteArray(700_000) { (it * 17 % 253).toByte() }
    private var cutAfter = -1
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (range != null) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
            val length = data.size - start
            ex.sendResponseHeaders(if (range != null) 206 else 200, length.toLong())
            ex.responseBody.use { out ->
                if (cutAfter in 0 until length) { out.write(data, start, cutAfter); out.flush(); ex.close() } else out.write(data, start, length)
            }
        }
        start()
    }
    private val file get() = RemoteFile("http://127.0.0.1:${server.address.port}/m.gguf", "m.gguf", data.size.toLong())

    @Test
    fun downloadsIntoAStoreThatOnlyAppendsAndResumesAfterACut() {
        val store = MemStore()
        cutAfter = 300_000
        assertFailsWith<IOException> { Downloader().download(listOf(file), store) }
        val have = store.size("m.gguf.part")
        assertTrue(have in 1 until data.size)
        assertFalse(store.exists("m.gguf"))
        cutAfter = -1
        Downloader().download(listOf(file), store)
        assertContentEquals(data, store.files["m.gguf"])
        assertFalse(store.exists("m.gguf.part"))
        server.stop(0)
    }

    @Test
    fun remainingBytesAndUnknownFreeSpace() {
        val store = MemStore()
        store.write("m.gguf.part").use { it.write(ByteArray(1000)) }
        val check = Downloader().checkStorage(listOf(RemoteFile("http://x/m.gguf", "m.gguf", 5000)), store)
        assertEquals(4000L, check.neededBytes)
        assertFalse(check.known)
        assertTrue(check.ok && check.missingBytes == 0L) // Platz unbekannt: nicht blockieren
        val tight = Downloader().checkStorage(listOf(RemoteFile("http://x/m.gguf", "m.gguf", 5000)), MemStore(freeSpace = 1000))
        assertTrue(tight.known && !tight.ok)
        server.stop(0)
    }

    @Test
    fun copyKeepsNewerFilesOnRestoreAndAlwaysWritesOnBackup() {
        val backup = MemStore()
        val local = MemStore()
        local.write("a.json").use { it.write("new".toByteArray()) }
        local.write("b.json").use { it.write("b".toByteArray()) }
        assertEquals(2, StoreCopy.copy(local, backup, onlyNewer = false))
        // Wiederherstellen: a.json gibt es lokal schon und ist nicht älter -> bleibt, c.json fehlt -> kommt dazu
        backup.write("c.json").use { it.write("c".toByteArray()) }
        val restored = StoreCopy.copy(backup, local, onlyNewer = true)
        assertEquals(1, restored)
        assertEquals("c", String(local.files["c.json"]!!))
        assertEquals("new", String(local.files["a.json"]!!))
        assertEquals(1, StoreCopy.copy(local, MemStore(), onlyNewer = false) { it == "a.json" }) // Filter
        server.stop(0)
    }
}
