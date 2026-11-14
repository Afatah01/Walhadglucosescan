// Ported from Glucose Direct (MIT License, Copyright (c) 2023 Reimar Metzen):
// Library/Content/SensorType.swift, SensorFamily.swift, SensorRegion.swift, SensorState.swift.

package com.glucosescan.app.libre

/** The kind of sensor. It is found from the first bytes of the "patch info" answer. */
enum class SensorType(val label: String) {
    UNKNOWN("Unknown"),
    LIBRE1("Libre 1"),
    LIBRE2_EU("Libre 2 EU"),
    LIBRE2_CA("Libre 2 CA"),
    LIBRE2_US("Libre 2 US"),
    LIBRE3("Libre 3"),
    LIBRE_PRO_H("Libre Pro/H"),
    LIBRE_SENSE("Libre Sense"),
    LIBRE_US_14DAY("Libre US 14d");

    companion object {
        /** [patchInfo] must have 4 bytes or more. */
        fun of(patchInfo: ByteArray): SensorType {
            return when (patchInfo[0].toInt() and 0xFF) {
                0xDF -> LIBRE1
                0xA2 -> LIBRE1
                0xE5, 0xE6 -> LIBRE_US_14DAY
                0x70 -> LIBRE_PRO_H
                0x9D, 0xC5, 0xC6, 0x7F -> LIBRE2_EU
                0x76 -> when {
                    (patchInfo[3].toInt() and 0xFF) == 0x02 -> LIBRE2_US
                    (patchInfo[3].toInt() and 0xFF) == 0x04 -> LIBRE2_CA
                    ((patchInfo[2].toInt() and 0xFF) shr 4) == 7 -> LIBRE_SENSE
                    else -> UNKNOWN
                }
                else -> if (patchInfo.size > 6) LIBRE3 else UNKNOWN // Libre 3 answers with 28 or 35 bytes
            }
        }
    }
}

enum class SensorFamily(val rawValue: Int, val label: String) {
    UNKNOWN(-1, "Unknown"),
    LIBRE1(0, "Libre 1"),
    LIBRE_PRO(1, "Libre Pro/H"),
    LIBRE2(3, "Libre 2"),
    LIBRE_SENSE(7, "Libre Sense");

    companion object {
        fun of(patchInfo: ByteArray): SensorFamily {
            return when ((patchInfo[2].toInt() and 0xFF) shr 4) {
                0 -> LIBRE1
                1 -> LIBRE_PRO
                3 -> LIBRE2
                7 -> LIBRE_SENSE
                else -> UNKNOWN
            }
        }
    }
}

enum class SensorRegion(val label: String) {
    UNKNOWN("0 - Unknown"),
    EUROPEAN("1 - European"),
    USA("2 - USA"),
    AUSTRALIAN("4 - Australia/Canada"),
    EASTERN("8 - Eastern");

    companion object {
        fun of(region: Int): SensorRegion {
            return when (region) {
                1 -> EUROPEAN
                2 -> USA
                4 -> AUSTRALIAN
                8 -> EASTERN
                else -> UNKNOWN
            }
        }
    }
}

/** What the sensor says about itself (byte 4 of its memory). */
enum class SensorState {
    UNKNOWN,
    NOT_YET_STARTED,
    STARTING,
    READY,
    EXPIRED,
    SHUTDOWN,
    FAILURE;

    companion object {
        fun of(state: Int): SensorState {
            return when (state) {
                1 -> NOT_YET_STARTED
                2 -> STARTING
                3 -> READY
                4 -> EXPIRED
                5 -> SHUTDOWN
                6 -> FAILURE
                else -> UNKNOWN
            }
        }
    }
}
