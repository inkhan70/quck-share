package com.quickshare.core.transfer

import com.quickshare.core.protocol.FileDigest
import com.quickshare.core.util.Checksums
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/**
 * End-to-end transfer tests over **real TCP sockets**.
 *
 * This is the substitute for the one thing that cannot be done in a build container: driving two
 * physical phones on the same Wi-Fi network. Everything below the radio is the real thing - a real
 * [TransferServer] bound to a real loopback port, driven by a real [TransferClient] that performs the
 * real handshake, the real pairing exchange, the real offer/accept exchange, real streamed file
 * bytes and a real SHA-256 verification - so the protocol, the framing, the buffering, the
 * cancellation paths and the checksum comparison are all exercised exactly as they run on device.
 *
 * What it does *not* cover is listed explicitly and is reported as not physically tested:
 * Wi-Fi discovery beacons (they need a real network interface), Android's SAF layer, and
 * backgrounding behaviour.
 */
class EndToEndTransferTest {

    private lateinit var serverRoot: File
    private lateinit var sourceRoot: File
    private var server: TransferServer? = null

    /** A free port, obtained by binding and immediately releasing, to avoid collisions. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Waits for a condition the *server* side produces, up to [timeoutMs].
     *
     * The client returns as soon as it has read the final ack, but the server still has to run its
     * post-transfer work - the per-sink `onSuccess` callbacks and `onFinished` - after writing that
     * ack. Asserting on those immediately is therefore a race, and it is a race that fails
     * intermittently rather than never, which is the worst kind. Polling for the condition makes the
     * assertion deterministic without weakening it: the timeout is generous, and a genuine failure to
     * report still fails the test.
     */
    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(10)
            true
        } ?: false

    @Before
    fun setUp() {
        serverRoot = File(System.getProperty("java.io.tmpdir"), "qs_e2e_server_${System.nanoTime()}").apply { mkdirs() }
        sourceRoot = File(System.getProperty("java.io.tmpdir"), "qs_e2e_source_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        try {
            server?.stop()
        } catch (e: Exception) {
            // Best effort: a test must not fail in teardown because a socket was already closed.
        }
        serverRoot.deleteRecursively()
        sourceRoot.deleteRecursively()
    }

    // -----------------------------------------------------------------------------------------
    // Test doubles that mirror what the Android layer provides, but against the filesystem.
    // -----------------------------------------------------------------------------------------

    /** Writes received bytes into a plain directory, hashing as it goes (as the SAF sink does). */
    private class FileSinkFactory(private val root: File) : SinkFactory {
        val written = mutableListOf<Pair<String, String>>() // name to sha256

        override fun create(info: TransferFileInfo): FileSink {
            // Duplicate resolution: the same numbering rule the SAF layer uses.
            val unique = com.quickshare.core.util.SafePaths.uniqueName(info.name, exists = { File(root, it).exists() })
            return object : FileSink {
                override val name: String = unique
                override val uri: String = File(root, unique).absolutePath
                private val target = File(root, unique)

                override fun openStream(): OutputStream =
                    BufferedOutputStream(FileOutputStream(target), 64 * 1024)

                override fun delete() {
                    target.delete()
                }

                override fun onSuccess(verified: Boolean) {
                    written += unique to Checksums.sha256Hex(target.inputStream())
                }
            }
        }
    }

    /** Reports effectively unlimited space so the guard never interferes. */
    private class RoomSpaceProvider : SpaceProvider {
        override fun usableBytes(destination: String?): Long = 64L * 1024 * 1024 * 1024
    }

    private class TightSpaceProvider(private val free: Long) : SpaceProvider {
        override fun usableBytes(destination: String?): Long = free
    }

    /** A source backed by a real file, mirroring a picked document. */
    private class LocalFileSource(private val file: File) : FileSource {
        override val name: String = file.name
        override val relativePath: String = file.name
        override val size: Long = file.length()
        override val mime: String = com.quickshare.core.util.MimeTypes.fromFileName(file.name)
        override fun openStream(): InputStream = file.inputStream()
    }

    /** Records what the server-side listener saw, and decides how to answer. */
    private class RecordingListener(
        private val acceptPairing: Boolean = true,
        private val acceptOffer: Boolean = true,
        private val destination: String? = "/tmp/dest",
    ) : TransferListener {
        val progressEvents = mutableListOf<TransferProgress>()
        val finishedRecords = mutableListOf<TransferRecord>()
        val filesCompleted = mutableListOf<String>()
        var pairingRequests = 0
        var offerRequests = 0
        var lastOffer: OfferSummary? = null

        override fun onProgress(progress: TransferProgress) {
            progressEvents += progress
        }

        override fun onFileCompleted(index: Int, total: Int, name: String) {
            filesCompleted += name
        }

        override fun onFinished(record: TransferRecord) {
            finishedRecords += record
        }

        override suspend fun onPairingRequested(peerName: String, code: String): Boolean {
            pairingRequests++
            return acceptPairing
        }

        override suspend fun onOfferReceived(peerName: String, offer: OfferSummary): Boolean {
            offerRequests++
            lastOffer = offer
            return acceptOffer
        }

        override suspend fun onDestinationRequired(): String? = destination
    }

    /** Starts a server on a loopback port and returns it with the port it actually bound. */
    private fun startServer(
        sinkFactory: SinkFactory,
        listener: TransferListener,
        spaceProvider: SpaceProvider = RoomSpaceProvider(),
    ): Pair<TransferServer, Int> {
        val port = freePort()
        val s = TransferServer(
            deviceId = "server-device",
            deviceName = "Receiver",
            appVersion = "1.0.0",
            port = port,
            sinkFactory = sinkFactory,
            spaceProvider = spaceProvider,
            listener = listener,
        )
        s.start()
        server = s
        return s to port
    }

    private fun client(localPort: Int) = TransferClient(
        deviceId = "client-device",
        deviceName = "Sender",
        localControlPort = localPort,
        appVersion = "1.0.0",
        connectTimeoutMs = 5_000,
        readTimeoutMs = 15_000,
    )

    private fun makeFile(name: String, size: Int): File {
        val file = File(sourceRoot, name)
        // Written in chunks rather than as one large array, so a big test file does not need a big
        // heap. The content is deterministic, so a checksum mismatch would be detected.
        FileOutputStream(file).use { out ->
            val chunk = ByteArray(256 * 1024) { ((it * 31 + 7) % 251).toByte() }
            var written = 0
            while (written < size) {
                val len = minOf(chunk.size, size - written)
                out.write(chunk, 0, len)
                written += len
            }
        }
        return file
    }

    // -----------------------------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a single file transfers and verifies over a real socket`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        val file = makeFile("hello.bin", 512 * 1024)
        val expectedHash = Checksums.sha256Hex(file.inputStream())

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-1",
            pairingCode = "123456",
        )

        assertEquals(TransferStatus.SUCCESS, record.status)
        assertTrue("the transfer should be reported as verified", record.verified)

        val received = File(serverRoot, "hello.bin")
        assertTrue("the received file should exist", received.exists())
        assertEquals("size must match exactly", file.length(), received.length())
        assertEquals("contents must be byte-identical", expectedHash, Checksums.sha256Hex(received.inputStream()))

        assertEquals("pairing should have been asked exactly once", 1, listener.pairingRequests)
        assertEquals("the offer should have been presented exactly once", 1, listener.offerRequests)
        assertEquals(listOf("hello.bin"), listener.filesCompleted)
        assertTrue(
            "the server must report the finished transfer",
            waitUntil { listener.finishedRecords.size == 1 },
        )
        assertEquals(TransferStatus.SUCCESS, listener.finishedRecords.single().status)
        assertTrue("the finished record must be marked verified", listener.finishedRecords.single().verified)
    }

    @Test
    fun `multiple files transfer in one session`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        val files = listOf(
            makeFile("first.txt", 1024),
            makeFile("second.bin", 300 * 1024),
            makeFile("third.dat", 64 * 1024),
        )
        val hashes = files.associate { it.name to Checksums.sha256Hex(it.inputStream()) }

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = files.map { LocalFileSource(it) },
            transferId = "t-e2e-multi",
            pairingCode = "654321",
        )

        assertEquals(TransferStatus.SUCCESS, record.status)
        assertEquals(3, listener.filesCompleted.size)
        for ((name, hash) in hashes) {
            val received = File(serverRoot, name)
            assertTrue("$name should have arrived", received.exists())
            assertEquals("$name must be byte-identical", hash, Checksums.sha256Hex(received.inputStream()))
        }
    }

    @Test
    fun `a large file streams without being buffered whole`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        // 24 MB, so the send and receive loops must iterate many times over the 64 KB buffer. That
        // is what makes this a streaming test rather than a single-write test: the memory ceiling is
        // one buffer regardless of this number.
        val file = makeFile("large.bin", 24 * 1024 * 1024)
        val expectedHash = Checksums.sha256Hex(file.inputStream())

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-large",
            pairingCode = "111222",
        )

        assertEquals(TransferStatus.SUCCESS, record.status)
        val received = File(serverRoot, "large.bin")
        assertEquals(24L * 1024 * 1024, received.length())
        assertEquals(expectedHash, Checksums.sha256Hex(received.inputStream()))

        // Progress reporting is throttled to ~5/sec by design, so the number of ticks depends on how
        // fast loopback happens to be and must not be asserted. What must hold is that progress was
        // reported at all (there is always a tick when a file completes) and that the byte counters
        // never go backwards - the property the progress bar depends on.
        assertTrue(
            "progress should have been reported, got ${listener.progressEvents.size}",
            listener.progressEvents.size >= 2,
        )
        var previous = -1L
        for (event in listener.progressEvents) {
            assertTrue("progress went backwards: $previous -> ${event.bytesTransferred}", event.bytesTransferred >= previous)
            previous = event.bytesTransferred
        }
        assertEquals(
            "the final tick must report the whole transfer",
            24L * 1024 * 1024,
            listener.progressEvents.last().bytesTransferred,
        )
    }

    @Test
    fun `an empty file transfers successfully`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        val file = File(sourceRoot, "empty.txt").apply { writeBytes(ByteArray(0)) }

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-empty",
            pairingCode = "333444",
        )

        assertEquals(TransferStatus.SUCCESS, record.status)
        val received = File(serverRoot, "empty.txt")
        assertTrue(received.exists())
        assertEquals(0L, received.length())
    }

    @Test
    fun `duplicate filenames are numbered rather than overwriting`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        // Pre-existing file, as if the user already received one with this name.
        File(serverRoot, "report.pdf").writeBytes("already here".toByteArray())

        val file = makeFile("report.pdf", 2048)
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-dupe",
            pairingCode = "555666",
        )

        assertEquals(TransferStatus.SUCCESS, record.status)
        assertEquals("the original must be untouched", "already here", File(serverRoot, "report.pdf").readText())
        assertTrue("the new file must be renamed", File(serverRoot, "report (1).pdf").exists())
    }

    // -----------------------------------------------------------------------------------------
    // Refusal and failure paths
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a declined pairing leaves no file behind`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener(acceptPairing = false)
        val (_, port) = startServer(sinkFactory, listener)

        val file = makeFile("nope.bin", 4096)
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-nopair",
            pairingCode = "999888",
        )

        assertFalse("a declined pairing must not succeed", record.status == TransferStatus.SUCCESS)
        assertEquals(1, listener.pairingRequests)
        assertEquals("no offer should be made after a refusal", 0, listener.offerRequests)
        assertTrue("nothing may be written", serverRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a declined offer leaves no file behind`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener(acceptOffer = false)
        val (_, port) = startServer(sinkFactory, listener)

        val file = makeFile("declined.bin", 8192)
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-nooffer",
            pairingCode = "121212",
        )

        assertFalse(record.status == TransferStatus.SUCCESS)
        assertEquals(1, listener.offerRequests)
        assertTrue("nothing may be written after a declined offer", serverRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `insufficient storage is refused before any byte is written`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        // Report 1 MB free for a 5 MB offer: the server must refuse up front.
        val (_, port) = startServer(sinkFactory, listener, spaceProvider = TightSpaceProvider(1024L * 1024))

        val file = makeFile("toobig.bin", 5 * 1024 * 1024)
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-nospace",
            pairingCode = "777888",
        )

        assertFalse("an impossible transfer must be refused", record.status == TransferStatus.SUCCESS)
        assertTrue("nothing may be written", serverRoot.listFiles().orEmpty().isEmpty())
        assertNotNull("the failure must carry an explanation", record.errorMessage)
    }

    @Test
    fun `a server that is not listening produces a clean failure, not a crash`() = runBlocking {
        // Port 1 is privileged and nothing is bound there.
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = 1,
            peerName = "Nobody",
            files = listOf(LocalFileSource(makeFile("x.bin", 128))),
            transferId = "t-e2e-nolisten",
            pairingCode = "000000",
        )

        assertEquals(TransferStatus.FAILED, record.status)
        assertNotNull("the user must be told why", record.errorMessage)
        assertTrue(
            "the message must be actionable, was '${record.errorMessage}'",
            record.errorMessage!!.isNotBlank(),
        )
    }

    @Test
    fun `an unreachable address fails cleanly rather than hanging`() = runBlocking {
        // 192.0.2.0/24 is TEST-NET-1 and is guaranteed not to be routed.
        val record = client(freePort()).send(
            peerAddress = "192.0.2.1",
            peerPort = 8988,
            peerName = "Nowhere",
            files = listOf(LocalFileSource(makeFile("y.bin", 128))),
            transferId = "t-e2e-unreachable",
            pairingCode = "000001",
        )
        // Whether the OS reports "no route" or times out is platform-dependent, so the status may
        // legitimately be FAILED or INTERRUPTED. What must always hold is that the call returns
        // promptly with an explanation instead of hanging.
        assertTrue(
            "expected a terminal failure, got ${record.status}",
            record.status.isTerminal && !record.status.isSuccess,
        )
        assertNotNull("the user must be told what happened", record.errorMessage)
    }

    @Test
    fun `a file removed after being offered fails without corrupting the destination`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        val file = makeFile("vanishing.bin", 256 * 1024)
        val source = LocalFileSource(file)
        // Delete the backing file after the source captured its metadata, simulating a document
        // that the user moved mid-transfer.
        file.delete()

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(source),
            transferId = "t-e2e-vanish",
            pairingCode = "444555",
        )

        assertFalse("the transfer cannot succeed", record.status == TransferStatus.SUCCESS)
        // The partial file must have been cleaned up, not left as a plausible-looking complete file.
        val leftovers = serverRoot.listFiles().orEmpty().filter { it.length() > 0 }
        assertTrue("a partial file was left behind: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun `cancelling mid transfer stops it and cleans up`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        // 96 MB written in chunks, so the transfer is still running when the cancel flag flips.
        // Loopback TCP is fast, so the file has to be comfortably larger than any single buffer
        // burst for this to be a mid-transfer cancel rather than a race.
        val file = makeFile("cancelme.bin", 96 * 1024 * 1024)
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        // Flip the flag from a separate thread, which is how the real cancel button behaves: the UI
        // thread sets a flag while the transfer thread is busy reading and writing.
        val canceller = Thread {
            Thread.sleep(60)
            cancelFlag.set(true)
        }
        canceller.isDaemon = true
        canceller.start()

        val progressSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(file)),
            transferId = "t-e2e-cancel",
            pairingCode = "606060",
            listener = object : TransferListener {
                override fun onProgress(progress: TransferProgress) { progressSeen.incrementAndGet() }
            },
            isCancelled = { cancelFlag.get() },
        )
        canceller.join(5_000)

        assertFalse("a cancelled transfer must not report success", record.status.isSuccess)
        assertTrue(
            "expected CANCELLED or INTERRUPTED, got ${record.status}",
            record.status == TransferStatus.CANCELLED || record.status == TransferStatus.INTERRUPTED,
        )
        // The partial file must not be left behind looking like a complete download.
        val partial = File(serverRoot, "cancelme.bin")
        if (partial.exists()) {
            assertTrue(
                "a partial file was left at full size: ${partial.length()}",
                partial.length() < file.length(),
            )
        }
    }

    @Test
    fun `concurrent transfers from two senders both complete`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        val a = makeFile("from_a.bin", 2 * 1024 * 1024)
        val b = makeFile("from_b.bin", 2 * 1024 * 1024)

        // Two clients in parallel against one server, exercising the accept loop's concurrency.
        val results = mutableListOf<TransferRecord>()
        val threads = listOf(
            Thread {
                runBlocking {
                    val r = client(freePort()).send(
                        peerAddress = "127.0.0.1",
                        peerPort = port,
                        peerName = "Receiver",
                        files = listOf(LocalFileSource(a)),
                        transferId = "t-e2e-par-a",
                        pairingCode = "112233",
                    )
                    synchronized(results) { results += r }
                }
            },
            Thread {
                runBlocking {
                    val r = client(freePort()).send(
                        peerAddress = "127.0.0.1",
                        peerPort = port,
                        peerName = "Receiver",
                        files = listOf(LocalFileSource(b)),
                        transferId = "t-e2e-par-b",
                        pairingCode = "445566",
                    )
                    synchronized(results) { results += r }
                }
            },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }

        assertEquals("both senders should have reported back", 2, results.size)
        assertTrue(
            "both transfers should succeed, got ${results.map { it.status }}",
            results.all { it.status == TransferStatus.SUCCESS },
        )
        assertTrue(File(serverRoot, "from_a.bin").exists())
        assertTrue(File(serverRoot, "from_b.bin").exists())
    }

    // -----------------------------------------------------------------------------------------
    // Verification logic, exercised directly
    // -----------------------------------------------------------------------------------------

    @Test
    fun `verify accepts a matching manifest`() {
        val data = "payload".toByteArray()
        val hash = Checksums.sha256Hex(data)
        val result = TransferServer.verify(
            expected = listOf(FileDigest("a.txt", hash)),
            actual = mapOf("a.txt" to hash),
        )
        assertTrue(result.verified)
        assertEquals(1, result.verifiedFiles)
        assertTrue(result.failedFiles.isEmpty())
    }

    @Test
    fun `verify rejects a mismatched digest`() {
        val result = TransferServer.verify(
            expected = listOf(FileDigest("a.txt", Checksums.sha256Hex("one".toByteArray()))),
            actual = mapOf("a.txt" to Checksums.sha256Hex("two".toByteArray())),
        )
        assertFalse("a corrupt file must not be reported as verified", result.verified)
        assertEquals(listOf("a.txt"), result.failedFiles)
    }

    @Test
    fun `verify rejects a file the sender never declared`() {
        // The two ends disagreeing about the contents of a transfer is itself a failure.
        val hash = Checksums.sha256Hex("x".toByteArray())
        val result = TransferServer.verify(
            expected = listOf(FileDigest("a.txt", hash)),
            actual = mapOf("a.txt" to hash, "smuggled.txt" to hash),
        )
        assertFalse(result.verified)
        assertTrue(result.failedFiles.contains("smuggled.txt"))
    }

    @Test
    fun `verify rejects a declared file that never arrived`() {
        val result = TransferServer.verify(
            expected = listOf(FileDigest("missing.txt", Checksums.sha256Hex("x".toByteArray()))),
            actual = emptyMap(),
        )
        assertFalse(result.verified)
        assertEquals(listOf("missing.txt"), result.failedFiles)
    }

    @Test
    fun `verify is case insensitive about hex digests`() {
        val hash = Checksums.sha256Hex("abc".toByteArray())
        val result = TransferServer.verify(
            expected = listOf(FileDigest("a", hash.uppercase())),
            actual = mapOf("a" to hash.lowercase()),
        )
        assertTrue(result.verified)
    }

    @Test
    fun `two files with the same name verify instead of failing the checksum`() = runBlocking {
        val sinkFactory = FileSinkFactory(serverRoot)
        val listener = RecordingListener()
        val (_, port) = startServer(sinkFactory, listener)

        // Same display name, different folders and different content - e.g. two "image.jpg" picks.
        val first = File(File(sourceRoot, "one").apply { mkdirs() }, "image.jpg")
            .apply { writeBytes(ByteArray(5_000) { 1 }) }
        val second = File(File(sourceRoot, "two").apply { mkdirs() }, "image.jpg")
            .apply { writeBytes(ByteArray(7_000) { 2 }) }

        val record = client(freePort()).send(
            peerAddress = "127.0.0.1",
            peerPort = port,
            peerName = "Receiver",
            files = listOf(LocalFileSource(first), LocalFileSource(second)),
            transferId = "t-e2e-dupnames",
            pairingCode = "424242",
        )

        assertEquals(record.errorMessage, TransferStatus.SUCCESS, record.status)
        assertTrue(record.verified)
        assertTrue(waitUntil { listener.finishedRecords.size == 1 })
        assertEquals(TransferStatus.SUCCESS, listener.finishedRecords.single().status)
        assertEquals(5_000L, File(serverRoot, "image.jpg").length())
        assertEquals(7_000L, File(serverRoot, "image (1).jpg").length())
    }

    @Test
    fun `start returns only once the port is accepting connections`() {
        val port = freePort()
        val s = TransferServer(
            deviceId = "server-device",
            deviceName = "Receiver",
            port = port,
            sinkFactory = FileSinkFactory(serverRoot),
            spaceProvider = RoomSpaceProvider(),
            listener = RecordingListener(),
        )
        s.start()
        server = s
        // Connecting immediately used to race the accept loop's own bind and get "connection refused".
        java.net.Socket("127.0.0.1", port).use { assertTrue(it.isConnected) }
    }

    @Test
    fun `a failed receive keeps the peer and file details in its record`() = runBlocking {
        val listener = RecordingListener()
        val (_, port) = startServer(FileSinkFactory(serverRoot), listener)

        // Offer 1 MiB, then hang up after sending only a little of it.
        val file = makeFile("big.bin", 1024 * 1024)
        val socket = java.net.Socket("127.0.0.1", port)
        val out = java.io.BufferedOutputStream(socket.getOutputStream())
        val input = java.io.BufferedInputStream(socket.getInputStream())
        out.write(com.quickshare.core.protocol.Protocol.HANDSHAKE_MAGIC.toByteArray(Charsets.US_ASCII))
        com.quickshare.core.protocol.FrameCodec.writeMessage(
            out,
            com.quickshare.core.protocol.Hello(
                com.quickshare.core.protocol.Protocol.VERSION, "Sender", "client-device", 1234, "", "1.0.0",
            ),
        )
        com.quickshare.core.protocol.FrameCodec.readMessage(input)
        com.quickshare.core.protocol.FrameCodec.writeMessage(
            out, com.quickshare.core.protocol.PairRequest("123456", "Sender", "client-device"),
        )
        com.quickshare.core.protocol.FrameCodec.readMessage(input)
        com.quickshare.core.protocol.FrameCodec.writeMessage(
            out,
            com.quickshare.core.protocol.Offer(
                "t-partial",
                listOf(com.quickshare.core.protocol.OfferedFile("big.bin", "big.bin", file.length(), "application/octet-stream")),
                file.length(), null,
            ),
        )
        com.quickshare.core.protocol.FrameCodec.readMessage(input)
        out.write(ByteArray(10_000)); out.flush()
        socket.close()

        assertTrue(waitUntil { listener.finishedRecords.isNotEmpty() })
        val record = listener.finishedRecords.single()
        assertFalse(record.status == TransferStatus.SUCCESS)
        assertEquals("Sender", record.peerName)
        assertEquals(listOf("big.bin"), record.fileNames)
        assertEquals(file.length(), record.totalBytes)
        assertTrue("the partial file must be removed", serverRoot.listFiles().orEmpty().isEmpty())
    }
}
