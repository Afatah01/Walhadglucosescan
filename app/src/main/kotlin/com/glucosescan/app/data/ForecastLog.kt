package com.glucosescan.app.data

import android.database.sqlite.SQLiteDatabase
import com.glucosescan.app.core.Forecast
import com.glucosescan.app.core.Forecaster
import com.glucosescan.app.core.Horizons

/**
 * One stored prediction, kept so that the app can check its own guesses against what really
 * happened. Nothing here is shown as a measurement; these rows exist only to measure the
 * forecast. Stored in the table `forecast_log` (database version 5).
 */
class HorizonRecord(
    /** 15, 30 or 60. */
    val horizonMinutes: Int,
    /** The moment the prediction is for. */
    val atTime: Long,
    /** The prediction, mg/dL. */
    val valueMgdl: Int,
    /** 3 = high confidence, 2 = moderate, 1 = low. */
    val confidence: Int
)

/** One stored prediction, read back for copying to the online copy. */
class ForecastRecord(
    val id: Long,
    val computedAt: Long,
    val atTime: Long,
    val horizonMinutes: Int,
    val valueMgdl: Double,
    val confidence: Int,
    val actual: Double?,
    val error: Double?,
    val evaluatedAt: Long?,
    val inputValue: Double?,
    val inputTime: Long?,
    val model: String?,
    val freshnessMinutes: Int?,
    val band: String?,
    val ciHalfWidth: Double?
)

/** How well the stored predictions matched the readings that actually arrived. */
class ForecastAccuracy(
    /** How many predictions were checked (needed at least ten for the word to mean anything). */
    val count: Int,
    /** Mean absolute error, mg/dL. */
    val mae: Double,
    /** Root mean squared error, mg/dL. */
    val rmse: Double
)

/**
 * The forecast log: predictions are written when they are made, and checked when the reading
 * that landed inside the same ten-minute window arrives. MAE and RMSE over the stored rows are
 * the honest measure of the engine — the app never claims an accuracy it has not measured.
 */
object ForecastLog {
    const val TABLE = "forecast_log"

    /** Which model wrote these predictions. Bump the number when the model changes. */
    const val MODEL = "forecaster-1"

    /** Create the table (used by onCreate and onUpgrade of the store). */
    fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "computed_at INTEGER NOT NULL, " + // when the prediction was made
                "at_time INTEGER NOT NULL, " + // the moment it is for
                "horizon INTEGER NOT NULL, " + // 15, 30 or 60
                "value REAL NOT NULL, " + // mg/dL
                "confidence INTEGER NOT NULL, " + // 1 low, 2 moderate, 3 high
                "actual INTEGER, " + // the reading that arrived, mg/dL; null until checked
                "error REAL, " + // actual - predicted
                "evaluated_at INTEGER, " + // when it was checked
                "input_value REAL, " + // the newest value the model was built on, mg/dL
                "input_time INTEGER, " + // the moment of that value
                "model TEXT, " + // which model made this, e.g. "forecaster-1"
                "freshness_minutes INTEGER, " + // how many minutes old the newest input was when the prediction was made
                "band TEXT, " + // the band of the estimate (LOW, IN_RANGE, ...)
                "ci_half_width REAL, " + // the model's own residual spread, widened with the horizon; NOT a validated coverage guarantee
                "synced INTEGER NOT NULL DEFAULT 0)" // 0 until the online copy has it
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forecast_log_at ON $TABLE(at_time)")
    }

    /** Upgrade from database version 6: the columns the online copy needs, and the sync mark. */
    fun upgradeToV7(db: SQLiteDatabase) {
        for (column in listOf(
            "ALTER TABLE $TABLE ADD COLUMN input_value REAL",
            "ALTER TABLE $TABLE ADD COLUMN input_time INTEGER",
            "ALTER TABLE $TABLE ADD COLUMN model TEXT",
            "ALTER TABLE $TABLE ADD COLUMN freshness_minutes INTEGER",
            "ALTER TABLE $TABLE ADD COLUMN band TEXT",
            "ALTER TABLE $TABLE ADD COLUMN ci_half_width REAL",
            "ALTER TABLE $TABLE ADD COLUMN synced INTEGER NOT NULL DEFAULT 0"
        )) {
            db.execSQL(column)
        }
    }

    /**
     * Write the three horizon estimates for [forecast]. The caller throttles how often this
     * may happen. Returns the number of rows written.
     */
    fun record(store: GlucoseStore, forecast: Forecast, now: Long, mean: Double?, lowLimit: Int, highLimit: Int, inputValue: Double? = null, inputTime: Long? = null, freshnessMinutes: Int? = null): Int {
        val horizons = intArrayOf(15, Forecaster.HORIZON_MINUTES, Forecaster.FAR_HORIZON_MINUTES)
        val db = store.writableDatabase
        var written = 0
        db.beginTransaction()
        try {
            for (minutes in horizons) {
                val estimate = Horizons.at(forecast, now, minutes, mean, lowLimit, highLimit)
                val confidence = when (estimate.confidence.name) {
                    "HIGH" -> 3
                    "MODERATE" -> 2
                    else -> 1
                }
                // The "interval" is the model's own residual spread, widened the way the model widens it
                // with the horizon. It is not a validated coverage guarantee; it exists so that the online
                // copy can show how sure the model was, never to present a probability of being on target.
                val ci = forecast.scatter * (1.0 + Forecaster.UNCERTAINTY_GROWTH * minutes / 15.0)
                db.execSQL(
                    "INSERT INTO $TABLE(computed_at, at_time, horizon, value, confidence, input_value, input_time, model, freshness_minutes, band, ci_half_width, synced) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",
                    arrayOf<Any?>(
                        now, estimate.at, minutes, estimate.value.toDouble(), confidence,
                        inputValue, inputTime ?: forecast.time, MODEL, freshnessMinutes, estimate.band.name, ci
                    )
                )
                written++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return written
    }

    /**
     * Check the stored predictions against a reading that has just arrived: every unchecked
     * prediction whose moment lies at or before [readingTime] (within the last ten minutes)
     * is scored against it. Returns how many rows were checked.
     */
    fun evaluate(db: SQLiteDatabase, readingTime: Long, actualMgdl: Int): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE actual IS NULL AND at_time <= ? AND at_time >= ?",
            arrayOf(readingTime.toString(), (readingTime - 10 * 60_000L).toString())
        )
        val pending = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (pending == 0) {
            return 0
        }
        db.execSQL(
            "UPDATE $TABLE SET actual = ?, error = ? - value, evaluated_at = ?, synced = 0 " +
                "WHERE actual IS NULL AND at_time <= ? AND at_time >= ?",
            arrayOf<Any>(actualMgdl, actualMgdl, readingTime, readingTime, readingTime - 10 * 60_000L)
        )
        return pending
    }

    /** MAE and RMSE of checked predictions made since [since], or null if none were checked yet. */
    fun accuracy(store: GlucoseStore, since: Long): ForecastAccuracy? {
        val db = store.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*), AVG(ABS(error)), AVG(error * error) FROM $TABLE " +
                "WHERE evaluated_at IS NOT NULL AND evaluated_at >= ?",
            arrayOf(since.toString())
        )
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0) || it.getInt(0) == 0) {
                return null
            }
            val count = it.getInt(0)
            val mae = it.getDouble(1)
            val mse = it.getDouble(2)
            return ForecastAccuracy(count, mae, Math.sqrt(mse))
        }
    }

    /** The predictions that the online copy does not have yet (oldest first). */
    fun unsynced(store: GlucoseStore, limit: Int): List<ForecastRecord> {
        val db = store.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, computed_at, at_time, horizon, value, confidence, actual, error, evaluated_at, " +
                "input_value, input_time, model, freshness_minutes, band, ci_half_width " +
                "FROM $TABLE WHERE synced = 0 ORDER BY computed_at ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val list = ArrayList<ForecastRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(ForecastRecord(
                    id = it.getLong(0),
                    computedAt = it.getLong(1),
                    atTime = it.getLong(2),
                    horizonMinutes = it.getInt(3),
                    valueMgdl = it.getDouble(4),
                    confidence = it.getInt(5),
                    actual = if (it.isNull(6)) null else it.getDouble(6),
                    error = if (it.isNull(7)) null else it.getDouble(7),
                    evaluatedAt = if (it.isNull(8)) null else it.getLong(8),
                    inputValue = if (it.isNull(9)) null else it.getDouble(9),
                    inputTime = if (it.isNull(10)) null else it.getLong(10),
                    model = if (it.isNull(11)) null else it.getString(11),
                    freshnessMinutes = if (it.isNull(12)) null else it.getInt(12),
                    band = if (it.isNull(13)) null else it.getString(13),
                    ciHalfWidth = if (it.isNull(14)) null else it.getDouble(14)
                ))
            }
        }
        return list
    }

    /** Acknowledge only the exact version uploaded; a concurrent evaluation must stay pending. */
    fun markSynced(db: SQLiteDatabase, records: List<ForecastRecord>) {
        db.beginTransaction()
        try {
            for (record in records) {
                db.execSQL(
                    "UPDATE $TABLE SET synced = 1 WHERE id = ? AND evaluated_at IS ?",
                    arrayOf<Any?>(record.id, record.evaluatedAt)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** How many predictions are waiting for the online copy. */
    fun pendingSyncCount(store: GlucoseStore): Int {
        val cursor = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE synced = 0", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * MAE and RMSE per horizon (15, 30, 60 minutes), over the predictions checked since
     * [since]. Each horizon is measured on its own rows; a horizon with no checked
     * prediction is simply absent from the map.
     */
    fun accuracyByHorizon(store: GlucoseStore, since: Long): Map<Int, ForecastAccuracy> {
        val db = store.readableDatabase
        val map = LinkedHashMap<Int, ForecastAccuracy>()
        for (horizon in intArrayOf(15, Forecaster.HORIZON_MINUTES, Forecaster.FAR_HORIZON_MINUTES)) {
            val cursor = db.rawQuery(
                "SELECT COUNT(*), AVG(ABS(error)), AVG(error * error) FROM $TABLE " +
                    "WHERE evaluated_at IS NOT NULL AND evaluated_at >= ? AND horizon = ?",
                arrayOf(since.toString(), horizon.toString())
            )
            cursor.use {
                if (it.moveToFirst() && !it.isNull(0) && it.getInt(0) > 0) {
                    map[horizon] = ForecastAccuracy(it.getInt(0), it.getDouble(1), Math.sqrt(it.getDouble(2)))
                }
            }
        }
        return map
    }

    /** Drop the predictions older than [before]; the log must not grow without bound. */
    fun deleteBefore(db: SQLiteDatabase, before: Long): Int {
        return db.delete(TABLE, "computed_at < ?", arrayOf(before.toString()))
    }
}
