# Reference results from the original app

The Android code in `app/src/main/kotlin/com/glucosescan/app/libre/` does the same work as
the Swift code of Glucose Direct: it unscrambles the sensor memory, checks it, and turns the
raw numbers into glucose values. Since version 2.0 it also builds the command that tells the
sensor to send over Bluetooth, the "unlock" bytes for the Bluetooth connection, and it
unscrambles and reads the Bluetooth messages. A mistake there would give wrong glucose
values, so the Kotlin code is not trusted on its own. It is compared with the original.

## How it works

1. `run.sh` downloads Glucose Direct at one fixed commit
   (`ffe5500a010f0e14adef0653047c9d9f8cf550d7`, branch `release`).
2. It compiles the original Swift files that hold the logic, **unchanged**, for Linux.
   - `Shims.swift` stands in for the few helpers that live in files which need Apple-only
     frameworks.
   - `Hooks.swift` and `HooksCalibration.swift` are appended to a *copy* of two original
     files, only to make private helpers and number tables reachable.
   - `LibreNFC.swift` needs Apple's NFC framework and cannot be compiled on Linux. `run.sh`
     copies the one function `nfcCommand` and the two small types it uses out of it,
     text for text, and stops if it does not find them.
   - `main.swift` feeds many inputs into the original code and writes down what comes out.
3. The result is `app/src/test/resources/reference/reference_vectors.txt`
   (13,807 records, about 3.4 MB).
4. The unit test `PortEqualsOriginalTest` gives the same inputs to the Kotlin code and
   requires exactly the same output on every line.

| Lines | What is compared |
|---|---|
| `CRYPTO`, `DECRYPT` | unscrambling the sensor memory |
| `CRC`, `SAMPLECRC` | the checksums |
| `BITS` | reading bit fields |
| `TYPE` | which sensor type the first bytes mean |
| `CALPARAM`, `TABLE`, `CALIBRATE` | factory calibration, with every row of the tables |
| `SENSOR`, `PARSE`, `RECORDS` | sensor information and the stored measurements |
| `READING`, `CHANGE`, `SLOPE` | glucose values, change per minute, arrow |
| `SAMPLE` | the two real scans (below) |
| `USEFUL`, `NFCCMD` | the NFC command "enable streaming" (version 2.0) |
| `UNLOCK` | the bytes that open the Bluetooth connection, for many counter values |
| `BLEDECRYPT` | unscrambling a Bluetooth message, also damaged and too-short ones |
| `BLEPARSE` | the values and the sensor age inside a Bluetooth message |

Only the bytes and the numbers are compared. How Android's Bluetooth is used (searching,
connecting, the order of the steps) is new code. It follows the order of the original, but
it could not be compared by a machine, and it was not tried with a real sensor.

Numbers with decimals are stored as their exact 64-bit pattern, so "the same" means
the same down to the last bit.

## Run it again

You only need this if you change the fixed commit or the test inputs.

    bash reference/run.sh

It needs `git` and a Swift compiler for Linux (https://www.swift.org/install/linux/;
Swift 6.0.3 was used). Set `SWIFTC` if `swiftc` is somewhere else. The run prints the
commit and the SHA-256 of the result file. With commit `ffe5500` the SHA-256 is
`2ac8caf7fa54ca9b45241aa33d229c401439e4f5a46c9847730a0541553afbcf`.

After a new run, also run `python3 tools/generate_calibration_tables.py` if the
calibration tables of the original changed, and then the unit tests.

## The two real scans

`samples/libre2_examples.txt` holds two real FreeStyle Libre 2 scans, still scrambled as
they come from the sensor. They were published in the open-source project
LibreTransmitter (https://github.com/LoopKit/LibreTransmitter, file
`LibreSensor/SensorContents/PreLibre2.swift`). LibreTransmitter is under the MIT license,
Copyright (c) 2016 Mark Wilson; the full text is in
`samples/LICENSE-LibreTransmitter.txt`.

These two scans are the only real sensor data this project was tested with.
