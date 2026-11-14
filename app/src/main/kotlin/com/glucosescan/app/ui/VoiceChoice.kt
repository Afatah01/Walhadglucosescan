package com.glucosescan.app.ui

import android.speech.tts.Voice
import java.util.Locale

/**
 * One voice of the phone, with the four things [VoiceChoice] looks at. A test can make these
 * by hand; the app makes them from the voices the phone offers.
 */
data class VoiceCandidate(val name: String, val language: String, val quality: Int, val needsInternet: Boolean) {
    constructor(voice: Voice) : this(voice.name, voice.locale.language, voice.quality, voice.isNetworkConnectionRequired)
}

/**
 * Which voice of the phone the app uses: **a man's voice**, so that the app always sounds the
 * same, wherever it is installed.
 *
 * A phone has many voices, and their names say which one they are in many different ways
 * ("en-US-language", "en-gb-x-gbd-local", "English (United States), Male"). The names are
 * read here, and the ones that stand for a woman are put aside first — "female" holds the
 * word "male", so that order matters.
 */
object VoiceChoice {
    /**
     * True if the name of a voice says that it is a man's voice. Names that say "female",
     * "woman" or "girl" are not, even though "female" holds the word "male".
     */
    fun soundsMale(name: String): Boolean {
        val words = name.lowercase(Locale.US)
        if (words.contains("female") || words.contains("woman") || words.contains("girl")) return false
        return words.contains("male") || words.contains("-man") || words.endsWith("-m") ||
            words.contains("#m") || words.contains("_m")
    }

    /**
     * The best man's voice for [language] out of [voices]: the one with the highest quality
     * that speaks that language and works without the internet. Null if there is none.
     */
    fun pick(voices: Collection<VoiceCandidate>?, language: String): VoiceCandidate? {
        val all = (voices ?: return null).filter { !it.needsInternet }
        val men = all.filter { soundsMale(it.name) }
        if (men.isEmpty()) {
            return null
        }
        val wanted = language.lowercase(Locale.US)
        val here = men.filter { it.language.lowercase(Locale.US) == wanted }
        return (here.ifEmpty { men }).maxByOrNull { it.quality }
    }
}
