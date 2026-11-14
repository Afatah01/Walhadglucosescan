package com.glucosescan.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketAssemblerTest {
    private val message = ByteArray(46) { (it + 1).toByte() }
    private val first = message.copyOfRange(0, 20)
    private val second = message.copyOfRange(20, 38)
    private val third = message.copyOfRange(38, 46)

    @Test
    fun threePartsGiveOneMessage() {
        val assembler = PacketAssembler()
        assertNull(assembler.add(first, 1_000))
        assertEquals(20, assembler.pending)
        assertNull(assembler.add(second, 1_050))
        assertArrayEquals(message, assembler.add(third, 1_100))
        assertEquals(0, assembler.pending)

        // and the next one, a minute later
        assertNull(assembler.add(first, 61_000))
        assertNull(assembler.add(second, 61_050))
        assertArrayEquals(message, assembler.add(third, 61_100))
    }

    /** The original app would cut every later message wrongly. Here the half message is dropped. */
    @Test
    fun listeningFromTheMiddleOfAMessageLosesOnlyThatMessage() {
        val assembler = PacketAssembler()
        assertNull(assembler.add(second, 1_000))
        assertNull(assembler.add(third, 1_050))
        assertEquals(26, assembler.pending)

        assertNull(assembler.add(first, 61_000))
        assertEquals("the old parts are gone", 20, assembler.pending)
        assertNull(assembler.add(second, 61_050))
        assertArrayEquals(message, assembler.add(third, 61_100))
    }

    @Test
    fun aLostPartDoesNotSpoilTheNextMessage() {
        val assembler = PacketAssembler()
        assertNull(assembler.add(first, 1_000))
        assertNull(assembler.add(third, 1_100)) // the second part never came

        assertNull(assembler.add(first, 61_000))
        assertNull(assembler.add(second, 61_050))
        assertArrayEquals(message, assembler.add(third, 61_100))
    }

    @Test
    fun tooManyBytesStartAgainWithTheNewPart() {
        val assembler = PacketAssembler()
        assertNull(assembler.add(first, 1_000))
        assertNull(assembler.add(first, 1_010))
        assertNull(assembler.add(first, 1_020)) // 60 bytes: these parts do not belong together
        assertEquals(20, assembler.pending)
        assertNull(assembler.add(second, 1_030))
        assertArrayEquals(message, assembler.add(third, 1_040))
    }

    @Test
    fun emptyPartsAndAClockThatJumpsBack() {
        val assembler = PacketAssembler()
        assertNull(assembler.add(ByteArray(0), 1_000))
        assertEquals(0, assembler.pending)
        assertNull(assembler.add(first, 5_000))
        assertNull(assembler.add(first, 1_000)) // the clock went back: start again
        assertEquals(20, assembler.pending)
        assembler.reset()
        assertEquals(0, assembler.pending)
    }
}
