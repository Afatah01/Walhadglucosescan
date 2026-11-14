package com.glucosescan.app.data

import com.glucosescan.app.core.Glucose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GlucoseStoreTest {
    private val minute = 60_000L
    private val noon = 1_767_268_800_000L
    private lateinit var store: GlucoseStore

    @Before
    fun setUp() {
        store = GlucoseStore(RuntimeEnvironment.getApplication(), null) // in memory
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun emptyStore() {
        assertNull(store.latest())
        assertEquals(0, store.count())
        assertTrue(store.since(0).isEmpty())
        assertTrue(store.newest(10).isEmpty())
    }

    @Test
    fun keepsEveryFieldOfAValue() {
        store.insert(
            listOf(
                Glucose(noon, 104, 1.25, true, "3MH001HM1LM"),
                Glucose(noon - 15 * minute, 620, null, false, null),
                Glucose(noon - 30 * minute, 20, -0.5, false, "X")
            )
        )
        assertEquals(3, store.count())

        val latest = store.latest()!!
        assertEquals(noon, latest.timestamp)
        assertEquals(104, latest.value)
        assertEquals(1.25, latest.minuteChange!!, 0.0)
        assertTrue(latest.fromTrend)
        assertEquals("3MH001HM1LM", latest.sensorSerial)

        val all = store.since(0)
        assertEquals(listOf(noon - 30 * minute, noon - 15 * minute, noon), all.map { it.timestamp })
        assertEquals(20, all[0].value)
        assertEquals(-0.5, all[0].minuteChange!!, 0.0)
        assertFalse(all[0].fromTrend)
        assertEquals(620, all[1].value)
        assertNull(all[1].minuteChange)
        assertNull(all[1].sensorSerial)
    }

    @Test
    fun aMinuteIsKeptOnlyOnce() {
        store.insert(listOf(Glucose(noon, 104, null, true)))
        store.insert(listOf(Glucose(noon, 150, null, true), Glucose(noon + minute, 106, null, true)))
        assertEquals(2, store.count())
        assertEquals("the first value of a minute stays", 104, store.since(0)[0].value)
    }

    @Test
    fun sinceAndNewest() {
        store.insert((0..9).map { Glucose(noon + it * minute, 100 + it, null, true) })
        assertEquals(listOf(105, 106, 107, 108, 109), store.since(noon + 5 * minute).map { it.value })
        assertEquals(listOf(109, 108, 107), store.newest(3).map { it.value })
    }

    @Test
    fun deleting() {
        store.insert((0..9).map { Glucose(noon + it * minute, 100 + it, null, true) })
        assertEquals(2, store.deleteAfter(noon + 7 * minute))
        assertEquals(107, store.latest()!!.value)
        assertEquals(3, store.deleteBefore(noon + 3 * minute))
        assertEquals(5, store.count())
        store.deleteAll()
        assertEquals(0, store.count())
        assertNull(store.latest())
    }

    @Test
    fun aChangeThatIsNotANumberIsSavedAsUnknown() {
        store.insert(listOf(Glucose(noon, 100, Double.NaN, true), Glucose(noon + minute, 100, Double.POSITIVE_INFINITY, true)))
        assertNull(store.since(0)[0].minuteChange)
        assertNull(store.since(0)[1].minuteChange)
    }

    @Test
    fun between() {
        store.insert((0..9).map { Glucose(noon + it * minute, 100 + it, null, true) })
        assertEquals("the start counts, the end does not", listOf(102, 103, 104), store.between(noon + 2 * minute, noon + 5 * minute).map { it.value })
        assertTrue(store.between(noon + 20 * minute, noon + 30 * minute).isEmpty())
    }

    /** A phone that has version 1.3 of the app keeps its readings. */
    @Test
    fun aDatabaseOfTheOlderAppIsKept() {
        val context = RuntimeEnvironment.getApplication()
        val name = "old-app.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        old.execSQL("CREATE TABLE glucose (timestamp INTEGER PRIMARY KEY, value INTEGER NOT NULL, minute_change REAL, kind INTEGER NOT NULL, sensor TEXT)")
        old.execSQL("INSERT INTO glucose (timestamp, value, minute_change, kind, sensor) VALUES ($noon, 104, 0.5, 0, 'S1')")
        old.version = 1
        old.close()

        val upgraded = GlucoseStore(context, name)
        try {
            assertEquals(1, upgraded.count())
            assertEquals(104, upgraded.latest()!!.value)
            assertEquals("S1", upgraded.latest()!!.sensorSerial)
            assertEquals(4, upgraded.readableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** A phone that has version 2.0 of the app keeps its readings; the diary of 2.0 goes away. */
    @Test
    fun theDiaryOfVersionTwoIsRemoved() {
        val context = RuntimeEnvironment.getApplication()
        val name = "app-2.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        old.execSQL("CREATE TABLE glucose (timestamp INTEGER PRIMARY KEY, value INTEGER NOT NULL, minute_change REAL, kind INTEGER NOT NULL, sensor TEXT)")
        old.execSQL("INSERT INTO glucose (timestamp, value, minute_change, kind, sensor) VALUES ($noon, 131, NULL, 1, 'S2')")
        old.execSQL("CREATE TABLE diary (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, kind INTEGER NOT NULL, amount REAL, note TEXT NOT NULL DEFAULT '')")
        old.execSQL("CREATE INDEX diary_time ON diary (timestamp)")
        old.execSQL("INSERT INTO diary (timestamp, kind, amount, note) VALUES ($noon, 1, 4.5, 'lunch')")
        old.version = 2
        old.close()

        val upgraded = GlucoseStore(context, name)
        try {
            assertEquals(1, upgraded.count())
            assertEquals(131, upgraded.latest()!!.value)
            assertFalse(upgraded.latest()!!.fromTrend)
            assertEquals(4, upgraded.readableDatabase.version)
            upgraded.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'diary%'", emptyArray()).use { cursor ->
                assertEquals("the diary table and its index are gone", 0, cursor.count)
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** A new installation has the table of the readings only. */
    @Test
    fun aNewDatabaseHasNoDiary() {
        store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'diary'", emptyArray()).use { cursor ->
            assertEquals(0, cursor.count)
        }
        assertEquals(4, store.readableDatabase.version)
    }

    // ------------------------------------------------------------ version 4: two sets of values, and the mark for the online copy

    /** A phone that has version 3.0 of the app keeps its readings and gets what version 4.0 needs. */
    @Test
    fun aDatabaseOfVersionThreeGetsTheNewParts() {
        val context = RuntimeEnvironment.getApplication()
        val name = "app-3.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        old.execSQL("CREATE TABLE glucose (timestamp INTEGER PRIMARY KEY, value INTEGER NOT NULL, minute_change REAL, kind INTEGER NOT NULL, sensor TEXT)")
        old.execSQL("INSERT INTO glucose (timestamp, value, minute_change, kind, sensor) VALUES ($noon, 104, 0.5, 0, 'S1')")
        old.execSQL("INSERT INTO glucose (timestamp, value, minute_change, kind, sensor) VALUES (${noon + 15 * 60_000L}, 111, NULL, 1, NULL)")
        old.version = 3
        old.close()

        val upgraded = GlucoseStore(context, name)
        try {
            assertEquals(4, upgraded.readableDatabase.version)
            val values = upgraded.since(0L)
            assertEquals(listOf(104, 111), values.map { it.value })
            assertEquals(0.5, values[0].minuteChange!!, 0.0001)
            assertTrue(values[0].fromTrend)
            assertEquals("S1", values[0].sensorSerial)
            assertNull(values[1].minuteChange)
            assertFalse(values[1].fromTrend)
            assertNull(values[1].sensorSerial)

            // the old values are not in an online copy yet; the set for LibreLinkUp is there and empty
            assertEquals(2, upgraded.table(ValueSource.SENSOR).unsyncedCount())
            assertEquals(0, upgraded.table(ValueSource.LINKUP).count())
            upgraded.table(ValueSource.LINKUP).insert(listOf(Glucose(noon, 99, null, true, "LibreLinkUp")))
            assertEquals(1, upgraded.table(ValueSource.LINKUP).count())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun theTwoSetsOfValuesAreKeptApart() {
        val sensor = store.table(ValueSource.SENSOR)
        val linkUp = store.table(ValueSource.LINKUP)
        // the same minute can hold a value in each set
        assertEquals(1, sensor.insert(listOf(Glucose(noon, 93, null, true, "S1"))))
        assertEquals(1, linkUp.insert(listOf(Glucose(noon, 104, null, true, "LibreLinkUp S1"))))
        assertEquals("a second value for a minute is left out, as before", 0, linkUp.insert(listOf(Glucose(noon + 20_000L, 105, null, true, "LibreLinkUp S1"))))
        assertEquals(93, sensor.latest()!!.value)
        assertEquals(104, linkUp.latest()!!.value)
        assertEquals(2, store.countAll())

        // the screens see the scanned values...
        assertEquals(ValueSource.SENSOR, store.view)
        assertEquals(93, store.latest()!!.value)
        assertEquals(1, store.count())
        assertEquals(listOf(93), store.between(noon, noon + 60_000L).map { it.value })

        // ...until LibreLinkUp is switched on; the store follows the setting by itself
        Settings(RuntimeEnvironment.getApplication()).linkUpEnabled = true
        assertEquals(ValueSource.LINKUP, store.view)
        assertEquals(104, store.latest()!!.value)
        assertEquals(listOf(104), store.newest(5).map { it.value })
        assertEquals(listOf(104), store.since(0L).map { it.value })

        // removing by time is about one set, tidying and "delete everything" about both
        assertEquals(1, linkUp.deleteAfter(noon - 1))
        assertEquals(1, sensor.count())
        linkUp.insert(listOf(Glucose(noon, 104, null, true, "LibreLinkUp S1")))
        assertEquals(2, store.deleteBefore(noon + 1))
        sensor.insert(listOf(Glucose(noon, 93, null, true, "S1")))
        linkUp.insert(listOf(Glucose(noon, 104, null, true, "LibreLinkUp S1")))
        store.deleteAll()
        assertEquals(0, store.countAll())
    }

    @Test
    fun theMarkForTheOnlineCopy() {
        val table = store.table(ValueSource.SENSOR)
        val values = (0 until 5).map { Glucose(noon + it * 60_000L, 100 + it, null, true, "S1") }
        table.insert(values)
        assertEquals(5, table.unsyncedCount())
        assertEquals("the oldest first", listOf(100, 101), table.unsynced(2).map { it.value })

        table.markSynced(listOf(noon, noon + 60_000L))
        assertEquals(3, table.unsyncedCount())
        assertEquals(listOf(102, 103, 104), table.unsynced(10).map { it.value })
        table.markSynced(emptyList())
        assertEquals(3, table.unsyncedCount())

        // values that come back from the copy need not go there again
        assertEquals(1, table.insert(listOf(Glucose(noon + 10 * 60_000L, 120, null, true, "S1")), synced = true))
        assertEquals(3, table.unsyncedCount())
        assertEquals(6, table.count())

        table.markAllUnsynced()
        assertEquals(6, table.unsyncedCount())
        assertEquals("the other set has its own marks", 0, store.table(ValueSource.LINKUP).unsyncedCount())
    }
}
