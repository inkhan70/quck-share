package com.quickshare.core.data

import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.transfer.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings and history persistence tests, including the corrupt-data path. */
class SettingsRepositoryTest {

    private fun repo(store: SettingsStore, corrupt: MutableList<String> = mutableListOf()) =
        SettingsRepository(store, onCorrupt = { corrupt += it })

    @Test
    fun `first run yields defaults`() {
        val settings = repo(InMemorySettingsStore()).load("dev-1", "My Phone")
        assertEquals("My Phone", settings.deviceName)
        assertEquals("dev-1", settings.deviceId)
        assertEquals(true, settings.receiveEnabled)
        assertEquals(true, settings.requirePairingCode)
        assertEquals(Settings.DARK_MODE_SYSTEM, settings.darkMode)
    }

    @Test
    fun `a saved setting round trips`() {
        val store = InMemorySettingsStore()
        val r = repo(store)
        val original = Settings.defaults("dev-1", "Phone").copy(
            deviceName = "Tablet",
            darkMode = Settings.DARK_MODE_DARK,
            receiveEnabled = false,
            requirePairingCode = false,
            saveLocation = "content://tree/1",
            saveLocationLabel = "Downloads/QuickShare",
        )
        r.save(original)
        val loaded = r.load("dev-1", "Phone")
        assertEquals("Tablet", loaded.deviceName)
        assertEquals(Settings.DARK_MODE_DARK, loaded.darkMode)
        assertEquals(false, loaded.receiveEnabled)
        assertEquals(false, loaded.requirePairingCode)
        assertEquals("content://tree/1", loaded.saveLocation)
        assertEquals("Downloads/QuickShare", loaded.saveLocationLabel)
    }

    @Test
    fun `an unknown theme falls back to system rather than being trusted`() {
        val store = InMemorySettingsStore(Settings.defaults("d", "P").copy(darkMode = "neon"))
        val loaded = repo(store).load("d", "P")
        assertEquals(Settings.DARK_MODE_SYSTEM, loaded.darkMode)
    }

    @Test
    fun `an absent field is filled with its default instead of failing the load`() {
        // Simulates a settings file written by an older version that had no darkMode key.
        val partial = Settings(deviceName = "Old Phone", deviceId = "d").copy(darkMode = null)
        val loaded = repo(InMemorySettingsStore(partial)).load("d", "Fallback")
        assertEquals("Old Phone", loaded.deviceName)
        assertEquals(Settings.DARK_MODE_SYSTEM, loaded.darkMode)
        assertEquals(true, loaded.receiveEnabled)
    }

    @Test
    fun `a store that throws is reported and defaults are used`() {
        val failing = object : SettingsStore {
            // The store's load() is declared nullable, so the throwing stub must match that
            // signature or the anonymous object does not implement the interface at all.
            override fun load(): Settings? = throw IllegalStateException("boom")
            override fun save(settings: Settings) = Unit
        }
        val problems = mutableListOf<String>()
        val loaded = repo(failing, problems).load("d", "P")
        assertEquals("P", loaded.deviceName)
        assertTrue("the user must be told the settings could not be read", problems.isNotEmpty())
    }

    @Test
    fun `a failed write is reported but does not throw`() {
        val failing = object : SettingsStore {
            override fun load(): Settings? = null
            override fun save(settings: Settings) = throw IllegalStateException("disk full")
        }
        val problems = mutableListOf<String>()
        repo(failing, problems).save(Settings.defaults("d", "P"))
        assertTrue(problems.isNotEmpty())
    }

    @Test
    fun `a blank device name is replaced by the fallback`() {
        val loaded = repo(InMemorySettingsStore(Settings(deviceName = "   ", deviceId = "d")))
            .load("d", "Fallback Name")
        assertEquals("Fallback Name", loaded.deviceName)
    }

    @Test
    fun `a device name longer than the protocol maximum is clamped`() {
        val loaded = repo(InMemorySettingsStore(Settings(deviceName = "x".repeat(500), deviceId = "d")))
            .load("d", "P")
        assertEquals(com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH, loaded.deviceName!!.length)
    }

    @Test
    fun `remembering a device is idempotent`() {
        val settings = Settings.defaults("d", "P")
        val once = settings.rememberDevice("peer-1")
        val twice = once.rememberDevice("peer-1")
        assertEquals(listOf("peer-1"), twice.knownDeviceIds)
    }

    @Test
    fun `forgetting a device removes only that device`() {
        val settings = Settings.defaults("d", "P")
            .rememberDevice("a").rememberDevice("b").forgetDevice("a")
        assertEquals(listOf("b"), settings.knownDeviceIds)
    }

    @Test
    fun `json round trip survives the persistence boundary`() {
        val r = repo(InMemorySettingsStore())
        val original = Settings.defaults("d", "Phone").copy(darkMode = Settings.DARK_MODE_LIGHT)
        val restored = r.fromJson(r.toJson(original))
        assertNotNull(restored)
        assertEquals(Settings.DARK_MODE_LIGHT, restored!!.darkMode)
    }

    @Test
    fun `unparseable json is rejected and reported without throwing`() {
        val problems = mutableListOf<String>()
        val parsed = repo(InMemorySettingsStore(), problems).fromJson("}{ not json")
        assertNull(parsed)
        assertTrue(problems.isNotEmpty())
    }

    @Test
    fun `blank json is treated as no settings at all`() {
        val problems = mutableListOf<String>()
        assertNull(repo(InMemorySettingsStore(), problems).fromJson("   "))
        assertNull(repo(InMemorySettingsStore(), problems).fromJson(null))
    }
}

/** Transfer history behaviour, including the corrupt history and pruning paths. */
class TransferHistoryRepositoryTest {

    private fun record(
        id: String,
        startedAt: Long,
        status: TransferStatus = TransferStatus.SUCCESS,
        direction: TransferDirection = TransferDirection.SEND,
        peer: String = "Pixel",
        bytes: Long = 1000,
    ) = TransferRecord(
        transferId = id,
        direction = direction,
        peerName = peer,
        peerAddress = "192.168.1.5",
        fileNames = listOf("a.txt"),
        fileCount = 1,
        totalBytes = bytes,
        startedAtMs = startedAt,
        status = status,
    )

    @Test
    fun `an empty store yields an empty history`() {
        assertTrue(TransferHistoryRepository(InMemoryHistoryStore()).all().isEmpty())
    }

    @Test
    fun `records come back newest first`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000))
        repo.upsert(record("b", 3000))
        repo.upsert(record("c", 2000))
        assertEquals(listOf("b", "c", "a"), repo.all().map { it.transferId })
    }

    @Test
    fun `upserting the same id replaces rather than duplicating`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000, status = TransferStatus.IN_PROGRESS))
        repo.upsert(record("a", 1000, status = TransferStatus.SUCCESS))
        assertEquals(1, repo.all().size)
        assertEquals(TransferStatus.SUCCESS, repo.all().first().status)
    }

    @Test
    fun `history is pruned to the configured maximum, oldest first`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore(), maxEntries = 3)
        for (i in 1..10) repo.upsert(record("t$i", i * 1000L))
        val ids = repo.all().map { it.transferId }
        assertEquals(3, ids.size)
        assertEquals(listOf("t10", "t9", "t8"), ids)
    }

    @Test
    fun `corrupt history is ignored instead of crashing`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore("}{ this is not json"))
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `a store that throws yields an empty history`() {
        val failing = object : HistoryStore {
            override fun load(): String = throw IllegalStateException("boom")
            override fun save(json: String) = Unit
        }
        assertTrue(TransferHistoryRepository(failing).all().isEmpty())
    }

    @Test
    fun `a failed history write does not propagate to the caller`() {
        val failing = object : HistoryStore {
            override fun load(): String? = null
            override fun save(json: String) = throw IllegalStateException("disk full")
        }
        // Must not throw: a full disk must not crash a completed transfer's UI update.
        TransferHistoryRepository(failing).upsert(record("a", 1))
    }

    @Test
    fun `removing a record leaves the rest intact`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000))
        repo.upsert(record("b", 2000))
        repo.remove("a")
        assertEquals(listOf("b"), repo.all().map { it.transferId })
    }

    @Test
    fun `clearing empties the history`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000))
        repo.clear()
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `a peer counts as known only after a successful transfer`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000, status = TransferStatus.FAILED, peer = "Pixel"))
        assertFalse(repo.hasSuccessfulTransferWith("Pixel"))
        repo.upsert(record("b", 2000, status = TransferStatus.SUCCESS, peer = "Pixel"))
        assertTrue(repo.hasSuccessfulTransferWith("Pixel"))
        assertFalse(repo.hasSuccessfulTransferWith("Someone else"))
    }

    @Test
    fun `stats count directions and only successful bytes`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(record("a", 1000, status = TransferStatus.SUCCESS, direction = TransferDirection.SEND, bytes = 500))
        repo.upsert(record("b", 2000, status = TransferStatus.SUCCESS, direction = TransferDirection.RECEIVE, bytes = 700))
        repo.upsert(record("c", 3000, status = TransferStatus.FAILED, direction = TransferDirection.SEND, bytes = 9000))
        val stats = repo.stats()
        assertEquals(3, stats.totalTransfers)
        assertEquals(2, stats.sentCount)
        assertEquals(1, stats.receivedCount)
        assertEquals(1, stats.failedCount)
        assertEquals(1200L, stats.totalBytes)
    }
}
