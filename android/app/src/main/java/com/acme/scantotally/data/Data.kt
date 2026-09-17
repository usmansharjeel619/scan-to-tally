package com.acme.scantotally.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * On-device storage.
 *
 * Offline-first is not a nicety here. Warehouse Wi-Fi drops, and a scanner that
 * stops working when the signal does is a scanner nobody uses. Everything the
 * operator needs to scan and validate is cached locally, and every session is
 * durable from the moment it is created.
 */

/** Cached Tally stock item, so a scan resolves with no signal. */
@Entity(tableName = "stock_items")
data class StockItemEntity(
    @PrimaryKey val name: String,
    val alias: String = "",
    val partNo: String = "",
    val baseUnits: String = "",
    val hasBatches: Boolean = true,
)

/** Learned PID -> Tally item mapping, synced from the relay. */
@Entity(tableName = "pid_bindings")
data class PidBindingEntity(
    @PrimaryKey val pid: String,
    val stockItemName: String,
    val description: String = "",
)

/**
 * Cached batch balance: the hard ceiling for outgoing quantity.
 *
 * Carries syncedAt so the UI can show the operator how old the figure is. A
 * surprise rejection later makes sense to them if they could see it was stale.
 */
@Entity(tableName = "batch_balances", primaryKeys = ["stockItemName", "batchName", "godownName"])
data class BatchBalanceEntity(
    val stockItemName: String,
    val batchName: String,
    val godownName: String,
    val closingQty: Double,
    val unit: String = "",
    val syncedAt: Long = 0,
)

@Entity(tableName = "sales_orders")
data class SalesOrderEntity(
    @PrimaryKey val voucherNumber: String,
    val partyName: String = "",
    val orderDate: String = "",
)

@Entity(tableName = "sales_order_lines", primaryKeys = ["voucherNumber", "stockItemName"])
data class SalesOrderLineEntity(
    val voucherNumber: String,
    val stockItemName: String,
    val orderedQty: Double,
    val deliveredQty: Double,
    val unit: String = "",
)

/**
 * Every box received, on a rolling window.
 *
 * This is what makes historical duplicate detection work offline. The key is
 * (pid, boxSerial) -- never the serial alone, because Tally scopes a batch
 * under a stock item and the same box number on a different product is a
 * different box.
 */
@Entity(tableName = "received_boxes", primaryKeys = ["pid", "boxSerial"])
data class ReceivedBoxEntity(
    val pid: String,
    val boxSerial: String,
    val receivedAt: String = "",
)

/**
 * What a product IS, from the price list -- independent of whether Tally has an
 * item for it.
 *
 * Kept on the device because the prompt fires at the scan, and the scan may
 * happen in a warehouse with no signal. Without this the operator would have to
 * type a description that was already known.
 */
@Entity(tableName = "product_catalogue")
data class CatalogueEntity(
    @PrimaryKey val pid: String,
    val description: String,
    /** Other wordings seen for this part number, offered as choices. */
    val alternates: String = "",
)

/**
 * A scan session. The id is a UUID minted HERE, when the session opens, so it
 * survives being offline and is the same idempotency key end to end.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val kind: String,              // INCOMING | OUTGOING | STOCKCHECK
    val godown: String,
    val party: String = "",
    val salesOrder: String = "",
    val scope: String = "PARTIAL", // stock check only
    val state: String = "DRAFT",   // DRAFT|QUEUED|POSTING|POSTED|FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val submittedAt: Long? = null,
    val tallyVoucherId: String = "",
    val errorClass: String = "",
    val errorMessage: String = "",
    /** False until the relay has acknowledged this session exists. */
    val syncedToRelay: Boolean = false,
)

/** What a session actually holds, for a queue that says something useful. */
data class SessionSummary(
    val sessionId: String,
    val boxes: Int,
    val totalQty: Double,
)

/** Where else this box has been scanned, and what became of that session. */
data class BoxElsewhere(
    val lineId: Long,
    val sessionId: String,
    val state: String,
    val qty: Double,
    val createdAt: Long,
)

@Entity(tableName = "session_lines")
data class SessionLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val pid: String,
    val boxSerial: String,
    val qty: Double,
    val unit: String = "",
    val stockItemName: String = "",
    val description: String = "",
    val mfgDate: String? = null,
    /** The untouched scanner output. Kept forever; the only field that can
     *  explain a disputed scan months later. */
    val rawPayload: String = "",
    val symbology: String = "",
    val flags: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    /** False until the relay has this line. Drives the outbox. */
    val synced: Boolean = false,
)

@Dao
interface ScanDao {

    // --- master data ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<StockItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBindings(bindings: List<PidBindingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBalances(balances: List<BatchBalanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(orders: List<SalesOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderLines(lines: List<SalesOrderLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceivedBoxes(boxes: List<ReceivedBoxEntity>)

    @Query("DELETE FROM batch_balances")
    suspend fun clearBalances()

    @Query("DELETE FROM sales_orders")
    suspend fun clearOrders()

    @Query("DELETE FROM sales_order_lines")
    suspend fun clearOrderLines()

    @Query("SELECT * FROM pid_bindings WHERE pid = :pid LIMIT 1")
    suspend fun binding(pid: String): PidBindingEntity?

    @Query("SELECT * FROM stock_items WHERE name = :name LIMIT 1")
    suspend fun item(name: String): StockItemEntity?

    @Query("SELECT * FROM stock_items WHERE partNo = :pid OR name = :pid OR alias = :pid LIMIT 1")
    suspend fun itemByAnyCode(pid: String): StockItemEntity?

    @Query(
        """SELECT * FROM stock_items
           WHERE name LIKE '%' || :q || '%' OR partNo LIKE '%' || :q || '%'
              OR alias LIKE '%' || :q || '%'
           ORDER BY name LIMIT 50"""
    )
    suspend fun searchItems(q: String): List<StockItemEntity>

    @Query(
        """SELECT * FROM batch_balances
           WHERE stockItemName = :item AND batchName = :batch AND godownName = :godown LIMIT 1"""
    )
    suspend fun balance(item: String, batch: String, godown: String): BatchBalanceEntity?

    @Query("SELECT * FROM sales_orders ORDER BY orderDate DESC")
    fun ordersFlow(): Flow<List<SalesOrderEntity>>

    @Query("SELECT * FROM sales_order_lines WHERE voucherNumber = :order")
    suspend fun orderLines(order: String): List<SalesOrderLineEntity>

    @Query("SELECT * FROM received_boxes WHERE pid = :pid AND boxSerial = :serial LIMIT 1")
    suspend fun receivedBox(pid: String, serial: String): ReceivedBoxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogue(entries: List<CatalogueEntity>)

    @Query("SELECT * FROM product_catalogue WHERE pid = :pid LIMIT 1")
    suspend fun catalogue(pid: String): CatalogueEntity?

    // --- sessions ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun sessionFlow(id: String): Flow<SessionEntity?>

    /**
     * Every receipt this device has made, saved ones included.
     *
     * Deliberately not a queue of outstanding work. What an operator asks is
     * "did the delivery I scanned this morning go in?", and a list that drops
     * a receipt the moment it succeeds cannot answer that -- it only ever
     * shows problems, so it looks broken even when everything worked.
     *
     * A session exists from the moment someone taps Incoming, so an empty
     * draft is somebody who opened a screen and backed out. Those are the one
     * thing left out.
     */
    @Query(
        """SELECT s.* FROM sessions s
           WHERE s.state != 'DRAFT'
              OR EXISTS (SELECT 1 FROM session_lines l WHERE l.sessionId = s.id)
           ORDER BY s.createdAt DESC
           LIMIT 200"""
    )
    fun recentSessionsFlow(): Flow<List<SessionEntity>>

    /** Drafts nobody scanned into. Cleared so they never reach the list. */
    @Query(
        """DELETE FROM sessions
           WHERE state = 'DRAFT'
             AND NOT EXISTS (SELECT 1 FROM session_lines l WHERE l.sessionId = sessions.id)
             AND createdAt < :olderThan"""
    )
    suspend fun purgeEmptyDrafts(olderThan: Long)

    /**
     * Anything not yet in Tally. The operator must always be able to see this
     * count -- work disappearing silently destroys trust faster than slow work.
     */
    @Query("SELECT COUNT(*) FROM sessions WHERE state IN ('QUEUED','POSTING')")
    fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE state = 'FAILED'")
    fun failedCountFlow(): Flow<Int>

    @Query("SELECT * FROM sessions WHERE state = :state ORDER BY createdAt")
    suspend fun sessionsInState(state: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE state IN ('DRAFT','QUEUED','POSTING') ORDER BY createdAt")
    suspend fun sessionsToSync(): List<SessionEntity>

    @Query("UPDATE sessions SET state = :state WHERE id = :id")
    suspend fun setSessionState(id: String, state: String)

    @Query(
        """UPDATE sessions SET state = :state, tallyVoucherId = :voucherId,
           errorClass = :errorClass, errorMessage = :errorMessage WHERE id = :id"""
    )
    suspend fun setSessionResult(
        id: String, state: String, voucherId: String, errorClass: String, errorMessage: String,
    )

    // --- lines ---
    @Insert
    suspend fun insertLine(line: SessionLineEntity): Long

    @Query("SELECT * FROM session_lines WHERE sessionId = :sessionId ORDER BY id")
    fun linesFlow(sessionId: String): Flow<List<SessionLineEntity>>

    @Query("SELECT * FROM session_lines WHERE sessionId = :sessionId ORDER BY id")
    suspend fun lines(sessionId: String): List<SessionLineEntity>

    @Query(
        """SELECT * FROM session_lines
           WHERE sessionId = :sessionId AND pid = :pid AND boxSerial = :serial LIMIT 1"""
    )
    suspend fun lineForBox(sessionId: String, pid: String, serial: String): SessionLineEntity?

    @Query(
        """SELECT COALESCE(SUM(qty),0) FROM session_lines
           WHERE sessionId = :sessionId AND pid = :pid AND boxSerial = :serial AND id != :excludeId"""
    )
    suspend fun committedForBox(
        sessionId: String, pid: String, serial: String, excludeId: Long = -1,
    ): Double

    @Query(
        """SELECT COALESCE(SUM(qty),0) FROM session_lines
           WHERE sessionId = :sessionId AND stockItemName = :item AND id != :excludeId"""
    )
    suspend fun committedForItem(sessionId: String, item: String, excludeId: Long = -1): Double

    @Query("UPDATE session_lines SET qty = :qty, flags = :flags WHERE id = :id")
    suspend fun setLineQty(id: Long, qty: Double, flags: String)

    @Query("DELETE FROM session_lines WHERE id = :id")
    suspend fun deleteLine(id: Long)

    /**
     * Throws a receipt away, scans and all.
     *
     * Only ever reachable for one that never reached Tally. A receipt that
     * posted is a record of stock that moved, and deleting the phone's copy of
     * it would leave the operator unable to check what Tally was told.
     */
    @Query("DELETE FROM session_lines WHERE sessionId = :id")
    suspend fun deleteLinesFor(id: String)

    @Query("DELETE FROM sessions WHERE id = :id AND state != 'POSTED'")
    suspend fun deleteSession(id: String)

    @Query("SELECT id FROM sessions WHERE state != 'POSTED'")
    suspend fun unsavedSessionIds(): List<String>

    @Query("UPDATE session_lines SET synced = 1 WHERE sessionId = :sessionId")
    suspend fun markLinesSynced(sessionId: String)

    @Query("UPDATE session_lines SET synced = 1 WHERE id = :id")
    suspend fun markLineSynced(id: Long)

    /**
     * The same carton on a DIFFERENT session.
     *
     * (part number, box number) identifies one physical box, so this is always
     * the same box being counted twice -- and it stayed invisible for as long
     * as history was built only from sessions that had already posted. A
     * receipt sitting unsaved on the device is exactly the case where someone
     * scans the pallet again.
     */
    @Query(
        """SELECT l.id AS lineId, l.sessionId AS sessionId, s.state AS state,
                  l.qty AS qty, s.createdAt AS createdAt
             FROM session_lines l JOIN sessions s ON s.id = l.sessionId
            WHERE l.pid = :pid AND l.boxSerial = :serial AND l.sessionId != :exceptSession
            ORDER BY l.id DESC LIMIT 1"""
    )
    suspend fun boxInAnotherSession(pid: String, serial: String, exceptSession: String): BoxElsewhere?

    @Query("SELECT * FROM session_lines WHERE sessionId = :sessionId AND synced = 0 ORDER BY id")
    suspend fun unsyncedLines(sessionId: String): List<SessionLineEntity>

    @Query(
        """SELECT sessionId, COUNT(*) AS boxes, COALESCE(SUM(qty),0) AS totalQty
             FROM session_lines GROUP BY sessionId"""
    )
    fun lineSummariesFlow(): Flow<List<SessionSummary>>

    @Query("SELECT COUNT(*) FROM session_lines WHERE sessionId = :sessionId AND synced = 0")
    suspend fun unsyncedCount(sessionId: String): Int
}

@Database(
    entities = [
        StockItemEntity::class, PidBindingEntity::class, BatchBalanceEntity::class,
        SalesOrderEntity::class, SalesOrderLineEntity::class, ReceivedBoxEntity::class,
        SessionEntity::class, SessionLineEntity::class, CatalogueEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun dao(): ScanDao

    companion object {
        @Volatile private var instance: ScanDatabase? = null

        fun get(context: Context): ScanDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ScanDatabase::class.java, "scan-to-tally.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
