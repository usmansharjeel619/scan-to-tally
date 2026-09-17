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

/**
 * Learned PID -> Tally item mapping.
 *
 * Normally synced from the relay, which got it from Tally. The exception is a
 * product the operator has just described: that is bound here immediately so
 * the rest of the pallet scans without prompting again, and it is a GUESS
 * until Tally confirms the item exists. Provisional says which is which.
 */
@Entity(tableName = "pid_bindings")
data class PidBindingEntity(
    @PrimaryKey val pid: String,
    val stockItemName: String,
    val description: String = "",
    val provisional: Boolean = false,
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
    /**
     * The Tally company this receipt was raised against.
     *
     * Scans are held on the phone until there is signal, and Tally can move to
     * another PC or open a different company in between. Without this, boxes
     * counted against one company would post into another, which is stock
     * appearing somewhere it never was.
     */
    val company: String = "",
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

/** What an order still needs, so the picker says it before anyone walks. */
data class OrderOutstanding(
    val voucherNumber: String,
    val items: Int,
    val pending: Double,
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

    /**
     * Tally is the only authority on what exists.
     *
     * Bindings and items used to be upserted and never cleared, so anything the
     * relay stopped sending stayed on the phone for ever -- including a product
     * whose creation Tally refused. The phone then resolved that part number
     * happily, never prompted for it again, and every receipt it appeared on
     * was refused with nothing on screen to explain it.
     *
     * Provisional bindings are spared: those are products described seconds ago
     * that Tally has not been asked about yet.
     */
    @Query("DELETE FROM pid_bindings WHERE provisional = 0")
    suspend fun clearConfirmedBindings()

    @Query("DELETE FROM pid_bindings WHERE pid = :pid")
    suspend fun deleteBinding(pid: String)

    @Query("SELECT * FROM pid_bindings WHERE provisional = 1")
    suspend fun provisionalBindings(): List<PidBindingEntity>

    @Query("DELETE FROM stock_items WHERE name NOT IN (SELECT stockItemName FROM pid_bindings WHERE provisional = 1)")
    suspend fun clearItemsExceptProvisional()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBalances(balances: List<BatchBalanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(orders: List<SalesOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderLines(lines: List<SalesOrderLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceivedBoxes(boxes: List<ReceivedBoxEntity>)

    @Query("DELETE FROM received_boxes")
    suspend fun clearReceivedBoxes()

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

    /** Every box of one product on one shelf. Drives the stock lookup. */
    @Query(
        """SELECT * FROM batch_balances
            WHERE stockItemName = :item AND godownName = :godown AND closingQty != 0
            ORDER BY batchName"""
    )
    suspend fun balancesFor(item: String, godown: String): List<BatchBalanceEntity>

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
     * Receipts only: incoming and outgoing. A stock take is a check, not a
     * receipt -- it is not a record of goods arriving or leaving, and listing
     * it here made the list longer without making it more useful.
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
           WHERE s.kind IN ('INCOMING', 'OUTGOING')
             AND (s.state != 'DRAFT'
                  OR EXISTS (SELECT 1 FROM session_lines l WHERE l.sessionId = s.id))
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
    @Query(
        """SELECT COUNT(*) FROM sessions
            WHERE state IN ('QUEUED','POSTING') AND kind IN ('INCOMING','OUTGOING')"""
    )
    fun pendingCountFlow(): Flow<Int>

    @Query(
        """SELECT COUNT(*) FROM sessions
            WHERE state = 'FAILED' AND kind IN ('INCOMING','OUTGOING')"""
    )
    fun failedCountFlow(): Flow<Int>

    /**
     * What this device has already despatched against an order and Tally has
     * not confirmed back yet.
     *
     * The delivered figure on an order is only as fresh as the last sync. Two
     * despatches inside that window both saw the whole order outstanding, and
     * six went out against an order for four.
     */
    @Query(
        """SELECT COALESCE(SUM(l.qty),0) FROM session_lines l
             JOIN sessions s ON s.id = l.sessionId
            WHERE s.kind = 'OUTGOING' AND s.salesOrder = :salesOrder
              AND l.stockItemName = :item
              AND s.id != :exceptSession
              AND s.state IN ('QUEUED','POSTING','POSTED')"""
    )
    suspend fun despatchedElsewhere(salesOrder: String, item: String, exceptSession: String): Double

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

    /**
     * Puts the product on every line waiting for it.
     *
     * The backfill used to rewrite the flags and leave stockItemName empty, so
     * a line the operator had just described still looked undescribed. Anything
     * asking "is this line still missing its product" -- including the prompt
     * that reopens for exactly that -- said yes for ever.
     */
    @Query(
        """UPDATE session_lines
              SET stockItemName = :name,
                  unit = :unit,
                  description = CASE WHEN description = '' THEN :description ELSE description END,
                  flags = TRIM(REPLACE(','||flags||',', ',UNRESOLVED_PID,', ',PROPOSED,'), ',')
            WHERE pid = :pid AND stockItemName = ''"""
    )
    suspend fun fillInProduct(pid: String, name: String, unit: String, description: String)

    /** Undoes that, for a product Tally turned out to refuse. */
    @Query(
        """UPDATE session_lines
              SET stockItemName = '',
                  flags = TRIM(REPLACE(','||flags||',', ',PROPOSED,', ',UNRESOLVED_PID,'), ',')
            WHERE pid = :pid AND sessionId IN (SELECT id FROM sessions WHERE state != 'POSTED')"""
    )
    suspend fun unfillProduct(pid: String)

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

    @Query(
        """SELECT voucherNumber,
                  COUNT(*) AS items,
                  COALESCE(SUM(MAX(orderedQty - deliveredQty, 0)), 0) AS pending
             FROM sales_order_lines
            GROUP BY voucherNumber"""
    )
    fun orderOutstandingFlow(): Flow<List<OrderOutstanding>>

    @Query("SELECT COUNT(*) FROM session_lines WHERE sessionId = :sessionId AND synced = 0")
    suspend fun unsyncedCount(sessionId: String): Int
}

@Database(
    entities = [
        StockItemEntity::class, PidBindingEntity::class, BatchBalanceEntity::class,
        SalesOrderEntity::class, SalesOrderLineEntity::class, ReceivedBoxEntity::class,
        SessionEntity::class, SessionLineEntity::class, CatalogueEntity::class,
    ],
    // Bumped to wipe the handset's local scan history for a clean test.
    // fallbackToDestructiveMigration drops the database on a version change,
    // and provisioning lives in DataStore, so the device stays set up.
    version = 5,
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
