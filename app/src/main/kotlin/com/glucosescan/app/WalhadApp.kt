package com.glucosescan.app

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a crash is written down, so that it can be read instead of photographed.
 *
 * The default handler still runs afterwards: nothing here tries to swallow a crash or to keep a
 * broken app alive. It only copies the stack trace into the app's own folder, from where the
 * About screen will share it.
 *
 * The trace holds no glucose value and no name. It holds the name of the code that failed and
 * the line it failed on.
 */
object CrashLog {

    private const val NAME = "crash.log"

    /** The file the last crash was written to, or null if there has not been one. */
    fun file(context: Context): File = File(context.filesDir, NAME)

    /** What the last crash said, or null if the app has not crashed. */
    fun read(context: Context): String? {
        return try {
            val file = file(context)
            if (!file.exists()) null else file.readText().take(20_000).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // nothing useful to do: it is only a note
        }
    }

    /** Puts the recorder in place. Harmless to call twice. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, thread, error)
            // let Android show what it shows, and let the Play Console hear of it
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(app: Application, thread: Thread, error: Throwable) {
        try {
            val whenItHappened = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file(app).writeText(
                "Walhad Glucose Scan $whenItHappened\n" +
                    "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "thread: ${thread.name}\n\n" +
                    error.stackTraceToString()
            )
        } catch (e: Exception) {
            // if even this fails there is nothing left to try
        }
    }
}

/** The app as a whole. It exists so that a crash can be written down (see [CrashLog]). */
class WalhadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
