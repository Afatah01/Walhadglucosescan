package com.glucosescan.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChoiceTest {
    private fun voice(name: String, language: String = "en", quality: Int = 400, net: Boolean = false) =
        VoiceCandidate(name, language, quality, net)

    @Test
    fun aNameThatSaysMaleIsAMansVoice() {
        assertTrue(VoiceChoice.soundsMale("en-US-Male"))
        assertTrue(VoiceChoice.soundsMale("English (United States), Male"))
        assertTrue(VoiceChoice.soundsMale("en-gb-x-gbd#male"))
        assertTrue(VoiceChoice.soundsMale("en-us-x-sfg#male-2-local"))
    }

    @Test
    fun aNameThatSaysFemaleIsNot() {
        // "female" holds the word "male": the names of women must be put aside first
        assertFalse(VoiceChoice.soundsMale("en-US-Female"))
        assertFalse(VoiceChoice.soundsMale("English (United Kingdom), Female"))
        assertFalse(VoiceChoice.soundsMale("en-au-x-aub#female"))
        assertFalse(VoiceChoice.soundsMale("woman"))
        assertFalse(VoiceChoice.soundsMale("girl"))
    }

    @Test
    fun aNameThatSaysNothingAboutItIsNotAMansVoice() {
        assertFalse(VoiceChoice.soundsMale("en-US-language"))
        assertFalse(VoiceChoice.soundsMale("de-DE-Emma"))
    }

    @Test
    fun theBestMansVoiceOfTheLanguageIsTaken() {
        val voices = listOf(
            voice("en-US-language"),
            voice("en-US-Female", quality = 500),
            voice("en-US-Male", quality = 400),
            voice("en-GB-Male", quality = 450),
            voice("de-DE-Male", language = "de", quality = 500)
        )
        // the German voice is the best of them all, but it does not speak English
        assertEquals("en-GB-Male", VoiceChoice.pick(voices, "en")!!.name)
        assertEquals("de-DE-Male", VoiceChoice.pick(voices, "de")!!.name)
    }

    @Test
    fun aVoiceThatNeedsTheInternetIsLeftOut() {
        val voices = listOf(voice("en-US-Male", quality = 500, net = true), voice("en-US-Male-2"))
        assertEquals("en-US-Male-2", VoiceChoice.pick(voices, "en")!!.name)
    }

    @Test
    fun withoutAMansVoiceThereIsNone() {
        assertNull(VoiceChoice.pick(listOf(voice("en-US-Female")), "en"))
        assertNull(VoiceChoice.pick(null, "en"))
        assertNull(VoiceChoice.pick(emptyList(), "en"))
    }
}
