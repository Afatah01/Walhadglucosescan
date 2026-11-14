package com.glucosescan.app.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.glucosescan.app.core.Glucose

/**
 * Where a value comes from. The two kinds are never mixed: the app's own formula and the
 * program of Abbott give different numbers for the same moment, and a chart that jumps
 * between the two would show ups and downs that never happened.
 */
enum class ValueSource(val code: Int, internal val table: String) {
    /** Read from the sensor by this app (scan or Bluetooth) and calculated with the open formula. */
    SENSOR(0, "glucose"),

    /** Fetched from LibreLinkUp over the internet: the numbers of Abbott's own program. */
    LINKUP(1, "linkup");

    companion object {
        fun of(code: Int): ValueSource? = values().firstOrNull { it.code == code }
    }
}

/** One saved value with what the online copy needs to know about it. */
class StoredValue(val source: ValueSource, val glucose: Glucose)

/** The values of one [ValueSource]. */
class GlucoseTable internal constructor(private val helper: SQLiteOpenHelper, val source: ValueSource) {
    private val table = source.table

    /**
     * Adds values. A value for a minute that is already there is left as it is.
     * @param synced true: the values come from the online copy and need not be sent there again
     * @return how many values were new
     */
    fun insert(values: List<Glucose>, synced: Boolean = false): Int {
        if (values.isEmpty()) {
            return 0
        }
        var added = 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (glucose in values) {
                val row = ContentValues()
                row.put("timestamp", glucose.timestamp)
                row.put("value", glucose.value)
                if (glucose.minuteChange != null && !glucose.minuteChange.isNaN() && !glucose.minuteChange.isInfinite()) {
                    row.put("minute_change", glucose.minuteChange)
                } else {
                    row.putNull("minute_change")
                }
                row.put("kind", if (glucose.fromTrend) 0 else 1)
                row.put("sensor", glucose.sensorSerial)
                row.put("synced", if (synced) 1 else 0)
                if (db.insertWithOnConflict(table, null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** The newest value, or null if there is none. */
    fun latest(): Glucose? {
        return query("SELECT $COLUMNS FROM $table ORDER BY timestamp DESC LIMIT 1", emptyArray()).firstOrNull()
    }

    /** All values from [from] on (milliseconds since 1970), oldest first. */
    fun since(from: Long): List<Glucose> {
        return query("SELECT $COLUMNS FROM $table WHERE timestamp >= ? ORDER BY timestamp ASC", arrayOf(from.toString()))
    }

    /** The newest [limit] values, newest first. */
    fun newest(limit: Int): List<Glucose> {
        return query("SELECT $COLUMNS FROM $table ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
    }

    /** All values from [from] (included) to [to] (not included), oldest first. */
    fun between(from: Long, to: Long): List<Glucose> {
        return query("SELECT $COLUMNS FROM $table WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC", arrayOf(from.toString(), to.toString()))
    }

    fun count(): Int = number("SELECT COUNT(*) FROM $table")

    /** Removes values with a time after [time]. They can only come from a wrong phone clock. */
    fun deleteAfter(time: Long): Int {
        return helper.writableDatabase.delete(table, "timestamp > ?", arrayOf(time.toString()))
    }

    fun deleteBefore(time: Long): Int {
        return helper.writableDatabase.delete(table, "timestamp < ?", arrayOf(time.toString()))
    }

    fun deleteAll() {
        helper.writableDatabase.delete(table, null, null)
    }

    // ------------------------------------------------------------------ the online copy

    /** The oldest [limit] values that are not in the online copy yet. */
    fun unsynced(limit: Int): List<Glucose> {
        return query("SELECT $COLUMNS FROM $table WHERE synced = 0 ORDER BY timestamp ASC LIMIT ?", arrayOf(limit.toString()))
    }

    fun unsyncedCount(): Int = number("SELECT COUNT(*) FROM $table WHERE synced = 0")

    /** Marks the values of these minutes as sent to the online copy. */
    fun markSynced(timestamps: List<Long>) {
        if (timestamps.isEmpty()) {
            return
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues()
            row.put("synced", 1)
            for (timestamp in timestamps) {
                db.update(table, row, "timestamp = ?", arrayOf(timestamp.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every value counts as not sent again: for a new online copy. */
    fun markAllUnsynced() {
        val row = ContentValues()
        row.put("synced", 0)
        helper.writableDatabase.update(table, row, null, null)
    }

    private fun number(sql: String): Int {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun query(sql: String, args: Array<String>): List<Glucose> {
        val result = ArrayList<Glucose>()
        helper.readableDatabase.rawQuery(sql, args).use { cursor: Cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Glucose(
                        timestamp = cursor.getLong(0),
                        value = cursor.getInt(1),
                        minuteChange = if (cursor.isNull(2)) null else cursor.getDouble(2),
                        fromTrend = cursor.getInt(3) == 0,
                        sensorSerial = if (cursor.isNull(4)) null else cursor.getString(4)
                    )
                )
            }
        }
        return result
    }

    private companion object {
        const val COLUMNS = "timestamp, value, minute_change, kind, sensor"
    }
}

/**
 * Keeps the glucose values in a small database on the phone.
 *
 * There are two sets of values (see [ValueSource]). The screens of the app work with one of
 * them, the one in [view]: the values from LibreLinkUp while that is switched on, otherwise
 * the values the app has read from the sensor itself. The plain functions of this class
 * ([latest], [between] and so on) are about that set.
 */
class GlucoseStore(context: Context, name: String? = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, DATABASE_VERSION) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sensorTable = GlucoseTable(this, ValueSource.SENSOR)
    private val linkUpTable = GlucoseTable(this, ValueSource.LINKUP)

    /** The values the screens show. It follows the setting, so it can never lag behind it. */
    val view: ValueSource
        get() = if (prefs.getBoolean(Settings.KEY_LINKUP_ENABLED, false)) ValueSource.LINKUP else ValueSource.SENSOR

    fun table(source: ValueSource): GlucoseTable = if (source == ValueSource.LINKUP) linkUpTable else sensorTable

    /** The values of [view]. */
    val shown: GlucoseTable
        get() = table(view)

    override fun onCreate(db: SQLiteDatabase) {
        for (source in ValueSource.values()) {
            createTable(db, source.table)
        }
    }

    private fun createTable(db: SQLiteDatabase, table: String) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $table (" +
                "timestamp INTEGER PRIMARY KEY, " + // milliseconds since 1970, start of a minute
                "value INTEGER NOT NULL, " + // mg/dL
                "minute_change REAL, " +
                "kind INTEGER NOT NULL, " + // 0 = one-minute value, 1 = 15-minute value
                "sensor TEXT, " +
                "synced INTEGER NOT NULL DEFAULT 0)" // 1 = the value is in the online copy
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 (app 1.0 to 1.3) had the glucose table only. Version 2 (app 2.0) added a
        // diary. Version 3 (app 3.0) has no diary any more: its table goes away with its entries.
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS diary")
        }
        // Version 4 (app 4.0): a mark for the online copy, and a second table for the values
        // from LibreLinkUp. The values that are there stay as they are.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE glucose ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            createTable(db, ValueSource.LINKUP.table)
        }
    }

    /** Adds values to [shown]. A value for a minute that is already there is left as it is. */
    fun insert(values: List<Glucose>) {
        shown.insert(values)
    }

    /** The newest value of [shown], or null if there is none. */
    fun latest(): Glucose? = shown.latest()

    /** All values of [shown] from [from] on (milliseconds since 1970), oldest first. */
    fun since(from: Long): List<Glucose> = shown.since(from)

    /** The newest [limit] values of [shown], newest first. */
    fun newest(limit: Int): List<Glucose> = shown.newest(limit)

    /** All values of [shown] from [from] (included) to [to] (not included), oldest first. */
    fun between(from: Long, to: Long): List<Glucose> = shown.between(from, to)

    fun count(): Int = shown.count()

    /** Removes values of [shown] with a time after [time]. */
    fun deleteAfter(time: Long): Int = shown.deleteAfter(time)

    /** Removes the values of both sets that are older than [time]. */
    fun deleteBefore(time: Long): Int = sensorTable.deleteBefore(time) + linkUpTable.deleteBefore(time)

    /** Removes every value of both sets from the phone. The online copy is not touched. */
    fun deleteAll() {
        sensorTable.deleteAll()
        linkUpTable.deleteAll()
    }

    /** How many values of both sets there are. */
    fun countAll(): Int = sensorTable.count() + linkUpTable.count()

    companion object {
        const val DATABASE_NAME = "glucose.db"
        const val DATABASE_VERSION = 4

        @Volatile
        private var instance: GlucoseStore? = null

        fun get(context: Context): GlucoseStore {
            return instance ?: synchronized(this) {
                instance ?: GlucoseStore(context).also { instance = it }
            }
        }

        /** Closes the shared store. The next [get] opens it again. Used by tests. */
        fun closeShared() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
