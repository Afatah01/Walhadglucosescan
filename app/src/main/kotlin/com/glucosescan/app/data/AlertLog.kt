package com.glucosescan.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** One persistent alert event, as stored in the app's own database. */
class AlertRecord(
    val id: Long,
    val eventId: String,
    val kind: String,
    val valueMgdl: Int?,
    val measuredAt: Long,
    val at: Long,
    val status: String,
    val detail: String?,
    val synced: Boolean
)

/** One persistent sync event, as stored in the app's own database. */
class SyncRecord(
    val id: Long,
    val clientId: String,
    val at: Long,
    val kind: String,
    val ok: Boolean,
    val detail: String?,
    val synced: Boolean
)

/**
 * The persistent records of the alert engine and of the sync.
 *
 * These tables are the app's audit trail: when an alert fired, about which reading, whether the
 * notification could be posted, and when the last sync happened. They live in glucose.db, are
 * written on every event, and are copied to the user's online copy (if switched on) so they
 * survive the phone. Rows are only ever inserted and marked copied, never rewritten.
 */
object AlertLog {

    const val TABLE_ALERTS = "alert_events"
    const val TABLE_SYNCS = "sync_events"

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_ALERTS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "event_id TEXT NOT NULL UNIQUE, " + // kind + measured reading time; see AlertEngine.eventId
                "kind TEXT NOT NULL, " +            // VERY_LOW, LOW, HIGH, VERY_HIGH, RAPID_*, NO_DATA, TEST
                "value_mgdl INTEGER, " +            // null when the alert was about missing data, or a test
                "measured_at INTEGER NOT NULL, " +  // the reading the alert is about (ms since 1970)
                "at INTEGER NOT NULL, " +           // when the alert fired (ms since 1970)
                "status TEXT NOT NULL, " +          // posted | failed | simulated
                "detail TEXT, " +
                "synced INTEGER NOT NULL DEFAULT 0)" // 1 = copied to the online copy
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_SYNCS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "client_id TEXT NOT NULL UNIQUE, " + // random id; copies to the online copy without duplicates
                "at INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, " +             // sync | backup | workmanager
                "ok INTEGER NOT NULL, " +
                "detail TEXT, " +
                "synced INTEGER NOT NULL DEFAULT 0)"
        )
    }

    /**
     * Writes an alert event. Returns false when the exact alert was already recorded
     * (IGNORE on event_id) — a duplicate evaluation then changes nothing.
     */
    fun recordAlert(db: SQLiteDatabase, eventId: String, kind: String, valueMgdl: Int?, measuredAt: Long, at: Long, status: String, detail: String?): Boolean {
        val row = ContentValues()
        row.put("event_id", eventId)
        row.put("kind", kind)
        if (valueMgdl != null) {
            row.put("value_mgdl", valueMgdl)
        } else {
            row.putNull("value_mgdl")
        }
        row.put("measured_at", measuredAt)
        row.put("at", at)
        row.put("status", status)
        putOrNull(row, "detail", detail?.take(500))
        row.put("synced", 0)
        return db.insertWithOnConflict(TABLE_ALERTS, null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Writes a sync event. */
    fun recordSync(db: SQLiteDatabase, clientId: String, kind: String, ok: Boolean, detail: String?, at: Long) {
        val row = ContentValues()
        row.put("client_id", clientId)
        row.put("at", at)
        row.put("kind", kind)
        row.put("ok", if (ok) 1 else 0)
        putOrNull(row, "detail", detail?.take(500))
        row.put("synced", 0)
        db.insertWithOnConflict(TABLE_SYNCS, null, row, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun latestAlert(db: SQLiteDatabase): AlertRecord? =
        alertRows(db.rawQuery("SELECT * FROM $TABLE_ALERTS ORDER BY at DESC, id DESC LIMIT 1", null)).firstOrNull()

    fun recentAlerts(db: SQLiteDatabase, limit: Int): List<AlertRecord> =
        alertRows(db.rawQuery("SELECT * FROM $TABLE_ALERTS ORDER BY at DESC, id DESC LIMIT ?", arrayOf(limit.toString())))

    fun latestSync(db: SQLiteDatabase): SyncRecord? =
        syncRows(db.rawQuery("SELECT * FROM $TABLE_SYNCS ORDER BY at DESC, id DESC LIMIT 1", null)).firstOrNull()

    fun recentSyncs(db: SQLiteDatabase, limit: Int): List<SyncRecord> =
        syncRows(db.rawQuery("SELECT * FROM $TABLE_SYNCS ORDER BY at DESC, id DESC LIMIT ?", arrayOf(limit.toString())))

    fun unsyncedAlerts(db: SQLiteDatabase, limit: Int): List<AlertRecord> =
        alertRows(db.rawQuery("SELECT * FROM $TABLE_ALERTS WHERE synced = 0 ORDER BY id LIMIT ?", arrayOf(limit.toString())))

    fun unsyncedSyncs(db: SQLiteDatabase, limit: Int): List<SyncRecord> =
        syncRows(db.rawQuery("SELECT * FROM $TABLE_SYNCS WHERE synced = 0 ORDER BY id LIMIT ?", arrayOf(limit.toString())))

    fun markAlertsSynced(db: SQLiteDatabase, ids: List<Long>) {
        if (ids.isEmpty()) {
            return
        }
        val row = ContentValues()
        row.put("synced", 1)
        db.update(TABLE_ALERTS, row, "id IN (${ids.joinToString(",")})", null)
    }

    fun markSyncsSynced(db: SQLiteDatabase, ids: List<Long>) {
        if (ids.isEmpty()) {
            return
        }
        val row = ContentValues()
        row.put("synced", 1)
        db.update(TABLE_SYNCS, row, "id IN (${ids.joinToString(",")})", null)
    }

    private fun putOrNull(row: ContentValues, key: String, value: String?) {
        if (value != null && value.isNotBlank()) {
            row.put(key, value)
        } else {
            row.putNull(key)
        }
    }

    private fun alertRows(cursor: Cursor): List<AlertRecord> {
        val list = ArrayList<AlertRecord>()
        try {
            val id = cursor.getColumnIndexOrThrow("id")
            val eventId = cursor.getColumnIndexOrThrow("event_id")
            val kind = cursor.getColumnIndexOrThrow("kind")
            val value = cursor.getColumnIndexOrThrow("value_mgdl")
            val measuredAt = cursor.getColumnIndexOrThrow("measured_at")
            val at = cursor.getColumnIndexOrThrow("at")
            val status = cursor.getColumnIndexOrThrow("status")
            val detail = cursor.getColumnIndexOrThrow("detail")
            val synced = cursor.getColumnIndexOrThrow("synced")
            while (cursor.moveToNext()) {
                list.add(
                    AlertRecord(
                        cursor.getLong(id),
                        cursor.getString(eventId),
                        cursor.getString(kind),
                        if (cursor.isNull(value)) null else cursor.getInt(value),
                        cursor.getLong(measuredAt),
                        cursor.getLong(at),
                        cursor.getString(status),
                        if (cursor.isNull(detail)) null else cursor.getString(detail),
                        cursor.getInt(synced) == 1
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return list
    }

    private fun syncRows(cursor: Cursor): List<SyncRecord> {
        val list = ArrayList<SyncRecord>()
        try {
            val id = cursor.getColumnIndexOrThrow("id")
            val clientId = cursor.getColumnIndexOrThrow("client_id")
            val at = cursor.getColumnIndexOrThrow("at")
            val kind = cursor.getColumnIndexOrThrow("kind")
            val ok = cursor.getColumnIndexOrThrow("ok")
            val detail = cursor.getColumnIndexOrThrow("detail")
            val synced = cursor.getColumnIndexOrThrow("synced")
            while (cursor.moveToNext()) {
                list.add(
                    SyncRecord(
                        cursor.getLong(id),
                        cursor.getString(clientId),
                        cursor.getLong(at),
                        cursor.getString(kind),
                        cursor.getInt(ok) == 1,
                        if (cursor.isNull(detail)) null else cursor.getString(detail),
                        cursor.getInt(synced) == 1
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return list
    }
}
