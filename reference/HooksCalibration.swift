
// ---------------------------------------------------------------------------
// Appended by reference/run.sh to a COPY of FactoryCalibration.swift.
// The original text above is unchanged. This hook only makes the two
// file-private number tables readable from the harness.
// ---------------------------------------------------------------------------
extension FactoryCalibration {
    static var hook_t1: [Double] { t1 }
    static var hook_t2: [Double] { t2 }
}
