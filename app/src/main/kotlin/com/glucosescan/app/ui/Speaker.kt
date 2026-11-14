package com.glucosescan.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Says texts aloud with the voice of the phone (text-to-speech).
 *
 * The app writes English sentences. If the voice of the phone has no English, only the number
 * is said, in the language of the phone. If the phone has no voice at all, nothing is said and
 * [status] tells so.
 */
class Speaker private constructor(context: Context) {
    enum class Status {
        /** Nothing was said yet, or the voice is still starting. */
        STARTING,

        /** The voice speaks English: whole sentences are said. */
        ENGLISH,

        /** The voice has no English: only numbers are said. */
        NUMBERS_ONLY,

        /** The phone has no voice, or it could not be started. */
        NO_VOICE
    }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var waiting: Array<String>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var counter = 0

    @Volatile
    var status: Status = Status.STARTING
        private set

    /** Called on the main thread whenever [status] changes. */
    var onStatus: ((Status) -> Unit)? = null

    /**
     * Says [sentence]; or [short] if the voice has no English.
     * Something that is being said is cut off: the newest value matters.
     */
    fun say(sentence: String, short: String) {
        handler.post {
            lastRequest = sentence
            requests.add(sentence)
            val current = engine
            if (current == null) {
                waiting = arrayOf(sentence, short)
                start()
            } else if (status == Status.STARTING) {
                waiting = arrayOf(sentence, short)
            } else {
                speakNow(sentence, short)
            }
        }
    }

    /** Stops what is being said. */
    fun stop() {
        handler.post {
            waiting = null
            try {
                engine?.stop()
            } catch (e: RuntimeException) {
                // nothing to do
            }
            giveBackFocus()
        }
    }

    private fun start() {
        setStatus(Status.STARTING)
        try {
            engine = TextToSpeech(context) { result -> handler.post { started(result) } }
        } catch (e: RuntimeException) {
            engine = null
            setStatus(Status.NO_VOICE)
        }
    }

    private fun started(result: Int) {
        val current = engine
        if (current == null || result != TextToSpeech.SUCCESS) {
            shutDown()
            setStatus(Status.NO_VOICE)
            waiting = null
            return
        }
        var english = false
        try {
            for (locale in listOf(Locale.US, Locale.UK, Locale.ENGLISH)) {
                if (current.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    english = true
                    break
                }
            }
            if (!english) {
                current.setLanguage(Locale.getDefault())
            }
            current.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handler.post { if (utteranceId == "walhad-$counter") giveBackFocus() }
                }

                @Deprecated("needed for Android 6")
                override fun onError(utteranceId: String?) {
                    handler.post { giveBackFocus() }
                }
            })
        } catch (e: RuntimeException) {
            shutDown()
            setStatus(Status.NO_VOICE)
            waiting = null
            return
        }
        setStatus(if (english) Status.ENGLISH else Status.NUMBERS_ONLY)
        val texts = waiting
        waiting = null
        if (texts != null) {
            speakNow(texts[0], texts[1])
        }
    }

    private fun speakNow(sentence: String, short: String) {
        val current = engine ?: return
        val text = if (status == Status.ENGLISH) sentence else short
        counter++
        askForFocus()
        val result = try {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "walhad-$counter")
        } catch (e: RuntimeException) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            // the voice was stopped by the system: start it again with the next text
            giveBackFocus()
            shutDown()
            setStatus(Status.STARTING)
        }
    }

    /** Asks other sound (music) to get quieter while the app speaks. */
    @Suppress("DEPRECATION")
    private fun askForFocus() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    .build()
                focusRequest = request
                audio.requestAudioFocus(request)
            } else {
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: RuntimeException) {
            // speaking works without it
        }
    }

    @Suppress("DEPRECATION")
    private fun giveBackFocus() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                audio.abandonAudioFocus(null)
            }
        } catch (e: RuntimeException) {
            // nothing to do
        }
    }

    private fun shutDown() {
        try {
            engine?.shutdown()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        engine = null
    }

    private fun setStatus(newStatus: Status) {
        if (status != newStatus) {
            status = newStatus
            onStatus?.invoke(newStatus)
        }
    }

    companion object {
        // no leak: the speaker keeps the context of the app, not of a screen
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Speaker? = null

        fun get(context: Context): Speaker {
            return instance ?: synchronized(this) {
                instance ?: Speaker(context).also { instance = it }
            }
        }

        /** The last text that was asked to be said (the English sentence). For tests. */
        @Volatile
        var lastRequest: String? = null

        /** Every text that was asked to be said, oldest first. For tests. */
        val requests: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        /** Forgets the voice and what was said. For tests. */
        fun reset() {
            synchronized(this) {
                instance?.shutDown()
                instance = null
            }
            lastRequest = null
            requests.clear()
        }
    }
}
