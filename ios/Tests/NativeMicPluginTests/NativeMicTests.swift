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
        XCTAssertFalse(options.audioOnly)
        XCTAssertTrue(options.reconnect.enabled)
        XCTAssertEqual(options.reconnect.maxAttempts, 3)
        XCTAssertEqual(options.reconnect.backoffMs, 2_000)
        XCTAssertEqual(options.media.outputRoute, .system)
        XCTAssertTrue(options.media.voiceProcessing)
        XCTAssertTrue(options.media.startMicEnabled)
    }

    func testWebRTCDefaultRoutePolicyUsesSpeakerExceptForReceiver() {
        XCTAssertTrue(NativeWebRTCController.shouldDefaultToSpeaker(for: .system))
        XCTAssertTrue(NativeWebRTCController.shouldDefaultToSpeaker(for: .speaker))
        XCTAssertFalse(NativeWebRTCController.shouldDefaultToSpeaker(for: .receiver))
    }

    func testWebRTCParseConnectOptionsRequiresWebRTCRequest() {
        assertWebRTCInvalidArgument("webrtcRequest is required.") {
            _ = try NativeWebRTCController.parseConnectOptions([:])
        }
    }

    func testWebRTCParseConnectOptionsRequiresEndpoint() {
        assertWebRTCInvalidArgument("webrtcRequest.endpoint is required.") {
            _ = try NativeWebRTCController.parseConnectOptions([
                "webrtcRequest": [:]
            ])
        }
    }

    func testWebRTCParseConnectOptionsRejectsInvalidOutputRoute() {
        assertWebRTCInvalidArgument("media.outputRoute must be one of: system, speaker, receiver.") {
            _ = try NativeWebRTCController.parseConnectOptions([
                "webrtcRequest": [
                    "endpoint": "https://voice.example.com/offer"
                ],
                "media": [
                    "outputRoute": "bluetooth"
                ]
            ])
        }
    }

    func testWebRTCParseConnectOptionsClampsValuesAndHonorsExplicitMedia() throws {
        let raw: [String: Any] = [
            "connectionId": "  test-connection  ",
            "webrtcRequest": [
                "endpoint": "https://voice.example.com/offer",
                "headers": [
                    "Authorization": 123
                ],
                "requestData": [
                    "mode": "voice"
                ],
                "timeoutMs": 250
            ],
            "waitForICEGathering": true,
            "audioOnly": true,
            "audioCodec": " DEFAULT ",
            "videoCodec": "  H264  ",
            "media": [
                "voiceProcessing": false,
                "startMicEnabled": false,
                "preferredInputId": "  built-in-mic  ",
                "outputRoute": "speaker"
            ],
            "reconnect": [
                "enabled": false,
                "maxAttempts": -3,
                "backoffMs": 100
            ]
        ]

        let options = try NativeWebRTCController.parseConnectOptions(raw)

        XCTAssertEqual(options.connectionId, "test-connection")
        XCTAssertEqual(options.webrtcRequest.timeoutMs, 1_000)
        XCTAssertEqual(options.webrtcRequest.headers["Authorization"], "123")
        XCTAssertEqual(options.webrtcRequest.requestData?["mode"] as? String, "voice")
        XCTAssertTrue(options.waitForICEGathering)
        XCTAssertTrue(options.audioOnly)
        XCTAssertNil(options.audioCodec)
        XCTAssertEqual(options.videoCodec, "H264")
        XCTAssertFalse(options.media.voiceProcessing)
        XCTAssertFalse(options.media.startMicEnabled)
        XCTAssertEqual(options.media.preferredInputId, "built-in-mic")
        XCTAssertEqual(options.media.outputRoute, .speaker)
        XCTAssertTrue(options.media.outputRouteExplicit)
        XCTAssertFalse(options.reconnect.enabled)
        XCTAssertEqual(options.reconnect.maxAttempts, 0)
        XCTAssertEqual(options.reconnect.backoffMs, 250)
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

    func testWebRTCParseIceServersRejectsNonObjectEntry() {
        assertWebRTCInvalidArgument("iceConfig.iceServers[0] must be an object.") {
            _ = try NativeWebRTCController.parseIceServers([
                "iceConfig": [
                    "iceServers": ["not-an-object"]
                ]
            ])
        }
    }

    func testWebRTCParseIceServersRejectsBlankUrls() {
        assertWebRTCInvalidArgument("iceConfig.iceServers[0].urls is required.") {
            _ = try NativeWebRTCController.parseIceServers([
                "iceConfig": [
                    "iceServers": [
                        [
                            "urls": [" "]
                        ]
                    ]
                ]
            ])
        }
    }

    func testWebRTCParseIceServersReturnsEmptyWhenConfigMissing() throws {
        XCTAssertTrue(try NativeWebRTCController.parseIceServers([:]).isEmpty)
    }

    func testWebRTCParseNullableCodec() {
        XCTAssertNil(NativeWebRTCController.parseNullableCodec(nil))
        XCTAssertNil(NativeWebRTCController.parseNullableCodec("default"))
        XCTAssertNil(NativeWebRTCController.parseNullableCodec(" DEFAULT "))
        XCTAssertEqual(NativeWebRTCController.parseNullableCodec("opus"), "opus")
        XCTAssertEqual(NativeWebRTCController.parseNullableCodec("  aac  "), "aac")
    }

    func testWebRTCCanStartConnectionFromIdleAndError() {
        XCTAssertTrue(NativeWebRTCController.canStartConnection(from: .idle))
        XCTAssertTrue(NativeWebRTCController.canStartConnection(from: .error))
        XCTAssertFalse(NativeWebRTCController.canStartConnection(from: .connected))
    }

    func testWebRTCShouldResetBeforeConnectForStaleOrErrorState() {
        XCTAssertTrue(
            NativeWebRTCController.shouldResetBeforeConnect(
                from: .error,
                hasActiveConnectionIdentity: true
            )
        )
        XCTAssertTrue(
            NativeWebRTCController.shouldResetBeforeConnect(
                from: .idle,
                hasActiveConnectionIdentity: true
            )
        )
        XCTAssertFalse(
            NativeWebRTCController.shouldResetBeforeConnect(
                from: .idle,
                hasActiveConnectionIdentity: false
            )
        )
        XCTAssertFalse(
            NativeWebRTCController.shouldResetBeforeConnect(
                from: .connected,
                hasActiveConnectionIdentity: true
            )
        )
    }

    func testWebRTCDisconnectAndDiagnosticsAllowedFromError() {
        XCTAssertTrue(NativeWebRTCController.canDisconnect(from: .error))
        XCTAssertTrue(NativeWebRTCController.canInspectConnection(from: .error))
        XCTAssertFalse(NativeWebRTCController.canDisconnect(from: .idle))
        XCTAssertFalse(NativeWebRTCController.canInspectConnection(from: .idle))
    }

    func testWebRTCConnectPermissionValidationRejectsDeniedWhenStartMicEnabled() {
        assertWebRTCInvalidArgument("Microphone permission denied.") {
            try NativeWebRTCController.validatePermissionForConnect(.denied, startMicEnabled: true)
        }
    }

    func testWebRTCConnectPermissionValidationAllowsDeniedWhenStartMicDisabled() throws {
        XCTAssertNoThrow(try NativeWebRTCController.validatePermissionForConnect(.denied, startMicEnabled: false))
    }

    func testWebRTCConnectPermissionValidationAllowsGrantedWhenStartMicDisabled() throws {
        XCTAssertNoThrow(try NativeWebRTCController.validatePermissionForConnect(.granted, startMicEnabled: false))
    }

    func testWebRTCConnectPermissionValidationTreatsOmittedStartMicEnabledAsRequired() throws {
        let options = try NativeWebRTCController.parseConnectOptions([
            "webrtcRequest": [
                "endpoint": "https://voice.example.com/offer"
            ]
        ])

        XCTAssertTrue(options.media.startMicEnabled)
        assertWebRTCInvalidArgument("Microphone permission not determined.") {
            try NativeWebRTCController.validatePermissionForConnect(.prompt, startMicEnabled: options.media.startMicEnabled)
        }
    }

    private func assertWebRTCInvalidArgument(
        _ expectedMessage: String,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ block: () throws -> Void
    ) {
        do {
            try block()
            XCTFail("Expected NativeWebRTCControllerError", file: file, line: line)
        } catch let error as NativeWebRTCControllerError {
            XCTAssertEqual(error.code, .invalidArgument, file: file, line: line)
            XCTAssertEqual(error.message, expectedMessage, file: file, line: line)
            XCTAssertFalse(error.recoverable, file: file, line: line)
            XCTAssertNil(error.nativeCode, file: file, line: line)
        } catch {
            XCTFail("Unexpected error: \(error)", file: file, line: line)
        }
    }
}
