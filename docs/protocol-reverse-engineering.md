# ICCOA Carlink protocol reverse-engineering notes

This document consolidates findings from static inspection of the official Android APK and the OpenCarlinkReceiver hardware traces. It deliberately distinguishes observed facts from hypotheses. Do not treat candidate port names, field names, or guessed meanings as verified protocol behavior until the official call path or additional captures confirm them.

## Evidence levels

- **Static APK observation**: a class/method/constant or data-flow pattern was observed in the inspected APK.
- **Hardware observation**: bytes or USB events were captured on the Galaxy S9+ receiver with a Galaxy S25 sender.
- **Hypothesis**: a possible interpretation that still needs a call-site trace, controlled experiment, or independent capture.

## AOA transport

Static inspection of the official receiver indicates an 8-byte big-endian outer header:

- bytes 0..3: channel ID, accepted range 1..240
- bytes 4..7: declared body length, with an official upper bound observed at 64 MiB
- channel ID 1 selects the create-socket record decoder
- create-socket record minimum body length: 10 bytes; fields are read at offsets 0, 4, 8, and 9

The app uses a lower local allocation cap (currently 1 MiB). USB reads must accumulate short positive reads and must not dispatch partial headers or bodies as complete records.

## Captured hardware fixture (2026-10-12)

Device: Samsung SM-G9650, Android 10/API 29. Sender: Samsung S25. After AOA v2 request 53, USB re-enumerated from 04E8:6860 to 18D1:2D01. The receiver granted USB permission and read endpoint 0x81.

Two records were observed repeatedly, including during a 10-second framed capture:

```text
header: 00 00 00 01 00 00 00 0A
body:   00 00 1C 44 FF FF FF FF 01 02

header: 00 00 00 01 00 00 00 0A
body:   00 00 10 E1 FF FF FF FF 01 02
```

Observed decoded fields according to the current static-analysis-derived decoder:

| Field offset | Decoder field | Record 1 | Record 2 | Confidence |
|---:|---|---:|---:|---|
| 0 | `port` | 7236 | 4321 | field extraction observed; business role not independently verified |
| 4 | `channelId` | -1 (raw `0xFFFFFFFF`) | -1 (raw `0xFFFFFFFF`) | raw value observed; semantic meaning still needs call-path confirmation |
| 8 | `socketType` | 1 | 1 | extraction observed; enum meaning not confirmed |
| 9 | `channelMessageType` | 2 | 2 | extraction observed; enum meaning not confirmed |

### Confirmed port classification from the official APK

A second pass over the official APK's DEX instructions resolved the two observed port values against the enum initializer for `com.ucar.protocol.channel.ChannelType`. The initializer constructs these entries (port values are decimal):

| Official enum | Port | Status |
|---|---:|---|
| `CUSTOM` | 0 | Static APK constant |
| `UIBC` | 4321 | Static APK constant; matches hardware record 2 |
| `RTSP` | 7236 | Static APK constant; matches hardware record 1 |
| `RTP` | 15550 | Static APK constant |
| `AUTH` | 57209 | Static APK constant |
| `CONTROL` | 57219 | Static APK constant |
| `MEDIA` | 57229 | Static APK constant |
| `SENSOR` | 57239 | Static APK constant |
| `CERT` | 57249 | Static APK constant |
| `VIDEO` | 57259 | Static APK constant |

The create-socket record handler calls `ChannelType.fromPort(port)`. Therefore, the port field values in the captured records map to the official enum names: record 1's port 7236 maps to `RTSP`; record 2's port 4321 maps to `UIBC`. This is stronger than matching a port list found elsewhere because the mapping is present in the inspected APK itself.

The handler also contains the diagnostic text `Expect received sever socket channel id is -1, but received`, and the captured second u32 is `0xFFFFFFFF` (-1). This supports the interpretation that -1 is an expected sentinel in this create-socket path. The exact relationship between this field and the later channel IDs still needs to be reconstructed from the surrounding branches before implementing it.

The handler's code path also contains a readiness log, `all needed default socket ready`, followed by references to `ShareLinkService` and `CarAuthClient.start()`. This establishes code-level links between create-socket processing, readiness handling, and authentication startup, but does not by itself prove that the observed session reached the readiness branch. The diagnostic app remains read-only and does not open these sockets.

The log does **not** prove that the receiver should connect to either port. No socket is opened by the diagnostic path. The two distinct values should be traced through the official APK before any active connection is implemented.

The 10-second capture still produced only these two records, with repeated 500 ms idle intervals. This shows no additional readable record arrived at the selected Bulk IN endpoint during that capture window; it does not prove that no later message exists or that authentication was attempted.

## Sender-side Android activity evidence

The previously collected S25 system log contains an accessory-attach launch of `com.samsung.android.carlink/.connection.iccoa.IccoaUsbConnectionActivity` at 2026-10-12 00:11:29.711. The activity was requested/resumed, then the system logged `wm_finish_activity` at 00:11:29.731 and destruction at 00:11:29.745. This is evidence that the phone's AOA accessory matching reached the Samsung ICCOA USB activity, but the activity ended almost immediately. It is **not** evidence of successful ICCOA authentication or a live Carlink session.

This phone-side observation must be interpreted alongside the official activity call path below. The activity is a short-lived dispatcher and calls the connection manager before finishing on the normal path, so its quick exit is not by itself evidence of failure. The remaining question is whether the connection manager accepted the accessory identity and continued into the transport path. Keep test placeholder identity values clearly distinguished from a real certified head unit.

## Sender-side USB activity and accessory validation

Method-level inspection of `IccoaUsbConnectionActivity` shows that `onCreate()` calls `w0(...)`; the latter checks onboarding/permission state, invokes the connection manager with protocol string `iccoa`, and then calls `finish()`. The connection manager's `startAOAConnect` path delegates to the ICCOA connection implementation. Therefore, the ActivityManager sequence in the S25 log—launch, app-request finish, destroy within roughly 34 ms—is consistent with the activity handing off work and finishing; it cannot be used alone to conclude that ICCOA rejected the connection.

The ICCOA connection implementation's USB-AOA path calls `UsbManager.getAccessoryList()` and checks the accessory's manufacturer against `ICCOA` and model against `CarLink`. It also obtains the accessory serial string and splits it on semicolons before constructing the internal car-device descriptor. A failure path logs `wrong createAOACarDevice`; the same class has a distinct log `ICCOA not support ADB connect` for its ADB-connect path.

Implications for our test:
- The public descriptor fields `manufacturer=ICCOA` and `model=CarLink` match the two explicit string comparisons seen in the APK.
- That is only the first gate. The serial field must also conform to the official parser's six-segment expectations; our synthetic values are not proof of a valid official head-unit identity.
- The current S25 system log contains ActivityManager lifecycle events but no corresponding app-tagged `CLINK/IccoaConnection` or `AOAConnectManager` lines in the captured window. It therefore cannot tell us whether serial validation succeeded or why any later connection attempt stopped.
- Do not publish or log the six serial segments or `usb_pin_code` raw. For the next capture, export only redacted app-tagged log lines and stage/result messages, with identity payloads omitted.

## Official APK classes to trace

The following names were identified during prior static analysis and are the next code-path anchors:

- AOA setup/transport: `AOAHostSetup`, `AOAConnectManager`, `AOAReadThread`, `AOAPreambleBuilder`
- Business protocol: `UCarProto`, `MessageHeader`, `ProtocolParser`, `Crc16`
- Authentication/certificates: `UCarAuthProtocol`, `UCarAuthService`, `AuthRequest`, `AuthResponse`, `AuthConfirm`, `CarCertificate`

Previous static observations:

- `MessageHeader` rejects inputs shorter than 20 bytes.
- A CRC value is read at offset 18 and a mismatch raises a protocol exception. The full CRC coverage, byte order, initial value, final XOR, and message-length relationship still need confirmation.
- The CRC lookup table begins `0000 C0C1 C181 0140 C301 03C0 ...`, consistent with a reflected 0xA001-family implementation; do not finalize a named CRC variant from this prefix alone.
- Candidate channel/port constants previously identified: AUTH 57209, CONTROL 57219, MEDIA 57229, CERT 57249. Treat these as static-analysis candidates until their use sites and actual socket endpoints are traced.
- Prior analysis indicated `UCarAdapter.startUCarChannel` starts Control/Sensor/CarCert/audio components, while `ShareLinkService` independently starts `UCarAuthService`. Confirm the exact sequence and prerequisites from the official call graph.

These candidate ports (57209, 57219, 57229, 57249) are distinct from the captured create-socket record values 7236 and 4321. Do not assume they refer to the same namespace or stage.

## Authentication call path recovered from DEX instructions

This section is based on method-level inspection of `classes2.dex`, not just string-pool matches.

### Message construction

`UCarAuthProtocol` builds three authentication message types in category `AUTH`:

| Operation | Message type | Method index | Protobuf body |
|---|---|---:|---|
| Client key negotiation request | `REQ` | 1 | `UCarProto.AuthRequest` |
| Server key negotiation response | `RES` | 2 | `UCarProto.AuthResponse` |
| Session-key confirmation | `SEND` | 3 | `UCarProto.AuthConfirm` |

The request/response parsers validate the message category/type before parsing the body as the corresponding Protobuf message. `UCarAuthProtocol.init()` registers the AUTH method-index names. Certificate messages are handled by a separate `UCarCertProtocol` in category `CERT`, with its create-message path using method index 1.

### Client-side sequence

The recovered call path in `com.share.connect.security.CarAuthClient` and `ClientKeyNegotiator` is:

1. `CarAuthClient` creates an auth channel using `ChannelType.AUTH` and retains a `ClientKeyNegotiator` and `SecurityManager`.
2. `CarAuthClient.start()` starts the auth channel if it is not already connected.
3. `agreeOnSecurityKeys(...)` asks `ClientKeyNegotiator.generateKeyNegotiationInfo(...)` to create an `AuthRequest`, wraps it using `createAuthRequestMessage`, and sends it with `SocketChannel.request(...)`.
4. The response callback converts the reply through `parseAuthResponseMessage`, reads the response's version/work-mode fields, and calls `ClientKeyNegotiator.agreeOnSessionKey(...)`.
5. The negotiator parses server information including authentication public key, authentication-key HMAC, agreement public key/signature, nonce, result, and server ID. The method references key lookup, peer authentication/agreement-key verification, ECDH-style agreement-key generation, session-key agreement, and peer-record persistence. Failure branches include malformed server response, server error, work-mode negotiation failure, and session-key agreement/persistence failure.
6. `confirmSessionKey(...)` formats a session confirmation string, UTF-8 encodes and encrypts it through `SecurityManager.encrypt(...)`, places the ciphertext in `UCarProto.AuthConfirm.cipher`, and sends an AUTH `SEND` message through the auth channel.

These observations establish the shape of the official authentication flow; they do **not** provide a valid real-car identity, PIN, key, certificate, or successful peer transcript. Do not log or commit PINs, AOA identity fields, key material, nonces, or authentication payloads from real sessions.

### Relation to the captured AOA records

The static AOA handler maps the record's first 32-bit field through `ChannelType.fromPort(port)` and then creates/starts per-channel read handling. The two captured ports are RTSP and UIBC, while the authentication client separately selects `ChannelType.AUTH` (port 57209) and is passed the loopback address `127.0.0.1` by the readiness path. This means the captured 7236/4321 records should not be mistaken for the AUTH TCP endpoint. The exact cross-process socket ownership and how the readiness condition is satisfied still require dynamic correlation.

## Implementation plan

1. Preserve the hardware records as regression fixtures and decode outer framing without active behavior.
2. Trace the official create-socket record handler from parser to socket creation, including how the first u32, second u32, and trailing bytes are consumed.
3. Trace the official channel/port constants to their actual bind/connect calls and service startup order.
4. Reconstruct `MessageHeader` and `Crc16` with unit tests using known-good captured messages or official parser fixtures. Test malformed lengths and CRC failures.
5. Map authentication messages and certificate operations from call sites; preserve certificate validation and error handling rather than bypassing them.
6. Only after the transport semantics and message format are verified, implement one isolated, opt-in connection step and capture both directions. Do not send guessed auth/business messages.

## Safety and evidence policy

- No guessed socket connections, authentication payloads, certificate bypasses, or fabricated success states.
- Keep raw observations, decoder output, and semantic interpretation as separate log fields.
- Every new parser behavior should have deterministic unit tests; every active transport step should be separately opt-in and report success only when the peer confirms it.
- The Android USB adapter and hardware session require real-device validation; CI cannot simulate USB enumeration or phone-side protocol behavior.
