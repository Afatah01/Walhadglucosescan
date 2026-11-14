//
//  main.swift  (Glucose Scan reference harness)
//
//  Runs the ORIGINAL Glucose Direct Swift code (compiled unchanged for Linux)
//  on many inputs and prints the results as text lines. The Android unit tests
//  then feed the same inputs to the Kotlin port and require identical results.
//
//  Output format: one record per line, fields separated by '|'.
//  Bytes are lower-case hex. Doubles are written as their 64-bit pattern (hex),
//  so they can be compared exactly.
//

import Foundation

// MARK: - Deterministic random numbers (SplitMix64), so every run gives the same file

struct RNG {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }

    mutating func int(_ range: ClosedRange<Int>) -> Int {
        let span = UInt64(range.upperBound - range.lowerBound + 1)
        return range.lowerBound + Int(next() % span)
    }

    mutating func byte() -> UInt8 { UInt8(truncatingIfNeeded: next()) }

    mutating func bytes(_ count: Int) -> Data {
        var d = Data(capacity: count)
        for _ in 0 ..< count { d.append(byte()) }
        return d
    }

    mutating func pick<T>(_ items: [T]) -> T { items[int(0 ... items.count - 1)] }
}

// MARK: - Output helpers

var lines: [String] = []

func emit(_ parts: String...) { lines.append(parts.joined(separator: "|")) }
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func hex(_ a: [UInt8]) -> String { a.map { String(format: "%02x", $0) }.joined() }
func h16(_ v: UInt16) -> String { String(format: "%04x", v) }
func bits(_ v: Double) -> String { String(format: "%016llx", v.bitPattern) }
func dataFromHex(_ s: String) -> Data { Data(hexString: s)! }

func describe(_ c: FactoryCalibration) -> String {
    // i3...i6 are whole numbers stored as Double
    "\(c.i1)|\(c.i2)|\(Int(c.i3))|\(Int(c.i4))|\(Int(c.i5))|\(Int(c.i6))"
}

func describe(_ readings: [SensorReading]) -> String {
    readings.map { "\(Int($0.timestamp.timeIntervalSince1970)),\(bits($0.glucoseValue)),\($0.error.rawValue)" }.joined(separator: ";")
}

var rng = RNG(state: 0x474C55434F5345) // "GLUCOSE"

// MARK: - A. processCrypto

do {
    var inputs: [[UInt16]] = [[0, 0, 0, 0], [0xffff, 0xffff, 0xffff, 0xffff], [1, 2, 3, 4], [0x8000, 0x4000, 0x2000, 0x1000]]
    for _ in 0 ..< 400 {
        inputs.append((0 ..< 4).map { _ in UInt16(truncatingIfNeeded: rng.next()) })
    }
    for input in inputs {
        let o = Libre2EUtility.hook_processCrypto(input)
        emit("CRYPTO", input.map(h16).joined(separator: ","), o.map(h16).joined(separator: ","))
    }
}

// MARK: - B. crc16

do {
    var datas: [Data] = [Data(), Data([0]), Data([0xff]), Data("123456789".utf8), Data((0 ... 255).map { UInt8($0) })]
    for len in [1, 2, 3, 6, 8, 22, 42, 294, 344] { datas.append(rng.bytes(len)) }
    for _ in 0 ..< 200 { datas.append(rng.bytes(rng.int(0 ... 400))) }
    for d in datas {
        emit("CRC", hex(d), h16(Libre2EUtility.hook_crc16(d)))
    }
}

// MARK: - C. readBits

do {
    for _ in 0 ..< 600 {
        let d = rng.bytes(rng.int(0 ... 48))
        let byteOffset = rng.int(0 ... 60)
        let bitOffset = rng.int(0 ... 70)
        let bitCount = rng.int(0 ... 20)
        let v = Libre2EUtility.hook_readBits(d, byteOffset, bitOffset, bitCount)
        emit("BITS", hex(d), "\(byteOffset)", "\(bitOffset)", "\(bitCount)", "\(v)")
    }
}

// MARK: - D. sensor type / family / region from patch info

do {
    var infos: [Data] = []
    for b in 0 ... 255 {
        for (x, y) in [(0x30, 0x01), (0x70, 0x02), (0x30, 0x02), (0x30, 0x04), (0x70, 0x00), (0x10, 0x08)] {
            infos.append(Data([UInt8(b), rng.byte(), UInt8(x), UInt8(y), rng.byte(), rng.byte()]))
        }
    }
    infos.append(Data([0x12, 0x00, 0x30, 0x01, 0x00, 0x00, 0x00]))
    infos.append(rng.bytes(28))
    infos.append(rng.bytes(35))
    for info in infos {
        emit("TYPE", hex(info), String(describing: SensorType(info)), "\(SensorFamily(info).rawValue)", String(describing: SensorRegion(info)))
    }
}

// MARK: - E. decryptFRAM

do {
    let firstBytes: [UInt8] = [0x9D, 0xC5, 0xC6, 0x7F, 0xE5, 0xE6, 0x76, 0xDF, 0xA2, 0x70]
    for n in 0 ..< 320 {
        let uuid = rng.bytes(8)
        var info = rng.bytes(6)
        info[0] = n % 8 == 7 ? rng.byte() : rng.pick(firstBytes)
        let fram = rng.bytes(344)
        let r = Libre2EUtility.decryptFRAM(uuid: uuid, patchInfo: info, fram: fram)
        emit("DECRYPT", hex(uuid), hex(info), hex(fram), r.map { hex($0) } ?? "NIL")
    }
    // wrong lengths must be refused
    let cases: [(Int, Int, Int)] = [(7, 6, 344), (9, 6, 344), (8, 5, 344), (8, 7, 344), (8, 6, 343), (8, 6, 345), (8, 6, 0)]
    for (u, p, f) in cases {
        let uuid = rng.bytes(u)
        var info = rng.bytes(p)
        info[0] = 0x9D
        let fram = rng.bytes(f)
        let r = Libre2EUtility.decryptFRAM(uuid: uuid, patchInfo: info, fram: fram)
        emit("DECRYPT", hex(uuid), hex(info), hex(fram), r.map { hex($0) } ?? "NIL")
    }
}

// MARK: - F. factory calibration parameters out of the FRAM

do {
    for _ in 0 ..< 300 {
        let fram = rng.bytes(344)
        let c = FactoryCalibration.libreStyleCalibration(fram: fram)
        emit("CALPARAM", hex(fram), describe(c))
    }
}

// MARK: - G. calibrate (raw value + temperature -> mg/dL)

do {
    func one(_ c: FactoryCalibration, _ raw: Double, _ temp: Double, _ adj: Double) {
        let v = c.calibrate(rawValue: raw, rawTemperature: temp, rawTemperatureAdjustment: adj)
        emit("CALIBRATE", describe(c), bits(raw), bits(temp), bits(adj), bits(v), "\(v)")
    }

    // wide random inputs
    for _ in 0 ..< 3000 {
        let i3mag = rng.int(0 ... 255)
        let c = FactoryCalibration(
            i1: rng.int(0 ... 7),
            i2: rng.int(1 ... 1023),
            i3: Double(rng.int(0 ... 1) == 0 ? i3mag : -i3mag),
            i4: Double(rng.int(0 ... 16383)),
            i5: Double(rng.int(0 ... 4095) << 2),
            i6: Double(rng.int(0 ... 4095) << 2)
        )
        let raw = Double(rng.int(0 ... 16383))
        let temp = Double(rng.int(0 ... 4095) << 2)
        let adjMag = rng.int(0 ... 511) << 2
        let adj = Double(rng.int(0 ... 1) == 0 ? adjMag : -adjMag)
        one(c, raw, temp, adj)
    }

    // inputs in the range real sensors produce
    for _ in 0 ..< 3000 {
        let c = FactoryCalibration(
            i1: rng.int(0 ... 7),
            i2: rng.int(1 ... 1023),
            i3: Double(rng.int(-60 ... 60)),
            i4: Double(rng.int(3000 ... 9000)),
            i5: Double(rng.int(1500 ... 3000) << 2),
            i6: Double(rng.int(1500 ... 3000) << 2)
        )
        let raw = Double(rng.int(200 ... 8000))
        let temp = Double(rng.int(1500 ... 3000) << 2)
        let adj = Double(rng.int(-40 ... 40) << 2)
        one(c, raw, temp, adj)
    }

    // special cases: division by zero, logarithm of zero or of a negative number
    let base = FactoryCalibration(i1: 1, i2: 500, i3: 30, i4: 6000, i5: 9000, i6: 8000)
    one(base, 1000, 8000, -8000) // rRight == 0
    one(base, 1000, 0, 0) // R == -1000
    one(base, 1000, 8000, 572000) // R == 0 exactly (8000 * 72500 / 580000 = 1000)
    one(FactoryCalibration(i1: 1, i2: 500, i3: 30, i4: 30, i5: 9000, i6: 8000), 1000, 8000, 0) // i4 == i3
    one(FactoryCalibration(i1: 1, i2: 500, i3: 30, i4: 30, i5: 9000, i6: 8000), 30, 8000, 0) // 0 / 0
    one(base, 0, 8000, 0)
    one(base, 16383, 16380, 2044)
    one(FactoryCalibration(i1: 0, i2: 1, i3: 0, i4: 1, i5: 0, i6: 4), 16383, 16380, 0)
    one(FactoryCalibration(i1: 7, i2: 1023, i3: -255, i4: 16383, i5: 16380, i6: 16380), 0, 4, -2044)
}

// MARK: - G2. the two calibration tables, every entry

do {
    emit("TABLE", "t1", "\(FactoryCalibration.hook_t1.count)", FactoryCalibration.hook_t1.map(bits).joined(separator: ","))
    emit("TABLE", "t2", "\(FactoryCalibration.hook_t2.count)", FactoryCalibration.hook_t2.map(bits).joined(separator: ","))

    // every table row once, with fixed realistic inputs
    for i2 in 1 ... 1023 {
        let c = FactoryCalibration(i1: 0, i2: i2, i3: 20, i4: 6800, i5: 9000, i6: 6500)
        let v = c.calibrate(rawValue: 1400, rawTemperature: 6576, rawTemperatureAdjustment: -4)
        emit("CALIBRATE", describe(c), bits(1400), bits(6576), bits(-4), bits(v), "\(v)")
    }
}

// MARK: - H. Sensor description from uid + patch info + FRAM

do {
    let firstBytes: [UInt8] = [0x9D, 0xC5, 0xC6, 0x7F, 0xE5, 0x76, 0xDF, 0xA2, 0x70]
    for n in 0 ..< 300 {
        var uuid = rng.bytes(8)
        if n % 2 == 0 { uuid[6] = 0x07; uuid[7] = 0xE0 }
        var info = rng.bytes(6)
        info[0] = rng.pick(firstBytes)
        if n % 3 != 0 { info[2] = rng.pick([0x00, 0x10, 0x30, 0x31, 0x70]); info[3] = rng.pick([0x00, 0x01, 0x02, 0x04, 0x08]) }
        var fram = rng.bytes(344)
        if n % 2 == 0 { fram[4] = UInt8(rng.int(0 ... 7)) }
        let s = Sensor.libreStyleSensor(uuid: uuid, patchInfo: info, fram: fram)
        emit(
            "SENSOR", hex(uuid), hex(info), hex(fram),
            "\(s.family.rawValue)", String(describing: s.type), String(describing: s.region), s.serial ?? "NIL",
            String(describing: s.state), "\(s.age)", "\(s.lifetime)", "\(s.warmupTime)",
            s.remainingWarmupTime.map { "\($0)" } ?? "NIL", "\(s.remainingLifetime)", "\(s.elapsedLifetime)",
            describe(s.factoryCalibration!)
        )
    }
}

// MARK: - I. parseFRAM (trend and history readings)

func emitParse(_ tag: String, _ fram: Data, _ pairing: Int) {
    let c = FactoryCalibration.libreStyleCalibration(fram: fram)
    let r = LibreUtility.parseFRAM(calibration: c, pairingTimestamp: Date(timeIntervalSince1970: Double(pairing)), fram: fram)
    emit("PARSE", tag, "\(pairing)", hex(fram), describe(c), "T=" + describe(r.trend), "H=" + describe(r.history))
}

/// A FRAM image that looks like a real one: sane indexes, age and raw values.
func plausibleFram(_ rng: inout RNG) -> Data {
    var fram = rng.bytes(344)
    fram = Libre2EUtility.hook_writeBits(fram, 2, 0, 3, rng.int(0 ... 7)) // i1
    fram = Libre2EUtility.hook_writeBits(fram, 2, 3, 0xa, rng.int(1 ... 1023)) // i2
    fram[4] = 3
    fram[26] = UInt8(rng.int(0 ... 15)) // trend index
    fram[27] = UInt8(rng.int(0 ... 31)) // history index
    let age = rng.pick([0, 1, 2, 3, 4, 17, 18, 19, 59, 60, 61, 62, 63, 474, 480, 482, 483, 500, rng.int(0 ... 21600), rng.int(0 ... 21600), rng.int(0 ... 65535)])
    fram[316] = UInt8(age & 0xff)
    fram[317] = UInt8((age >> 8) & 0xff)
    fram[326] = UInt8(20160 & 0xff)
    fram[327] = UInt8(20160 >> 8)
    // calibration block: realistic values
    fram = Libre2EUtility.hook_writeBits(fram, 0x150, 0, 8, rng.int(0 ... 60)) // i3 magnitude
    fram = Libre2EUtility.hook_writeBits(fram, 0x150, 0x21, 1, rng.int(0 ... 1)) // i3 sign
    fram = Libre2EUtility.hook_writeBits(fram, 0x150, 8, 0xe, rng.int(3000 ... 9000)) // i4
    fram = Libre2EUtility.hook_writeBits(fram, 0x150, 0x28, 0xc, rng.int(1500 ... 3000)) // i5 >> 2
    fram = Libre2EUtility.hook_writeBits(fram, 0x150, 0x34, 0xc, rng.int(1500 ... 3000)) // i6 >> 2
    // records
    for slot in 0 ..< 48 {
        let offset = slot < 16 ? 28 + slot * 6 : 124 + (slot - 16) * 6
        let kind = rng.int(0 ... 19)
        let raw = kind == 0 ? 0 : rng.int(200 ... 8000) // about 5% faulty records
        fram = Libre2EUtility.hook_writeBits(fram, offset, 0, 0xe, raw)
        fram = Libre2EUtility.hook_writeBits(fram, offset, 0x1a, 0xc, rng.int(1500 ... 3000))
        fram = Libre2EUtility.hook_writeBits(fram, offset, 0x26, 0x9, rng.int(0 ... 40))
        fram = Libre2EUtility.hook_writeBits(fram, offset, 0x2f, 0x1, rng.int(0 ... 1))
    }
    return fram
}

do {
    let t0 = 1_767_225_600 // 2026-01-01 00:00:00 UTC
    for n in 0 ..< 400 {
        emitParse("plausible\(n)", plausibleFram(&rng), t0 + rng.int(0 ... 40_000_000))
    }
    // completely random bytes (only i2 is kept inside the table, as the original would crash otherwise)
    for n in 0 ..< 200 {
        var fram = rng.bytes(344)
        if Libre2EUtility.hook_readBits(fram, 2, 3, 0xa) == 0 {
            fram = Libre2EUtility.hook_writeBits(fram, 2, 3, 0xa, 1)
        }
        emitParse("random\(n)", fram, t0 + rng.int(0 ... 40_000_000))
    }
}

// MARK: - I2. every record of the FRAM, newest first, without the time filter

/// Reads all 16 trend and 32 history records with the original bit reader and the original
/// calibration, in the same order as the loops in the original parseFRAM (i = 0 is the newest).
/// Fields per record: raw glucose, raw temperature, temperature adjustment, value (bits).
func emitRecords(_ tag: String, _ fram: Data) {
    let c = FactoryCalibration.libreStyleCalibration(fram: fram)
    func record(_ offset: Int) -> String {
        let rawGlucoseValue = Libre2EUtility.hook_readBits(fram, offset, 0, 0xe)
        let rawTemperature = Libre2EUtility.hook_readBits(fram, offset, 0x1a, 0xc) << 2
        var rawTemperatureAdjustment = Libre2EUtility.hook_readBits(fram, offset, 0x26, 0x9) << 2
        if Libre2EUtility.hook_readBits(fram, offset, 0x2f, 0x1) != 0 {
            rawTemperatureAdjustment = -rawTemperatureAdjustment
        }
        let v = c.calibrate(rawValue: Double(rawGlucoseValue), rawTemperature: Double(rawTemperature), rawTemperatureAdjustment: Double(rawTemperatureAdjustment))
        return "\(rawGlucoseValue),\(rawTemperature),\(rawTemperatureAdjustment),\(bits(v))"
    }
    var trend: [String] = []
    let trendIndex = Int(fram[26])
    for i in 0 ... 15 {
        var j = trendIndex - 1 - i
        if j < 0 { j += 16 }
        trend.append(record(28 + j * 6))
    }
    var history: [String] = []
    let historyIndex = Int(fram[27])
    for i in 0 ... 31 {
        var j = historyIndex - 1 - i
        if j < 0 { j += 32 }
        history.append(record(124 + j * 6))
    }
    let age = Int(fram[316]) + Int(fram[317]) << 8
    emit("RECORDS", tag, hex(fram), "\(age)", "\(trendIndex)", "\(historyIndex)", "T=" + trend.joined(separator: ";"), "H=" + history.joined(separator: ";"))
}

do {
    var r2 = RNG(state: 0x5245434F524453) // "RECORDS"
    for n in 0 ..< 120 {
        emitRecords("plausible\(n)", plausibleFram(&r2))
    }
}

// MARK: - J. reading -> glucose value (rounding, limits)

do {
    var values: [Double] = [
        .nan, .infinity, -.infinity, -1e30, 1e30, -1000, -0.5, -0.4999, 0, 0.49999999999999994, 0.5, 1.5, 2.5,
        38, 38.4, 38.5, 38.999, 39, 39.5, 40, 99.5, 100, 100.49, 100.5, 499.5, 500, 500.4, 500.5, 501, 501.4, 501.5, 502, 10000,
    ]
    for _ in 0 ..< 300 { values.append(Double(rng.int(-2000 ... 12000)) / 16.0) }
    let ts = Date(timeIntervalSince1970: 1_767_225_600)
    for v in values {
        let reading = SensorReading.createGlucoseReading(timestamp: ts, glucoseValue: v)
        if let g = reading.calibrate(customCalibration: []) {
            emit("READING", bits(v), "\(g.rawGlucoseValue)", "\(g.intGlucoseValue)", "\(g.glucoseValue)", String(describing: g.type))
        } else {
            emit("READING", bits(v), "NIL")
        }
    }
}

// MARK: - K. change per minute and trend arrow

do {
    for _ in 0 ..< 400 {
        let prevTs = 1_767_225_600 + rng.int(0 ... 100_000)
        let nextTs = prevTs + rng.pick([0, 20, 59, 60, 61, 119, 120, 180, 300, 900, rng.int(0 ... 4000)])
        let prevVal = rng.pick([20, 39, 40, 80, 100, 120, 250, 500, 501, 600, rng.int(0 ... 600)])
        let nextVal = prevVal + rng.pick([0, 1, -1, 2, -2, 3, -3, 4, -4, 7, -7, rng.int(-80 ... 80)])
        let prev = SensorGlucose(timestamp: Date(timeIntervalSince1970: Double(prevTs)), rawGlucoseValue: prevVal, intGlucoseValue: prevVal)
        let next = SensorGlucose(timestamp: Date(timeIntervalSince1970: Double(nextTs)), rawGlucoseValue: nextVal, intGlucoseValue: nextVal)
        let changed = next.populateChange(previousGlucose: prev)
        emit(
            "CHANGE", "\(prevTs)", "\(prevVal)", "\(nextTs)", "\(nextVal)",
            changed.minuteChange.map { bits($0) } ?? "NIL", String(describing: changed.trend), "\(changed.glucoseValue)"
        )
    }
    let slopes: [Double] = [-100, -3.6, -3.5, -3.4999, -2.1, -2, -1.9, -1.1, -1, -0.9, -0.1, 0, 0.1, 0.9, 1, 1.1, 1.9, 2, 2.1, 3.4999, 3.5, 3.6, 100, .nan, .infinity, -.infinity]
    for s in slopes {
        emit("SLOPE", bits(s), String(describing: SensorTrend(slope: s)))
    }
}

// MARK: - L. real Libre 2 scans

do {
    let samplesPath = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "samples/libre2_examples.txt"
    let text = try! String(contentsOfFile: samplesPath, encoding: .utf8)
    for line in text.split(separator: "\n") where !line.hasPrefix("#") {
        let f = line.split(separator: "|").map(String.init)
        let (name, uuid, info, enc) = (f[0], dataFromHex(f[1]), dataFromHex(f[2]), dataFromHex(f[3]))
        let fram = Libre2EUtility.decryptFRAM(uuid: uuid, patchInfo: info, fram: enc)!
        emit("SAMPLE", name, hex(uuid), hex(info), hex(enc), hex(fram))

        // The three checksums inside the decrypted FRAM, computed with the original crc16.
        func section(_ start: Int, _ end: Int) -> String {
            let stored = UInt16(fram[start], fram[start + 1])
            let computed = Libre2EUtility.hook_crc16(Data(fram[(start + 2) ..< end]))
            return "\(h16(stored)),\(h16(computed))"
        }
        emit("SAMPLECRC", name, section(0, 24), section(24, 320), section(320, 344))

        let s = Sensor.libreStyleSensor(uuid: uuid, patchInfo: info, fram: fram)
        emit(
            "SENSOR", hex(uuid), hex(info), hex(fram),
            "\(s.family.rawValue)", String(describing: s.type), String(describing: s.region), s.serial ?? "NIL",
            String(describing: s.state), "\(s.age)", "\(s.lifetime)", "\(s.warmupTime)",
            s.remainingWarmupTime.map { "\($0)" } ?? "NIL", "\(s.remainingLifetime)", "\(s.elapsedLifetime)",
            describe(s.factoryCalibration!)
        )
        emitParse(name, fram, 1_767_225_600 + 12_345)
        emitRecords(name, fram)
    }
}

// MARK: - M. Bluetooth of the Libre 2 (added for version 2.0 of the Android app)
//
// This section has its own random numbers, so that every record above stays as it was.

do {
    var ble = RNG(state: 0x424C5545544F4F54) // "BLUETOOT"

    func sensorUid() -> Data {
        var uuid = ble.bytes(8)
        uuid[6] = ble.int(0 ... 3) == 0 ? 0x7A : 0x07
        uuid[7] = 0xE0
        return uuid
    }

    func libre2Info() -> Data {
        var info = ble.bytes(6)
        info[0] = ble.pick([0x9D, 0xC5, 0xC6, 0x7F] as [UInt8])
        info[1] = 0x08
        info[2] = 0x30
        info[3] = 0x01
        return info
    }

    // M1. usefulFunction: the base of the NFC command and of unlocking
    for n in 0 ..< 300 {
        let uuid = n % 3 == 0 ? ble.bytes(8) : sensorUid()
        let x: UInt16 = n % 4 == 0 ? UInt16(truncatingIfNeeded: ble.next()) : (n % 2 == 0 ? 0x1b : 0x1e)
        let y: UInt16 = n % 5 == 0 ? 0x1b6a : UInt16(truncatingIfNeeded: ble.next())
        emit("USEFUL", hex(uuid), h16(x), h16(y), hex(Libre2EUtility.usefulFunction(uuid: uuid, x: x, y: y)))
    }

    // M2. the NFC command that switches Bluetooth on ("enable streaming")
    let host = NfcCommandCopy()
    for n in 0 ..< 200 {
        let uuid = sensorUid()
        let info = libre2Info()
        let unlockCode: UInt32 = n % 2 == 0 ? 42 : UInt32(truncatingIfNeeded: ble.next())
        let (code, parameters) = host.hook_enableStreaming(unlockCode: unlockCode, patchInfo: info, sensorUID: uuid)
        emit("NFCCMD", hex(uuid), hex(info), "\(unlockCode)", String(format: "%02x", code), hex(parameters))
    }

    // M3. the 12 bytes that unlock the Bluetooth connection
    for n in 0 ..< 300 {
        let uuid = sensorUid()
        let info = libre2Info()
        // the original adds the two numbers as UInt32 and would stop on an overflow
        let enableTime: UInt32 = n % 2 == 0 ? 42 : UInt32(ble.next() % 0xFFFE_0000)
        let unlockCount: UInt16 = n < 40 ? UInt16(n + 1) : UInt16(truncatingIfNeeded: ble.next())
        let payload = Libre2EUtility.streamingUnlockPayload(uuid: uuid, patchInfo: info, enableTime: enableTime, unlockCount: unlockCount)
        emit("UNLOCK", hex(uuid), hex(info), "\(enableTime)", "\(unlockCount)", hex(payload))
    }

    // M4. unscrambling a Bluetooth message: 46 bytes in, 44 bytes out, with a checksum.
    //
    // To make messages that the original accepts, a plain message is scrambled here with the
    // same key steps as in decryptBLE. That this is right is not assumed: for every message
    // the original decryptBLE must give back the plain text.
    func keyStream(_ uuid: Data, _ first: UInt8, _ second: UInt8) -> [UInt8] {
        let d = Libre2EUtility.usefulFunction(uuid: uuid, x: 0x1b, y: 0x1b6a)
        let x = UInt16(d[1], d[0]) ^ UInt16(d[3], d[2]) | 0x63
        let y = UInt16(second, first) ^ 0x63
        var key = [UInt8]()
        var words = Libre2EUtility.hook_processCrypto(Libre2EUtility.hook_prepareVariables(uuid: uuid, x: x, y: y))
        for _ in 0 ..< 8 {
            for word in words {
                key.append(UInt8(truncatingIfNeeded: word))
                key.append(UInt8(truncatingIfNeeded: word >> 8))
            }
            words = Libre2EUtility.hook_processCrypto(words)
        }
        return key
    }

    var plainMessages: [Data] = []
    for n in 0 ..< 300 {
        let uuid = sensorUid()
        var plain: Data
        if n % 3 == 0 {
            plain = ble.bytes(44)
        } else {
            // ten records that look like real ones
            plain = Data(count: 44)
            for i in 0 ..< 10 {
                let raw = ble.int(0 ... 9) == 0 ? 0 : ble.int(600 ... 4000)
                let temp = ble.int(0 ... 19) == 0 ? ble.int(0 ... 4095) : ble.int(1500 ... 1800)
                let adjustment = ble.int(0 ... 31)
                let negative = ble.int(0 ... 1)
                plain = Libre2EUtility.hook_writeBits(plain, i * 4, 0, 0xe, raw)
                plain = Libre2EUtility.hook_writeBits(plain, i * 4, 0xe, 0xc, temp)
                plain = Libre2EUtility.hook_writeBits(plain, i * 4, 0x1a, 0x5, adjustment)
                plain = Libre2EUtility.hook_writeBits(plain, i * 4, 0x1f, 0x1, negative)
                precondition(Libre2EUtility.hook_readBits(plain, i * 4, 0, 0xe) == raw)
                precondition(Libre2EUtility.hook_readBits(plain, i * 4, 0xe, 0xc) == temp)
                precondition(Libre2EUtility.hook_readBits(plain, i * 4, 0x1a, 0x5) == adjustment)
                precondition(Libre2EUtility.hook_readBits(plain, i * 4, 0x1f, 0x1) == negative)
            }
        }
        let age = ble.pick([0, 1, 2, 14, 15, 16, 17, 30, 45, 59, 60, 61, 62, 75, 76, 77, 20158, 20159, 20160, ble.int(0 ... 21600), ble.int(0 ... 21600), ble.int(0 ... 21600), ble.int(0 ... 65535)])
        plain[40] = UInt8(age & 0xFF)
        plain[41] = UInt8(age >> 8)
        let crc = Libre2EUtility.hook_crc16(Data(plain.prefix(42)))
        plain[42] = UInt8(crc >> 8)
        plain[43] = UInt8(crc & 0xFF)

        let first = ble.byte()
        let second = ble.byte()
        let key = keyStream(uuid, first, second)
        var message = Data([first, second])
        for i in 0 ..< 44 {
            message.append(plain[i] ^ key[i])
        }

        let back = Data(try! Libre2EUtility.decryptBLE(uuid: uuid, data: message))
        precondition(back == plain, "the original does not give back the plain text")
        emit("BLEDECRYPT", hex(uuid), hex(message), hex(back))
        plainMessages.append(plain)

        // one changed bit must be found by the checksum
        if n % 3 == 1 {
            var broken = message
            broken[ble.int(2 ... 45)] ^= UInt8(1 << ble.int(0 ... 7))
            let result = try? Libre2EUtility.decryptBLE(uuid: uuid, data: broken)
            emit("BLEDECRYPT", hex(uuid), hex(broken), result.map { hex($0) } ?? "ERROR")
        }
    }
    for _ in 0 ..< 100 {
        let uuid = sensorUid()
        let message = ble.bytes(46)
        let result = try? Libre2EUtility.decryptBLE(uuid: uuid, data: message)
        emit("BLEDECRYPT", hex(uuid), hex(message), result.map { hex($0) } ?? "ERROR")
    }

    // M5. the records of a Bluetooth message. The original stamps them with the time of the
    // call, so the times are written as "minutes before now".
    for (n, plain) in plainMessages.enumerated() {
        let c = n % 2 == 0
            ? FactoryCalibration(i1: ble.int(0 ... 7), i2: ble.int(1 ... 1023), i3: Double(ble.int(-60 ... 60)), i4: Double(ble.int(3000 ... 9000)), i5: Double(ble.int(1500 ... 3000) << 2), i6: Double(ble.int(1500 ... 3000) << 2))
            : FactoryCalibration(i1: ble.int(0 ... 7), i2: ble.int(1 ... 1023), i3: Double(ble.int(-255 ... 255)), i4: Double(ble.int(0 ... 16383)), i5: Double(ble.int(0 ... 4095) << 2), i6: Double(ble.int(0 ... 4095) << 2))
        // The original cuts every time down to the start of its minute. "now" is treated the
        // same way here. If the minute changes while the original runs, it runs once more.
        var now = Date().toRounded(on: 1, .minute)
        var r = Libre2EUtility.parseBLE(calibration: c, data: plain)
        while Date().toRounded(on: 1, .minute) != now {
            now = Date().toRounded(on: 1, .minute)
            r = Libre2EUtility.parseBLE(calibration: c, data: plain)
        }
        func describeBle(_ readings: [SensorReading]) -> String {
            readings.map {
                let minutesBefore = Int((now.timeIntervalSince($0.timestamp) / 60).rounded())
                return "\(minutesBefore),\(bits($0.glucoseValue)),\($0.error.rawValue)"
            }.joined(separator: ";")
        }
        emit("BLEPARSE", hex(plain), describe(c), "\(r.age)", "T=" + describeBle(r.trend), "H=" + describeBle(r.history))
    }
}

// MARK: - write

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "reference_vectors.txt"
let header = [
    "# Reference results produced by the ORIGINAL Glucose Direct Swift code.",
    "# Do not edit by hand. Re-create with:  bash reference/run.sh",
    "# \(lines.count) records.",
]
try! (header + lines).joined(separator: "\n").appending("\n").write(toFile: outPath, atomically: true, encoding: .utf8)
print("wrote \(lines.count) records to \(outPath)")
