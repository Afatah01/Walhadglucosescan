package com.glucosescan.app.ui

/** The time of the phone. Tests put a fixed time here. */
object AppClock {
    @Volatile
    var source: () -> Long = { System.currentTimeMillis() }

    /** Milliseconds since 1970. */
    fun now(): Long = source()
}
