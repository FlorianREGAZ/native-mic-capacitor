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

    func testWebRTCParseConnectOptionsDefaults() throws {
        let raw: [String: Any] = [
            "webrtcRequest": [
                "endpoint": "https://voice.example.com/offer"
            ]
        ]

        let options = try NativeWebRTCController.parseConnectOptions(raw)

        XCTAssertFalse(options.connectionId.isEmpty)
        XCTAssertTrue(options.reconnect.enabled)
        XCTAssertEqual(options.reconnect.maxAttempts, 3)
        XCTAssertEqual(options.reconnect.backoffMs, 2_000)
        XCTAssertEqual(options.media.outputRoute, .receiver)
        XCTAssertTrue(options.media.voiceProcessing)
    }

    func testWebRTCParseIceServers() throws {
        let raw: [String: Any] = [
            "iceConfig": [
                "iceServers": [
                    ["urls": "stun:stun.example.com:3478"],
                    [
                        "urls": [
                            "turn:turn-a.example.com:3478",
                            "turn:turn-b.example.com:3478"
                        ],
                        "username": "user",
                        "credential": "pass"
                    ]
                ]
            ]
        ]

        let servers = try NativeWebRTCController.parseIceServers(raw)

        XCTAssertEqual(servers.count, 2)
        XCTAssertEqual(servers[0].urls.count, 1)
        XCTAssertEqual(servers[1].urls.count, 2)
        XCTAssertEqual(servers[1].username, "user")
        XCTAssertEqual(servers[1].credential, "pass")
    }

    func testWebRTCParseNullableCodec() {
        XCTAssertNil(NativeWebRTCController.parseNullableCodec(nil))
        XCTAssertNil(NativeWebRTCController.parseNullableCodec("default"))
        XCTAssertEqual(NativeWebRTCController.parseNullableCodec("opus"), "opus")
    }
}
