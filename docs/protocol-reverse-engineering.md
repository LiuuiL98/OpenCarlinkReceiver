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

### Create-socket branch: socketType 1 means local server socket

The create-socket handler is the obfuscated class `LK6/d`, method `a(byte[], int)`. The method compares the decoded `socketType` field with integer `1` before selecting the socket-thread branch.

- When `socketType == 1`, the handler constructs `LK6/f(port, channelId, channelTypeName)`. This subclass creates a Java `ServerSocket(port)`; its socket-acquisition method calls `ServerSocket.accept()`. The handler starts the thread and stores it in a `SparseArray` keyed by port. This is a local listening socket, not a request to connect outward to the phone at that port.
- When `socketType != 1`, the handler constructs `LK6/e(port, channelId, channelTypeName)`, a subclass of the shared `LK6/h` socket-read thread. Its socket-acquisition method resolves `127.0.0.1` and creates `new Socket(loopbackAddress, port)`, i.e. it connects to a local loopback listener on the receiver itself—not directly to the phone's IP. It also enables TCP_NODELAY and configures send/receive buffer sizes. This branch's connect target is now confirmed from the official DEX.
- In the server branch, `channelId == -1` is the expected value; the handler logs `Expect received sever socket channel id is -1, but received ...` when the value differs.
- Once the default socket/read-thread collection passes its readiness checks, the handler logs `all needed default socket ready`, updates the `usb_connected` state, and constructs `CarAuthClient` with `127.0.0.1` before calling `start()`.

This resolves the most important ambiguity in the current hardware trace: both observed records have `socketType=1` and `channelId=-1`, so the official code path identifies them as local server-socket requests for RTSP port 7236 and UIBC port 4321. The project must not implement these by blindly opening outbound client connections. We have not yet verified successful binds or the complete phone-side connection sequence on hardware, while `channelMessageType=2` is classified as `raw` by the official thread's `toString()` branch; the meaning of the outgoing record still needs bidirectional validation.

The log does **not** prove that the receiver should connect to either port. No socket is opened by the diagnostic path. The two distinct values should be traced through the official APK before any active connection is implemented.

### Create-socket branch: channel message type and client-thread behavior

A further method-level pass over `LK6/h`, the thread created for the non-server branch, resolves part of the previously unknown message-type field:

- Its `toString()` method compares the message-type field with integer `3`; the value `3` is labeled `ucar`, while the alternate branch is labeled `raw`. This is a direct observation from the official APK's branch and string constants.
- The captured value `channelMessageType=2` therefore follows the `raw` branch in that diagnostic representation. Do not interpret this as a video codec or as proof that the bytes are uncompressed image data; `raw` here is only the official socket-thread's transport/message-mode label.
- `LK6/h.run()` obtains a socket, wraps its input and output streams, reads channel data, and routes data through distinct `ucar channel` and `raw channel` paths. Those paths copy data and call the AOA USB bulk-transfer helper to send it back to the phone. This establishes that the thread participates in socket↔USB forwarding rather than being merely a passive TCP reader.
- The socket-acquisition path for `socketType != 1` is now confirmed as a TCP connection to `127.0.0.1:<port>`. What remains unresolved is which local component is expected to listen on each such port, how all numeric socket/message values are assigned, and the complete connect timing. Do not implement this branch until those dependencies are traced.

A related reverse path is visible in `LK6/f` after `ServerSocket.accept()` succeeds: it resolves the channel enum from the listening port, obtains a new value from a shared `AtomicInteger`, calls the create-socket sender `LK6/d.d(int, int, int, int)`, and assigns that channel ID to the shared socket-read thread. The shared counter is initialized to **16**, and `getAndIncrement()` means the first accepted channel receives ID 16, followed by 17, 18, and so on, assuming no earlier allocations. This ties local TCP acceptance to an AOA-side channel-ID/control update.

#### Four-argument create-socket sender and socket-type mapping

The sender `LK6/d.d(int, int, int, int)` logs arguments in the order `port`, `channelId`, `socketType`, `channelMsgType`, then writes a 10-byte body. In the `LK6/f` accept path, `socketType` is selected by a switch over the `ChannelType` enum ordinal. The decoded mapping is:

| Channel enum | Port | Outgoing socketType after local accept | Evidence |
|---|---:|---:|---|
| UIBC | 4321 | 4 | DEX enum-switch case |
| RTSP | 7236 | 3 | DEX enum-switch case |
| RTP | 15550 | 5 | DEX enum-switch case |
| AUTH | 57209 | 7 | DEX enum-switch case |
| CONTROL | 57219 | 2 | DEX enum-switch case |
| MEDIA | 57229 | 6 | DEX enum-switch case |
| SENSOR | 57239 | 8 | DEX enum-switch case |
| CERT | 57249 | 9 | DEX enum-switch case |
| CUSTOM / VIDEO / unmapped enum ordinal | varies | 2 (switch default) | DEX switch default; role-specific behavior still needs validation |

This mapping describes the **outgoing record emitted after the local listener accepts a TCP client**. It must not be confused with the incoming request's `socketType=1`, which selects the local `ServerSocket` branch. The sender's field order and the enum-switch values are now statically resolved; the meaning of the phone's response and the complete live sequence still require a known-good bidirectional hardware capture.
- The captured records both have `socketType=1`, so they do **not** instantiate `LK6/h` at all; the client-thread behavior above is adjacent protocol logic, not evidence that the observed RTSP/UIBC records connect outward.

This narrows the interpretation of the captured records: `channelMessageType=2` is classified as `raw` by the official thread's diagnostic representation, while `socketType=1` still selects the server-listener branch. Keep those two independent fields separate in any implementation.

The 10-second capture still produced only these two records, with repeated 500 ms idle intervals. This shows no additional readable record arrived at the selected Bulk IN endpoint during that capture window; it does not prove that no later message exists or that authentication was attempted.


## Call-chain trace: create-socket request to authentication startup (static DEX trace, 2026-10-12)

This section records the call path reconstructed from the official APK's `classes2.dex` instruction stream. Class names such as `LK6/d` are obfuscated; the conclusions below are tied to observed invokes, fields, strings, and branch structure rather than names alone.

### 1. Incoming create-socket record: `LK6/d.a(byte[], int)`

1. The method decodes the record's port, channel ID, socket type, and channel-message type, then logs `received create socket msg, port ...`.
2. The `socketType == 1` branch is the local-server path. It expects `channelId == -1`; a mismatch is logged. It resolves the channel enum with `ChannelType.fromPort(port)`, constructs `LK6/f(port, channelId, channelType.name())`, starts that thread, and stores it in a `SparseArray` keyed by port. A construction/start failure is caught and logged as `Start ServerSocketRead Thread Fail`.
3. The non-server branch validates the channel ID and message-type range before constructing `LK6/h`, starts the client socket read thread, and stores it in the same port-keyed collection. Invalid values are logged (`invalid channel id` / `invalid msg type`); failures are logged as `Start ClientSocketRead Thread Fail`.
4. After adding a thread, the handler walks the registered read-thread collection and checks for missing/null entries. Only the readiness branch logs `all needed default socket ready`. This is a collection/read-thread readiness gate, not proof that the phone has authenticated or that video is flowing.
5. On that readiness path, the code logs the `ShareLink` network-ready state, updates the `usb_connected` preference/state, constructs the `ShareLinkService` callback helper with `127.0.0.1`, creates `CarAuthClient`, and invokes `CarAuthClient.start()`. A boolean field guards part of this initialization path; its exact lifecycle across reconnects still needs to be checked before reproducing it.

**Important interpretation:** the two observed records (`RTSP=7236` and `UIBC=4321`, both `socketType=1`) enter the server-listener branch. The diagnostic app has not accepted TCP clients or reached this readiness/authentication path, so these are static call-path findings, not a claim of successful live session establishment.

### 2. Local TCP client accepted: `LK6/f.f()`

1. The thread calls `ServerSocket.accept()` and logs either `One client connected in ...` or `One client connected fail: ...`.
2. For an accepted socket, it enables `TCP_NODELAY` and sets send/receive buffer sizes.
3. It maps the listening port back to `ChannelType`, obtains a channel ID using a shared `AtomicInteger.getAndIncrement()` (static initialization starts at 16), chooses the outgoing `socketType` using the enum switch, and calls `LK6/d.d(port, channelId, socketType, channelMsgType)` to emit the 10-byte create-socket body over AOA.
4. It assigns the allocated ID to the associated `LK6/h` thread and starts/updates that thread's forwarding path. The exact phone-side reaction to this outgoing record remains unverified until a bidirectional hardware capture is available.

### 3. What this trace proves—and what it does not

- **Proven by static DEX flow:** create-socket records register per-port threads; server requests use `ServerSocket`; the readiness branch is followed by `CarAuthClient.start()`; accepted local TCP clients trigger an outgoing create-socket record and channel-ID assignment.
- **Not yet proven:** the S25 accepts our test accessory identity as a valid ICCOA head unit; either local TCP listener receives a client; the readiness gate is reached in the captured session; authentication succeeds; or a phone-side channel ID exactly matches the inferred outgoing assignment.
- **Next validation:** collect a time-correlated, redacted trace showing incoming AOA records, local listener accept/connect events, outgoing AOA record metadata (port/channel ID/socket type/message type only), and authentication stage/result logs. Do not capture or publish raw serial/PIN values, keys, nonces, certificates, or authentication payloads. Keep the receiver in read-only mode until the sequence is confirmed.


## Authentication call-chain trace: `CarAuthClient` to session-key confirmation (static DEX trace, 2026-10-12)

This trace follows the authentication code only; it does not send any messages or bypass authentication.

### 1. Auth channel connection and request start

- `CarAuthClient` constructs a `SocketChannel` for `ChannelType.AUTH` and installs a callback. The callback reports channel-connect success/failure through `CarAuthListener.onChannelConnectResult(...)`.
- `CarAuthClient.start()` first checks `mAuthChannel.isConnected()`. If already connected, it starts the channel with the stored remote address; exceptions report `ERROR_CONNECTIVITY` to the listener.
- The `agreeOnSecurityKeys(...)` method selects a `ClientKeyNegotiator.ConnectionType` branch (`NORMAL`, `QUICK`, or `QUICK_W_UC`), asks `ClientKeyNegotiator.generateKeyNegotiationInfo(...)` to construct an `AuthRequest`, wraps it using `UCarAuthProtocol.createAuthRequestMessage(...)`, and submits it through `SocketChannel.request(...)`. Exceptions are reported to the listener as `ERROR_AND_RESTART_NORMAL_CONNECTION`.
- The protocol builder identifies the request as category `AUTH`, message type `REQ`, method index `1`. The response parser requires category `AUTH`, message type `RES`, and method index `2` before parsing the protobuf body. `AuthConfirm` is a separate `SEND` message at method index `3`.

### 2. AuthResponse validation and key agreement

The response callback `CarAuthClient$2.completed(AuthResponse)` logs that client key-negotiation information was received, then calls `ClientKeyNegotiator.agreeOnSessionKey(response)`. The static DEX flow shows these stages:

1. `parseServerInfo(response)` extracts the server version, server authentication public key, its HMAC/MAC, agreement public key, agreement-key signature, random/nonce, result code, and server ID.
2. An ill-formed response returns `ERROR`. Server result codes can lead to `ERROR_FROM_SERVER`; work-mode negotiation failure has a separate `ERROR_WORK_MODE_NEGOTIATION` result.
3. For a normal connection, the code derives a peer-key alias from the server ID, obtains/encodes the relevant public keys, validates peer authentication and agreement-key material, derives the session key, and saves the peer record. If key agreement or saving the peer fails, it removes the peer record and returns an error.
4. A quick-connection branch uses a stored peer record and still performs key/session-agreement checks; failure does not silently become success.
5. The response callback then reports the `ClientKeyNegotiator.Result` and work-mode value to `CarAuthListener.onResult(...)`. The result is an explicit state outcome, not just “a response was received.”

Do not copy server keys, nonces, signatures, or IDs from a live session into logs or public issues. The names above describe the data fields, not their values.

### 3. Encrypted AuthConfirm and callback result

- `CarAuthClient.confirmSessionKey(...)` formats the confirmation context as `%s:%d`, encodes it as UTF-8, encrypts it through `SecurityManager.encrypt(...)`, wraps the ciphertext in `UCarProto.AuthConfirm`, creates the AUTH method-3 message, and sends it via `mAuthChannel.send(...)`.
- The send callback reports completion to `CarAuthListener.onSessionKeyConfirmed(client, success, selectedWorkMode)`. Its failure path logs `writing client connection info failed` and reports a failed confirmation.
- The service-side listener `com.ucarmd.f` forwards the confirmation result and screen-info string to a service helper, then closes the auth channel. This is a post-confirmation callback path; it is not evidence that a real session has passed it.
- The service listener also contains explicit recovery branches for normal-connection restart, user confirmation, connectivity retry, work-mode negotiation failure, and terminal failure. A retry branch posts a delayed retry on the main-thread `Handler`; the DEX includes distinct “retry count exhausted” outcomes.

### 4. What this means for OpenCarlinkReceiver

We now have a static, ordered authentication outline:

`AUTH SocketChannel connect → AuthRequest (REQ/1) → AuthResponse validation (RES/2) → server-info/key agreement → encrypted AuthConfirm (SEND/3) → confirmation callback`.

This is **not** an implementation recipe for fabricating authentication. The project must continue to treat the official authentication and certificate/key checks as mandatory. The immediate engineering task is to expose safe diagnostics for transport readiness and stage outcomes, while leaving secrets and payloads redacted. Only after real bidirectional captures establish the channel and callback sequence should protocol integration be attempted.

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
- The parser reads a 16-bit checksum at byte offset 18 and compares it with `Crc16.checksum(rawBytes, 0, 18)`; therefore the checksum covers exactly header bytes 0..17, excluding the checksum field itself. A mismatch raises a protocol exception.
- `ByteUtil` reads and writes multi-byte integers/shorts in big-endian order. The checksum field is consequently serialized big-endian by the byte-array path.
- `Crc16` initializes its CRC accumulator to zero and uses a reflected lookup table beginning `0000 C0C1 C181 0140 C301 03C0 ...`, consistent with the CRC-16/ARC (reflected 0xA001 polynomial, init 0, xorout 0) family. Treat CRC-16/ARC as the best-supported identification, and still add a known-good official header vector to confirm the implementation end-to-end before using it to accept live messages.
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

The static AOA handler maps the record's first 32-bit field through `ChannelType.fromPort(port)` and then creates/starts per-channel read handling. The two captured ports are RTSP and UIBC, while the authentication client separately selects `ChannelType.AUTH` (port 57209) and is passed the loopback address `127.0.0.1` by the readiness path. This means the captured 7236/4321 records should not be mistaken for the AUTH TCP endpoint. The shared channel counter starts at 16 in the static initializer, but the exact cross-process socket ownership and how the readiness condition is satisfied still require dynamic correlation.

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


## Post-authentication callback and remote-display boundary (static DEX trace, 2026-10-12)

The next call-site pass clarifies the boundary between authentication and the display startup path. This is still a static trace of the official APK; it does not imply that our test receiver has completed authentication.

### Authentication result handling

- `com.ucarmd.f.onResult(CarAuthClient, ClientKeyNegotiator.Result, int)` treats `OK` specially: it logs `key agreement OK` and calls `CarAuthClient.confirmSessionKey(...)`. Restart-normal, work-mode negotiation errors, connectivity failures, and other terminal results follow separate branches. Several non-success paths close the auth client and/or call the service's internal disconnect helper; do not collapse them into a generic success state.
- `com.ucarmd.f.onSessionKeyConfirmed(CarAuthClient, boolean, int)` branches on the confirmation boolean. If it is false, it skips the screen-info handoff and closes the auth channel. If true, it obtains screen info through `CarAuthClient.getScreenInfo()`, calls `com.ucarmd.h.a(int, String)`, then closes the auth channel.
- The concrete interface implementation for this Wi-Fi P2P path is `com.ucarmd.a`. In `com.ucarmd.b.onConnected(...)`, the official app constructs `com.ucarmd.a` with the connection parameters and passes it to `com.ucarmd.f` as the auth callback's handoff target. `com.ucarmd.a.a(int, String)` then calls the `ShareLinkService` helper that contains the `start rtsp client` log and remote-display initialization. This resolves the handoff for the observed P2P connection path: remote-display startup is gated by successful session-key confirmation, rather than by AOA attachment alone.

### Remote-display startup method

A separate `ShareLinkService` helper contains the log string `start rtsp client`. Its observed instruction sequence:

1. Logs the start message.
2. Calls `RemoteDisplayNative.init(applicationContext, "SYNERGY_TV")`.
3. Builds/updates the remote-display state object.
4. Parses the supplied screen-info string with `UCarScreenConfig.parseScreenInfo(...)`.
5. Stores the parsed screen configuration and passes the screen-info string to the remote-display object.

This proves that the official APK has a native remote-display initialization path driven by screen information. For the Wi-Fi P2P callback path described above, the handoff reaches this helper through `com.ucarmd.a.a(int, String)` only when session-key confirmation reports success. The APK also has other call sites / implementations, so do not generalize this exact sequence to every connection mode without tracing that mode separately.

### Implementation consequence

Keep the receiver's state machine split into distinct stages rather than starting RTSP or a display surface merely because AOA switched successfully:

`AOA attached → create-socket requests → local TCP clients accepted → default-channel readiness → AUTH channel connected → AuthRequest/AuthResponse key agreement → AuthConfirm result → screen-info handoff → remote-display initialization`

The arrows after the first AOA records are **expected code-path stages**, not stages observed in the current hardware trace. The current capture has only the RTSP/UIBC server-listener requests and no verified local accepts, readiness event, authentication result, or screen-info handoff. Before implementing active socket/auth/display behavior, obtain a redacted time-correlated trace that distinguishes listener bind/accept, outgoing AOA control records, auth result, and display startup. Never log raw serial/PIN values, keys, nonces, certificates, or auth payloads.


## AUTH channel socket role and port override (static DEX trace, 2026-10-12)

A further pass into `com.ucar.protocol.channel.socket.SocketChannel` resolves a critical ambiguity in the AUTH startup path.

- `CarAuthClient.<init>` constructs its inner channel callback with `ChannelType.AUTH` and both boolean constructor arguments set to `true`. That callback constructs `SocketChannel(ChannelType, boolean, boolean)`; the first boolean is passed to `NetChannel(ChannelType, boolean)`, whose `isServerChannel()` value selects server versus client startup. The second boolean initializes the socket channel's message-pool option.
- `CarAuthClient.start()` calls `mAuthChannel.start(513, mRemoteAddress)` after checking the channel's current state. In `SocketChannel.start(int, String)`, the supplied port is applied only when `ChannelType == CUSTOM`; AUTH is a predefined enum, so the supplied `513` is **not** an override for AUTH's port. The address is set to the supplied remote-address string, observed as `127.0.0.1`.
- `SocketChannel.start()` branches on `isServerChannel()`. The server branch invokes `startServerChannel(port, address, options)`; that method creates a `ServerSocket`, binds an `InetSocketAddress(address, port)`, invokes `onServerBound()`, and then waits in `accept()`.
- For `ChannelType.AUTH`, the enum port is **57209**. The inspected `SocketChannel.onServerBound()` method body is empty (a no-op), so the static evidence does **not** support the previous claim that this callback itself calls the AOA create-socket sender. The generic channel code binds the local server socket, then enters `accept()`; a separate callback or transport bridge would need to be identified before linking this listener to AOA control records.
- `CarAuthClient.start()` passes `513` and `127.0.0.1` to `SocketChannel.start(int, String)`, but the port argument is applied only for `ChannelType.CUSTOM`. AUTH remains on its predefined port 57209. The AUTH channel constructor uses the server-channel flag, and the generic start method dispatches to `startServerChannel(...)`, which binds a `ServerSocket` to the supplied address/port and then blocks in `accept()`.

This corrects an easy but important misreading: the AUTH channel is not shown connecting outward to `127.0.0.1:513`. However, we have **not** established how this particular AUTH listener is reached, whether it belongs to the same data path as the observed AOA RTSP/UIBC listeners, or whether an AOA create-socket record is emitted for it by another component. Do not synthesize an AUTH record or initiate authentication based only on the two currently captured RTSP/UIBC records.

### Revised expected connection stages

`AOA attached → RTSP/UIBC server-listener requests → default-channel readiness → AUTH local listener bound at 127.0.0.1:57209 → [unresolved bridge / client-connect trigger] → AUTH client accepted → AuthRequest/AuthResponse key agreement → AuthConfirm → screen-info handoff → remote-display initialization`

The AUTH bridge / client-connect trigger is now an explicit unresolved dependency. The stages after the captured RTSP/UIBC requests remain unobserved on hardware. In particular, distinguish **AUTH listener bound** from **AUTH client accepted**, and distinguish both from **session-key confirmation**. Log only state transitions, ports, channel IDs, booleans, elapsed time, and error class/message after redaction; never log credentials, key material, nonces, certificates, or authentication payload bytes.


### Correction note: AUTH `onServerBound()` callback (2026-10-12)

The earlier paragraph stating that `SocketChannel.onServerBound()` emits an AOA create-socket record was too strong and is superseded by the corrected trace above. In the inspected DEX, this method has an empty body. The generic `SocketChannel` server-start path binds and accepts TCP, but the bridge between the AUTH TCP listener and AOA control transport has not yet been located. Keep that link marked unresolved until a concrete caller or captured event proves it.


## Candidate AUTH-to-AOA bridge: hypothesis separated from proven behavior (2026-10-12)

The two socket mechanisms can now be placed side-by-side without claiming an unobserved callback:

- The AOA create-socket handler `LK6/d.a(byte[], int)` creates `LK6/f` (a local `ServerSocket`) for incoming `socketType=1` records. For non-1 socket types, it creates the client-thread path, which connects to `127.0.0.1:<port>`.
- Independently, `CarAuthClient.start()` starts a `SocketChannel` configured as a server channel on the predefined AUTH port 57209, bound to the loopback address supplied by the service.
- The generic `SocketChannel.onServerBound()` method body is empty; it is not the missing bridge.

**Working hypothesis, not yet proven:** if the peer sends an AOA create-socket record for AUTH with a non-server socket type, the AOA handler's loopback-client branch could connect to the AUTH `SocketChannel` listener at `127.0.0.1:57209`. This would explain how the USB/AOA transport and the separate local AUTH TCP service can meet without `onServerBound()` sending anything itself. However, no AUTH create-socket record or successful loopback connect appears in the current hardware capture, and this exact cross-component relationship has not been confirmed from a peer-side capture or an explicit call site.

### What would confirm or falsify this hypothesis

1. Capture the next session with only metadata: incoming AOA record port, channel ID, socket type, message type, timestamp; local bind/connect/accept result; and auth state transition.
2. If an AUTH record arrives, compare its port and socket type with the observed listener state. Do not log its raw payload beyond the already-safe numeric header fields.
3. Confirm whether the local connection target is `127.0.0.1:57209`, and whether it triggers `CarAuthClient$1.onConnect()` before the first AuthRequest.
4. If no AUTH AOA record arrives, inspect the peer-side control path and the readiness gate rather than fabricating a record or manually opening the socket.

Until these observations exist, keep the bridge as a hypothesis and keep active AUTH transport disabled in the receiver.


## Real-device trace: Galaxy S25 → Galaxy S9+ (2026-10-12)

Device pair: Samsung Galaxy S25 (Android 16 / One UI 8.5, sender) → Samsung Galaxy S9+ SM-G9650 (Android 10 / API 29, receiver running OpenCarlinkReceiver 0.2.5). The user-provided trace is from a real USB session on 2026-10-12. Identity strings and PIN values are intentionally not repeated here.

### Confirmed observations

1. Before AOA, the S9+ sees USB VID:PID `04E8:6860`. Standard AOA request 51 succeeds and reports protocol version 2. All six identity-field control transfers report success; request 53 (START_ACCESSORY_MODE) returns 0.
2. The device detaches as `04E8:6860` and reattaches as `18D1:2D01`, with two interfaces. This confirms AOA accessory-mode re-enumeration at the USB layer. It does **not** prove that the sender accepted the ICCOA identity or completed ICCOA authentication; the identity contains placeholder test values.
3. A read-only framed read on interface 0 / endpoint `0x81` captured exactly two records, both outer channel ID 1, each with a declared 10-byte body:
   - `00 00 1C 44 FF FF FF FF 01 02` → port 7236, requested channel ID -1, socket type 1, channel-message type 2.
   - `00 00 10 E1 FF FF FF FF 01 02` → port 4321, requested channel ID -1, socket type 1, channel-message type 2.
4. These map to the previously reverse-engineered RTSP (7236) and UIBC (4321) default socket requests. In the official implementation, incoming socket type 1 follows the server-listener path. The sentinel channel ID -1 is not by itself an error.
5. The framed reader then idled for about 10 seconds. A separate raw Bulk IN run performed afterward read 0 bytes over about 5 seconds (10 no-data/timeout returns, 0 other errors). This is consistent with an idle endpoint during that later window; it does not establish why the protocol stopped producing records, and the two runs must not be treated as one continuous capture.

### What remains unproven

- Whether the receiver creates local TCP listeners for 7236 and 4321.
- Whether any local client connects and is accepted, whether the receiver assigns a positive channel ID, and whether it sends the corresponding AOA response/control record.
- Whether the default-channel readiness gate is satisfied.
- Whether an AUTH AOA record or local bridge to `127.0.0.1:57209` exists, whether an AuthRequest is sent, or whether key confirmation succeeds.
- Whether RTSP negotiation or video/audio data begins.

### Next diagnostic step

Add a separately gated **socket lifecycle probe**, not an automatic protocol implementation. It should correlate monotonic timestamps for each received create-socket request with: listener bind success/failure, local client accept, assigned channel ID, outgoing AOA control-record metadata, default-channel readiness, AUTH listener bind/accept, and high-level auth/display state transitions. For each event, log only port, channel ID, socket/message type, elapsed time, result boolean, and redacted exception class/message. Never log raw AOA identity strings, USB PIN, certificate contents, key material, nonces, or authentication payloads.

Keep the probe opt-in and avoid creating listeners or writing AOA records during the existing read-only test. Validate framing and metadata parsing with unit tests first; then run a distinct hardware session. Do not synthesize an AUTH record or claim authentication/video success from the current trace.


## Experimental RTSP/UIBC socket-lifecycle probe (build 0.2.6-dev.1)

The app now has a separate, confirmation-gated test mode intended to test the next hypothesis from the 2026-10-12 S25 → S9+ trace. The existing read-only framed reader and raw Bulk IN test remain separate and unchanged in purpose.

- Only accepts create-socket requests on outer channel 1 with requested channel ID -1, socket type 1, and port 7236 or 4321. It ignores other ports/types rather than binding arbitrary requested ports.
- Attempts local listeners on 7236 (RTSP) and 4321 (UIBC), logging bind success/failure and local TCP accept/timeout.
- After a local client is accepted, it sends an 18-byte candidate AOA control frame on Bulk OUT: outer channel ID 1, body length 10, port, assigned channel ID starting at 16, outgoing socket type 3 (RTSP) or 4 (UIBC), and candidate message type 2. Outgoing socket-type mapping and initial channel ID come from static reverse engineering. The exact response message-type semantics are still a hardware-validation hypothesis; the encoder unit test validates byte layout, not peer acceptance.
- This probe does **not** forward TCP data to/from USB and does not implement AUTH, RTSP negotiation, UIBC payload handling, or media streaming. It is deliberately not a functional bridge. It is opt-in, warns before writing, limits itself to the two observed ports, and closes sockets at the end of the short test window.
- Expected useful evidence is the correlated sequence `AOA_SOCKET_REQUEST` → `TCP_LISTENER_BIND` → `TCP_ACCEPT` → `AOA_SOCKET_RESPONSE_WRITE`, including actual USB OUT byte count. If no accept occurs, the probe cannot validate the outgoing record yet; inspect the client connection path and listener bind errors. If a full 18 bytes are written, that confirms only host-side transfer completion, not that the S25 accepted the record.
- Never infer successful channel setup, authentication, or display startup solely from a successful bind or USB OUT write. Do not log raw AOA identity/PIN strings, keys, nonces, certificates, or authentication payloads.

The test `AoaCreateSocketFrameEncoderTest` verifies the candidate frame layout for both observed ports and rejects invalid channel IDs. Hardware results are still required to establish whether the candidate response is accepted by the S25.
