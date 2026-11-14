
// ---------------------------------------------------------------------------
// Appended by reference/run.sh to a COPY of SensorUtility.swift.
// The original text above is unchanged. These hooks only make the file-private
// helpers callable from the harness.
// ---------------------------------------------------------------------------
extension Libre2EUtility {
    static func hook_processCrypto(_ input: [UInt16]) -> [UInt16] { processCrypto(input: input) }
    static func hook_prepareVariables(uuid: Data, x: UInt16, y: UInt16) -> [UInt16] { prepareVariables(uuid: uuid, x: x, y: y) }
    static func hook_crc16(_ data: Data) -> UInt16 { crc16(data) }
    static func hook_prepareVariables2(uuid: Data, i1: UInt16, i2: UInt16, i3: UInt16, i4: UInt16) -> [UInt16] { prepareVariables(uuid: uuid, i1: i1, i2: i2, i3: i3, i4: i4) }
    static func hook_readBits(_ buffer: Data, _ byteOffset: Int, _ bitOffset: Int, _ bitCount: Int) -> Int { readBits(buffer, byteOffset, bitOffset, bitCount) }
    static func hook_writeBits(_ buffer: Data, _ byteOffset: Int, _ bitOffset: Int, _ bitCount: Int, _ value: Int) -> Data { writeBits(buffer, byteOffset, bitOffset, bitCount, value) }
}
