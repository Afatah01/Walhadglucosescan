package com.glucosescan.app.nfc

import com.glucosescan.app.core.Glucose
import com.glucosescan.app.core.ScanStatus
import com.glucosescan.app.libre.SensorType
import com.glucosescan.app.reference.ReferenceVectors.hex
import com.glucosescan.app.testing.TestSensors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibreNfcReaderTest {
    private val noon = 1_767_268_800_000L

    private fun tag(sample: TestSensors.Sample = TestSensors.example) = FakeLibre2Tag(sample.uid, sample.patchInfo, sample.encrypted.copyOf())

    private fun reader() = LibreNfcReader(now = { noon }, sleep = { })

    private fun scanner() = SensorScanner(reader(), now = { noon })

    @Test
    fun readsTheWholeSensor() {
        val tag = tag()
        val scan = reader().read(tag)

        assertArrayEquals(TestSensors.example.uid, scan.uid)
        assertArrayEquals(TestSensors.example.patchInfo, scan.patchInfo)
        assertArrayEquals(TestSensors.example.encrypted, scan.fram)
        assertEquals(noon, scan.scanTime)
    }

    @Test
    fun sendsExactlyTheExpectedCommands() {
        val tag = tag()
        reader().read(tag)

        val expected = ArrayList<String>()
        expected.add("02a107") // patch info
        for (block in 0 until 42 step 3) {
            expected.add("0223%02x02".format(block)) // 3 blocks
        }
        expected.add("02232a00") // block 42 alone
        assertEquals(expected, tag.commands)
        assertEquals(16, tag.commands.size)
    }

    @Test
    fun onlyReadCommandsAreEverSent() {
        // whatever goes wrong: the app must never send anything but "patch info" and "read block"
        val scenarios: List<(FakeLibre2Tag) -> Unit> = listOf(
            { },
            { it.supportsMultipleBlocks = false },
            { it.supportsReadMultipleCommand = false },
            { it.failEvery = 3 },
            { it.goneAfterCommands = 7 },
            { it.answersPatchInfo = false },
            { t -> t.beforeBlockRead = { block -> if (block == 20) t.fram[100] = (t.fram[100] + 1).toByte() } }
        )
        for ((number, prepare) in scenarios.withIndex()) {
            val tag = tag()
            prepare(tag)
            try {
                scanner().scan(tag)
            } catch (e: NfcReadException) {
                // fine
            }
            assertTrue("scenario $number", tag.forbiddenCommands.isEmpty())
            for (command in tag.commands) {
                val bytes = hex(command)
                val isPatchInfo = command == "02a107"
                val isReadMultiple = bytes.size == 4 && bytes[0].toInt() == 0x02 && bytes[1].toInt() == 0x23
                val isReadSingle = bytes.size == 3 && bytes[0].toInt() == 0x02 && bytes[1].toInt() == 0x20
                assertTrue("scenario $number: unexpected command $command", isPatchInfo || isReadMultiple || isReadSingle)
                if (isReadMultiple) {
                    val first = bytes[2].toInt() and 0xFF
                    val count = (bytes[3].toInt() and 0xFF) + 1
                    assertTrue("scenario $number: $command reads past block 42", first + count <= 43)
                }
                if (isReadSingle) {
                    assertTrue((bytes[2].toInt() and 0xFF) <= 42)
                }
            }
        }
    }

    @Test
    fun wholeScanGivesTheRightValue() {
        val result = scanner().scan(tag(TestSensors.example))
        assertEquals(ScanStatus.OK, result.status)
        assertEquals("3MH001HM1LM", result.sensor!!.serial)
        assertEquals(99, Glucose.toInteger(result.readings!!.trend.last().glucoseValue)!!.toInt())

        val result2 = scanner().scan(tag(TestSensors.example2))
        assertEquals(ScanStatus.OK, result2.status)
        assertEquals(95, Glucose.toInteger(result2.readings!!.trend.last().glucoseValue)!!.toInt())
    }

    @Test
    fun fallsBackToOneBlockPerCommand() {
        val tag = tag()
        tag.supportsMultipleBlocks = false
        val reader = reader()
        val scan = reader.read(tag)
        assertArrayEquals(TestSensors.example.encrypted, scan.fram)
        assertEquals(LibreNfcReader.Mode.MULTI_ONE, reader.mode)
        // patch info, one refused command, then 43 single blocks
        assertEquals(1 + 1 + 43, tag.commands.size)

        // the next read starts with the way that worked
        val tag2 = tag()
        tag2.supportsMultipleBlocks = false
        reader.read(tag2)
        assertEquals(1 + 43, tag2.commands.size)
    }

    @Test
    fun fallsBackToTheSingleBlockCommand() {
        val tag = tag()
        tag.supportsReadMultipleCommand = false
        val reader = reader()
        val scan = reader.read(tag)
        assertArrayEquals(TestSensors.example.encrypted, scan.fram)
        assertEquals(LibreNfcReader.Mode.SINGLE, reader.mode)
        assertEquals("022000", tag.commands[3])
        assertEquals("02202a", tag.commands.last())
    }

    @Test
    fun triesAgainWhenTheSensorDoesNotAnswerOnce() {
        val tag = tag()
        tag.failEvery = 3
        val scan = reader().read(tag)
        assertArrayEquals(TestSensors.example.encrypted, scan.fram)
        assertTrue(tag.commands.size > 16)

        val tag2 = tag()
        tag2.failNext = 4 // 5 tries are made
        assertArrayEquals(TestSensors.example.encrypted, reader().read(tag2).fram)
    }

    @Test
    fun reportsWhenThePhoneIsMovedAway() {
        for (after in listOf(0, 1, 5, 15)) {
            val tag = tag()
            tag.goneAfterCommands = after
            try {
                reader().read(tag)
                fail("no error after $after commands")
            } catch (e: NfcReadException) {
                assertEquals("after $after", NfcReadException.Reason.NO_ANSWER, e.reason)
            }
        }
    }

    @Test
    fun reportsATagThatIsNotASensor() {
        val tag = tag()
        tag.answersPatchInfo = false
        try {
            reader().read(tag)
            fail()
        } catch (e: NfcReadException) {
            assertEquals(NfcReadException.Reason.NOT_A_SENSOR, e.reason)
        }
        assertEquals("the memory is not touched", 1, tag.commands.size)

        val shortId = FakeLibre2Tag(TestSensors.example.uid, TestSensors.example.patchInfo, TestSensors.example.encrypted, reportedId = ByteArray(4))
        try {
            reader().read(shortId)
            fail()
        } catch (e: NfcReadException) {
            assertEquals(NfcReadException.Reason.NOT_A_SENSOR, e.reason)
        }
        assertTrue(shortId.commands.isEmpty())
    }

    @Test
    fun acceptsAnIdInEitherByteOrder() {
        val sample = TestSensors.example
        assertEquals(0xE0, sample.uid[7].toInt() and 0xFF)
        val reversed = FakeLibre2Tag(sample.uid, sample.patchInfo, sample.encrypted, reportedId = sample.uid.reversedArray())
        val result = scanner().scan(reversed)
        assertEquals(ScanStatus.OK, result.status)
        assertEquals("3MH001HM1LM", result.sensor!!.serial)
        assertEquals("02a107", reversed.commands[0])
    }

    @Test
    fun readsAgainWhenTheMemoryChangedDuringTheRead() {
        // The sensor writes a new value while the phone reads: the first read is a mix of old
        // and new and its checksum is wrong. The second read is clean.
        val sample = TestSensors.example
        val newer = sample.changed { TestSensors.setAge(it, 10321) }
        val tag = tag(sample)
        var switched = false
        tag.beforeBlockRead = { block ->
            if (block == 20 && !switched) {
                switched = true
                tag.fram = newer.encrypted.copyOf()
            }
        }
        val scanner = scanner()
        val result = scanner.scan(tag)
        assertEquals(ScanStatus.OK, result.status)
        assertEquals(10321, result.sensor!!.age)
        assertEquals(1 + 15 + 15, tag.commands.size)
        assertTrue(scanner.log.any { it.contains("reading again") })
    }

    @Test
    fun givesUpWhenTheMemoryStaysWrong() {
        val tag = tag()
        tag.fram[200] = (tag.fram[200] + 1).toByte()
        val result = scanner().scan(tag)
        assertEquals(ScanStatus.BAD_DATA, result.status)
        assertTrue(result.checksumFailed)
        assertEquals("read twice, not more", 1 + 15 + 15, tag.commands.size)
    }

    @Test
    fun doesNotReadTheMemoryOfOtherSensorTypes() {
        val libre1 = FakeLibre2Tag(TestSensors.example.uid, hex("df0000010102"), TestSensors.example.encrypted)
        val result = scanner().scan(libre1)
        assertEquals(ScanStatus.UNSUPPORTED_SENSOR, result.status)
        assertEquals(SensorType.LIBRE1, result.sensorType)
        assertEquals(listOf("02a107"), libre1.commands)

        val libre3 = FakeLibre2Tag(TestSensors.example.uid, ByteArray(28), TestSensors.example.encrypted)
        val result3 = scanner().scan(libre3)
        assertEquals(ScanStatus.UNSUPPORTED_SENSOR, result3.status)
        assertEquals(SensorType.LIBRE3, result3.sensorType)
        assertEquals(1, libre3.commands.size)
    }

    @Test
    fun statusByteWithErrorFlagIsAnError() {
        assertTrue(LibreNfcReader.isError(byteArrayOf(0x01, 0x0F)))
        assertTrue(LibreNfcReader.isError(byteArrayOf()))
        assertTrue(!LibreNfcReader.isError(byteArrayOf(0x00, 0x12)))
    }
}
