import XCTest
@testable import NativeMicPlugin

class NativeMicTests: XCTestCase {
    func testOutputStreamSampleRates() {
        XCTAssertEqual(OutputStream.pcm16k.sampleRate, 16_000)
        XCTAssertEqual(OutputStream.pcm48k.sampleRate, 48_000)
    }

    func testPermissionStateRawValues() {
        XCTAssertEqual(MicPermissionState.prompt.rawValue, "prompt")
        XCTAssertEqual(MicPermissionState.granted.rawValue, "granted")
        XCTAssertEqual(MicPermissionState.denied.rawValue, "denied")
    }
}
