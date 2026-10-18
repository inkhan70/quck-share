package com.quickshare.core.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.util.Logs

/** Persistence port for transfer history. */
interface HistoryStore {
    fun load(): String?
    fun save(json: String)
}

class InMemoryHistoryStore(private var json: String? = null) : HistoryStore {
    override fun load(): String? = json
    override fun save(json: String) {
        this.json = json
    }
}

/**
 * Bounded, newest-first transfer history.
 *
 * The cap exists because history is written on every transfer and read on every screen open; an
 * unbounded list would slowly degrade both. Records are pruned oldest-first, and an entry that has
 * not reached a terminal state when it is pruned is treated as interrupted rather than left
 * "in progress" forever.
 */
class TransferHistoryRepository(
    private val store: HistoryStore,
    private val maxEntries: Int = 200,
    private val gson: Gson = Gson(),
) {

    private companion object {
        const val TAG = "HistoryRepository"
    }

    private val listType = object : TypeToken<List<TransferRecord>>() {}.type

    /** All records, newest first. Never throws: unreadable history yields an empty list. */
    fun all(): List<TransferRecord> {
        val raw = try {
            store.load()
        } catch (e: Exception) {
            Logs.w(TAG, "History read failed", e)
            return emptyList()
        }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val parsed: List<TransferRecord>? = gson.fromJson(raw, listType)
            parsed.orEmpty()
                .filterNotNull()
                .sortedByDescending { it.startedAtMs }
                .take(maxEntries)
        } catch (e: Exception) {
            Logs.w(TAG, "Stored history was unreadable and has been ignored", e)
            emptyList()
        }
    }

    /**
     * Inserts or replaces [record] by id.
     *
     * Replace-by-id matters: a transfer is recorded when it starts (so the UI can show it) and again
     * when it finishes, and those two writes must not produce two rows.
     */
    fun upsert(record: TransferRecord): List<TransferRecord> {
        val current = all().filterNot { it.transferId == record.transferId }
        val updated = (listOf(record) + current)
            .sortedByDescending { it.startedAtMs }
            .take(maxEntries)
        persist(updated)
        return updated
    }

    /**
     * Marks every record still flagged in-progress as interrupted.
     *
     * Call once at process start, when no transfer can possibly be running. A transfer is written to
     * history when it begins; if the app is killed mid-transfer nothing ever updates that row, and it
     * would otherwise show as "in progress" forever (and be neither retryable nor counted as failed).
     *
     * @return the number of records that were corrected.
     */
    fun markStaleInterrupted(
        message: String = "QuickShare was closed before this transfer finished.",
    ): Int {
        val current = all()
        val stale = current.count { it.status == com.quickshare.core.transfer.TransferStatus.IN_PROGRESS }
        if (stale == 0) return 0
        persist(
            current.map {
                if (it.status == com.quickshare.core.transfer.TransferStatus.IN_PROGRESS) {
                    it.copy(
                        status = com.quickshare.core.transfer.TransferStatus.INTERRUPTED,
                        finishedAtMs = if (it.finishedAtMs > 0) it.finishedAtMs else System.currentTimeMillis(),
                        errorMessage = it.errorMessage ?: message,
                    )
                } else {
                    it
                }
            },
        )
        return stale
    }

    fun remove(transferId: String): List<TransferRecord> {
        val updated = all().filterNot { it.transferId == transferId }
        persist(updated)
        return updated
    }

    fun clear(): List<TransferRecord> {
        persist(emptyList())
        return emptyList()
    }

    /** True when a send to [deviceId] has completed successfully before. */
    fun hasSuccessfulTransferWith(peerName: String): Boolean =
        all().any { it.peerName == peerName && it.status.isSuccess }

    fun stats(): HistoryStats {
        val records = all()
        return HistoryStats(
            totalTransfers = records.size,
            sentCount = records.count { it.direction == TransferDirection.SEND },
            receivedCount = records.count { it.direction == TransferDirection.RECEIVE },
            totalBytes = records.filter { it.status.isSuccess }.sumOf { it.totalBytes },
            failedCount = records.count { it.status.isTerminal && !it.status.isSuccess },
        )
    }

    private fun persist(records: List<TransferRecord>) {
        try {
            store.save(gson.toJson(records))
        } catch (e: Exception) {
            Logs.w(TAG, "Could not persist history", e)
        }
    }
}

data class HistoryStats(
    val totalTransfers: Int,
    val sentCount: Int,
    val receivedCount: Int,
    val totalBytes: Long,
    val failedCount: Int,
)
