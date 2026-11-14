//
//  Shims.swift  (Glucose Scan reference harness)
//
//  The original app keeps a few small helpers inside files that import SwiftUI,
//  Combine or OSLog. Those files cannot be compiled on Linux, so the helpers that
//  the pure-logic code needs are provided here.
//

import Foundation

// Logging is a no-op here.
enum DirectLog {
    static func debug(_ message: String) {}
    static func info(_ message: String) {}
    static func warning(_ message: String) {}
    static func error(_ message: String) {}
}

// Copied verbatim from Library/Extensions/Int.swift of Glucose Direct.
extension UInt16 {
    init(_ high: UInt8, _ low: UInt8) {
        self = UInt16(high) << 8 + UInt16(low)
    }

    init(_ data: Data) {
        self = UInt16(data[data.startIndex + 1]) << 8 + UInt16(data[data.startIndex])
    }
}

// Only used inside a debug description string.
extension Int {
    var inTime: String { "\(self) min" }
}

// Copied verbatim from Library/Extensions/Double.swift of Glucose Direct.
extension Double {
    func toMmolL() -> Double {
        return self * GlucoseUnit.exchangeRate
    }

    func toMgdl() -> Int? {
        if let value = (self / GlucoseUnit.exchangeRate).toInteger() {
            return value
        }

        return nil
    }

    func toInteger() -> Int? {
        if self >= Double(Int.min), self < Double(Int.max) {
            return Int(self.rounded())
        } else {
            return nil
        }
    }
}
