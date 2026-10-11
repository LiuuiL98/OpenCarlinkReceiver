# ICCOA v1.5.3 static-analysis notes

Source artifact: `ICCOA - v1.5.3_202312071948_d8ad20b.apk`. This is an evidence log. It distinguishes observations from the supplied APK's DEX code from hypotheses that still need runtime validation.

## 1. Confirmed class and method inventory

DEX metadata places these classes in the supplied APK:

- AOA transport: `com.ucar.connect.aoa.AOAHostSetup`, `AOAPreambleBuilder`, `AOAConnectManager`, and nested `AOAReadThread` / `AOAConnectThread`.
- Message framing: `com.ucar.protocol.MessageHeader`, `ProtocolParser`, `Crc16`.
- Authentication: `com.ucar.protocol.UCarAuthProtocol`, `UCarProto.AuthRequest`, `AuthResponse`, `AuthConfirm`, and `CarCertificate`.

The DEX method table confirms these signatures exist:

- `AOAPreambleBuilder.build(String, String, String, String, String, String): String`
- `AOAPreambleBuilder.encodeCarName(String): String`
- `AOAPreambleBuilder.encodeGenericString(String, int): String`
- `AOAPreambleBuilder.encodeVersion(String): String`
- `AOAHostSetup.changeToAccessoryMode(): boolean`
- `AOAHostSetup.getProtocolVersion(): boolean`
- `AOAHostSetup.sendIdentityStrings(): boolean`
- `AOAHostSetup.startAccessoryMode(): boolean`
- `AOAHostSetup.bulkTransferIn(byte[], int): int`
- `MessageHeader.createMessageHeaderFromBytes(byte[]): MessageHeader`
- `MessageHeader.writeBytesTo(ByteBuffer): void`
- `MessageHeader.writeBytesTo(byte[]): void`
- `MessageHeader.updateLength(int): void`
- `MessageHeader.updateBodyLength(int): void`
- `MessageHeader.updateChecksum(): void`
- `MessageHeader.toTmpBytes(): byte[]`
- `Crc16.checksum(ByteBuffer, int, int): short`
- `Crc16.checksum(byte[], int, int): short`
- `UCarAuthProtocol.createAuthRequestMessage(AuthRequest): UCarMessage`
- `UCarAuthProtocol.createAuthResponseMessage(AuthResponse, int): UCarMessage`
- `UCarAuthProtocol.createAuthConfirmMessage(AuthConfirm): UCarMessage`
- `UCarAuthProtocol.parseAuthRequestMessage(UCarMessage): AuthRequest`
- `UCarAuthProtocol.parseAuthResponseMessage(UCarMessage): AuthResponse`
- `UCarAuthProtocol.parseAuthConfirmMessage(UCarMessage): AuthConfirm`

## 2. New bytecode-level findings

The supplied DEX instruction bodies were inspected using a local instruction-width decoder. These findings are limited to direct call sites, constants and field accesses visible in those method bodies; this is not a full Java decompilation.

### 2.1 Message header

- `MessageHeader.writeBytesTo(ByteBuffer)` checks that at least `0x14` (20) bytes remain before writing the header.
- The write path serializes `mLength`, `mSeqId`, and `mTimestamp` with `ByteBuffer.putInt`, then calls `writeCmdDescription`, writes a short field, computes CRC16 over the first 18 bytes, and writes the resulting short.
- The byte-array overload uses the same 20-byte minimum, calls `ByteUtil.writeIntToBytes` for the three integer fields, calls `writeCmdDescription`, writes a short at offset 16, calls `Crc16.checksum(headerBytes, 0, 18)`, and writes the checksum at offset 18.
- `createMessageHeaderFromBytes(byte[])` rejects buffers shorter than 20 bytes, reads integer fields from offsets 0, 4 and 8, reconstructs the command description, reads short fields at offsets 16 and 18, and compares the stored checksum with a calculated CRC16. A mismatch raises `ProtocolException` with an “Invalid header, checksum expect … actual …” message.
- `updateLength(int)` rejects values below 20 and recalculates the checksum. `updateBodyLength(int)` rejects negative body lengths and sets the total length to body length plus 20 before recalculating the checksum.
- `updateSeqId(int)` only permits sequence ID updates when the message type is `RES`, and rejects sequence IDs less than or equal to zero.

The three integer fields and CRC coverage are now evidenced by code paths. The exact byte order of the custom `ByteUtil` helpers and the meaning/encoding of every command-description field still need to be confirmed before writing a compatible serializer.

### 2.2 CRC16

Both overloads, `checksum(ByteBuffer, int, int)` and `checksum(byte[], int, int)`, use a shared `CRC16_TABLE` lookup table and process the requested range. The header writer explicitly invokes the byte-array overload with offset 0 and length 18. Do not substitute a generic CRC-16 variant yet: the initial state, reflected/non-reflected update details, and final representation must be reproduced and checked against known header bytes.

### 2.3 Authentication message wrapper

The official `UCarAuthProtocol` methods construct messages through a common builder:
- `newAuthMessageBuilder()` sets the configured source device and category `AUTH`.
- Auth request uses message type `REQ`; auth response uses `RES`; auth confirm uses `SEND`.
- These builders set a method index and then call `buildProtobufMessage`. The response path additionally calls `updateSeqId`.
- Parsing methods check the expected message type, get the message body, and invoke the corresponding protobuf `parseFrom` method. Protobuf parse failures are wrapped as `ProtocolException`.

This establishes the wrapper structure, but not the numeric method indices, protobuf field semantics, certificate checks, or the full authentication state machine.

### 2.4 AOA preamble builder

`AOAPreambleBuilder.build` formats six encoded values using the literal template `%s;%s;%s;%s;%s;%s`. It calls `encodeGenericString` with length arguments 12 and 8 for generic fields, `encodeCarName` for the car-name field, `encodeVersion` for the version field, and then encodes the remaining generic fields. The car-name method uses UTF-8 byte length and truncates long values with an ellipsis; the version method splits a dotted version string and formats two numeric components.

The six-part layout is verified. The correct OEM values and the exact role of each preamble field are not recovered by this alone; project placeholders must not be treated as valid Samsung/OEM provisioning data.

## 3. What the captured USB bytes tell us

The 2026-10-12 trace after AOA request 53 re-enumerated as `18D1:2D01`. It recorded four successful reads from endpoint `0x81` on interface 0:

```text
00 00 00 01 00 00 00 0A
00 00 1C 44 FF FF FF FF 01 02
00 00 00 01 00 00 00 0A
00 00 10 E1 FF FF FF FF 01 02
```

Read result lengths were 8, 10, 8 and 10 bytes (36 bytes total), followed by 10 timeouts and no other USB errors. No single read is long enough to be a complete 20-byte `MessageHeader`. Pairing adjacent reads yields 18-byte sequences, which are still shorter than the header. This is evidence that the reads cannot be passed individually to `createMessageHeaderFromBytes`; it does not yet prove whether the data represents split UCar frames, another handshake layer, or fixed-size protocol records. A stream reassembler/parser must first account for the actual transport framing.

No Samsung Carlink confirmation appeared. These bytes must not be labelled as a specific command or error until the parser and transport framing are validated.

## 4. AOA interface selection

For Android AOA PID `0x2D01`, the standard accessory interface is interface 0 and the second interface is ADB. The prototype's interface 0 / Bulk IN `0x81` selection is consistent with that layout. The trace also found Bulk OUT endpoint `0x01`; the prototype intentionally has not sent speculative business-protocol data.

## 5. Next steps

1. Confirm the exact `ByteUtil.read/writeIntToBytes` and `read/writeShortToBytes` endianness.
2. Reproduce the custom CRC16 routine and test it against a complete known-good header or an official receiver capture.
3. Inspect `ProtocolParser` and `UCarMessageBuilder` to establish how the 20-byte header and body length delimit a stream.
4. Add a read-only reassembler that preserves raw bytes and reports candidate header/checksum results without transmitting anything.
5. Only after framing is validated, trace the authentication state machine and certificate handling.
6. Do not hard-code fabricated OEM IDs, pairing codes, private keys, certificates or guessed Bulk OUT commands. Keep raw HEX capture opt-in because future frames may contain identifiers or authentication material.

## 6. Evidence boundary

Confirmed from the supplied APK's DEX: class/method inventory, 20-byte minimum header, three integer fields at offsets 0/4/8, command-description writer call, a short at offset 16, CRC16 over bytes 0–17 stored at offset 18, total-length checks, and the auth message wrapper's type/category/builder calls.

Still not confirmed: custom integer/short byte order, complete command-description field layout, exact CRC polynomial/initial/final details, numeric authentication method indices, protobuf field mapping, certificate validation, complete stream framing, and the full authentication exchange. No business-protocol code or APK was changed/built during this analysis step.


## 7. Endianness confirmed from ByteUtil

A second bytecode pass inspected `ByteUtil.readIntFromBytes`, `readShortFromBytes`, `writeIntToBytes` and `writeShortToBytes`. The read paths combine bytes using shifts of 24, 16, 8 and 0 bits for integers, and 8 and 0 bits for shorts; the write paths extract those same high-to-low byte positions. This confirms **big-endian/network byte order** for these protocol integer and short helpers.

Consequently, the first integer in a complete 20-byte header is decoded as a big-endian total length, the next as sequence ID, and the third as timestamp. This does not make the current 8/10-byte reads complete headers: they still need transport-level framing/reassembly before parsing.


## 8. Follow-up: identify the actual transport dispatcher

The DEX method inventory shows that `com.ucar.protocol.ProtocolParser` is **not** the stream/frame parser suggested by its class name. Its methods are limited to version conversion helpers: `getMajorVersion`, `getMinorVersion`, `parseVersionToByteArray`, and `parseVersionToString`. It should not be treated as the missing USB frame parser.

The official APK's `com.ucar.connect.aoa.AOAConnectManager` has a different, transport-oriented surface:
- `handleCreateSocket(byte[], int)`
- `getReadThreadByChannelId(int)`
- `startClientSocketThread(int, String, int, int)`
- `startServerSocketThread(int, String, int)`
- `checkNetworkReady()`
- `notifyDisconnected()`

Its nested `AOAReadThread.run()` reads the AOA channel, while the manager contains socket creation and per-channel read-thread lookup. This suggests the AOA transport may be used to bootstrap or carry channel/socket setup data, after which additional traffic is routed by channel. This is a **structural inference from method names and call surfaces**, not proof of the exact wire message layout.

### Updated next target

Prioritize the actual read path and dispatch chain:
1. `AOAConnectManager$AOAReadThread.run()`
2. the private byte-read method called by that thread
3. `AOAConnectManager.handleCreateSocket(byte[], int)`
4. `MessageHeader.createMessageHeaderFromBytes(byte[])`
5. `UCarMessageBuilder` / message body serialization references if present in the APK

Do not build a generic parser based solely on the class name `ProtocolParser`. First recover the call chain that takes raw USB bytes and decides whether they are headers, socket-control records, or channel payloads.


## 9. Read-thread body confirms an 8-byte AOA transport prefix

A further bytecode pass inspected `AOAConnectManager$AOAReadThread.run()` and `handleCreateSocket(byte[], int)`. This is the most important new result in this pass:

- `AOAReadThread.run()` allocates/uses `mMsgHead` and calls `AOAHostSetup.bulkTransferIn(mMsgHead, ...)` with an 8-byte head buffer.
- After the head read succeeds, it calls `ByteConvert.bytesToInt(mMsgHead, 0)` and `ByteConvert.bytesToInt(mMsgHead, 4)` to decode two integer fields.
- It then allocates/uses `mMsg`, performs another Bulk IN read for a body buffer, and passes the body plus an integer argument through the manager's internal dispatch method. The read loop subsequently looks up a `SocketReadThread` by channel ID and forwards data to that socket thread with `writeData(byte[], int, int)`.
- `handleCreateSocket(byte[], int)` reads two integer values from offsets 0 and 4, and unsigned-byte values from offsets 8 and 9. It logs those values as `port`, `channelId`, `socketType`, and `channelMsgType`, then starts either a local server socket thread or a local client socket thread depending on the decoded values.

This gives us a concrete distinction between two layers: the AOA reader begins with an **8-byte transport header**, while `MessageHeader` is a separate **20-byte UCar protocol header**. It is therefore not justified to interpret the first 20 bytes read from Bulk IN as one `MessageHeader` without first accounting for the AOA transport framing and the body/channel dispatch path.

### Important caveat about the current capture

The prototype's four read results (8, 10, 8, 10 bytes) were generated by a diagnostic read loop that does not reproduce the official receiver's exact 8-byte-header-then-body read sequence. Thus, the observed 8/10-byte packets cannot be mapped directly to the official `AOAReadThread` fields. We need a new capture that records read request sizes and actual return lengths separately, ideally using the official receiver as the known-good endpoint, before trying to decode these bytes.

### Updated investigation target

1. Recover the exact length/offset logic in `AOAReadThread.run()`, including how it validates the second 32-bit header value and chooses body-read size.
2. Recover the internal dispatch method called by the read thread and its argument order.
3. Compare that framing to `handleCreateSocket`'s offsets 0, 4, 8 and 9.
4. Only then decide whether the 20-byte `MessageHeader` belongs inside a channel payload or to another protocol layer.


## 10. Layer separation and capture plan for the next experiment

The public Android Open Accessory documentation specifies AOA control requests and says that after switching modes, the accessory communicates over Bulk IN/OUT endpoints using its own communication protocol. It does **not** define ICCOA's post-enumeration 8-byte transport header. The 8-byte header described in section 9 is therefore an implementation detail recovered from the official ICCOA receiver, not part of the standard AOA specification.

### Current evidence table

| Layer | Evidence-backed fact | Still unknown |
|---|---|---|
| USB/AOA enumeration | 18D1:2D01 is accessory + ADB; interface 0 is the standard accessory interface | Whether ICCOA's app selection/launch handshake is accepted |
| ICCOA AOA transport | Official read thread first requests an 8-byte buffer, decodes two 32-bit values, then performs a second body read | Integer byte order, exact field meanings, validation rules and body-length formula |
| Socket/channel control | handleCreateSocket reads 32-bit values at offsets 0/4 and bytes at offsets 8/9 | Full record length, enum values, whether this record is carried as a body or a channel payload |
| UCar message layer | MessageHeader uses a 20-byte header; CRC covers first 18 bytes and is stored at 18 | How UCar messages are carried over the AOA transport/socket channels |

### Required next capture

A useful capture must record each USB read **as a pair of values**: requested buffer length and returned byte count, alongside exact returned bytes and a monotonic timestamp. Also record:
- interface number and endpoint address;
- the time of each USB attach/detach and VID/PID;
- whether the official ICCOA receiver or the experimental receiver owns the USB connection;
- the first read after opening the AOA interface and every subsequent read until at least one body read completes;
- whether any Bulk OUT writes occur, including requested length and result, without altering their payload.

Do not concatenate arbitrary reads into candidate 20-byte UCar headers. First establish the 8-byte transport header's field byte order and length semantics. If testing the official receiver, capture only traffic that the user is authorized to inspect and redact identifiers or authentication material before sharing.

### Read-only decoder acceptance criteria

Before adding a parser to the app, it must:
1. parse a complete 8-byte transport header without assuming unverified field meanings;
2. reject impossible or oversized body lengths before allocating memory;
3. distinguish timeout, short read, disconnect, malformed header and valid zero-length body;
4. preserve raw read boundaries in diagnostic output;
5. include unit tests for big/little-endian candidates and boundary values, but select one byte order only when supported by bytecode or known-good capture;
6. never transmit a response or guessed command as part of parser tests.

This capture is the gating item for a safe implementation. The current evidence is sufficient to describe the official read sequence, but not enough to write a compatible body-length parser or send business messages.


## 11. Read-only transport-header probe in the diagnostic build

The diagnostic UI now records the requested Bulk IN buffer length separately from the actual returned byte count. For reads of at least eight bytes, it also records the first eight raw bytes and both big-endian and little-endian interpretations of the two 32-bit words.

This is deliberately a probe, not a decoder: the output does not label either word as a length, channel ID, or command, and it does not allocate a body buffer or transmit any response. The official read-thread structure supports investigating an eight-byte prefix, but the available evidence still does not establish the prefix's byte order or length formula.

After collecting a new trace, compare the two candidate interpretations across several consecutive reads and correlate them with the official receiver's first-header/body-read sequence. Do not choose an interpretation from one sample alone.


## 12. Follow-up bytecode pass: socket bridge and dispatch

A second targeted pass inspected the complete instruction bodies for `AOAConnectManager$AOAReadThread.run()`, `AOAConnectManager.handleCreateSocket(byte[], int)`, the synthetic dispatch accessors, `SocketReadThread.run()`, `SocketReadThread.writeData(byte[], int, int)`, and `UCarMessageBuilder.buildProtobufMessage(...)`.

### 12.1 Dispatch is a socket bridge, not a direct UCar parser

- The read thread invokes the manager's synthetic `access$100(manager, body, value)`; that accessor immediately delegates to `handleCreateSocket(body, value)`.
- For the non-create-socket path, it invokes `access$200(manager, channelId)`, which delegates to `getReadThreadByChannelId(channelId)`.
- If a matching `SocketReadThread` exists, the read thread forwards the received body and associated integer arguments to `SocketReadThread.writeData(byte[], int, int)`.
- `writeData` writes the supplied byte range to a `BufferedOutputStream` and flushes it. This is direct evidence that the AOA transport forwards payload data into a socket stream; it does not parse each forwarded payload as a `MessageHeader` in this method.

### 12.2 SocketReadThread owns the network-side stream

The bytecode for `SocketReadThread.run()` shows it waiting for/obtaining a socket, wrapping the socket input and output streams in `BufferedInputStream` and `BufferedOutputStream`, and calling `peerCreateProxyClientSocket()`. This supports a USB-to-socket proxy/bridge model: AOA carries channel setup/data, and each channel can be connected to a network socket. The exact application-level payload protocol still depends on the channel and must be traced separately.

### 12.3 Create-socket record layout

`handleCreateSocket(byte[], int)` checks that the input record is at least 10 bytes before reading:
- a 32-bit integer at offset 0;
- a 32-bit integer at offset 4;
- an unsigned byte at offset 8;
- an unsigned byte at offset 9.

The implementation logs these decoded values as `port`, `channelId`, `socketType`, and `channelMsgType`, and branches into server- or client-socket creation logic. Since the integer reads use `ByteConvert.bytesToInt(byte[], int)`, the previously confirmed big-endian helper applies to the two 32-bit fields. The minimum 10-byte check is confirmed; this pass does not establish that every record is exactly 10 bytes or fully document enum values.

### 12.4 Protobuf builder serialization path

`UCarMessageBuilder.buildProtobufMessage(GeneratedMessageLite)`:
1. sets the data format to `PB3`;
2. serializes the protobuf using `toByteArray()`;
3. builds a `CmdDescription` from source device, data format, message type, category, and method index;
4. constructs a `MessageHeader` and then a `UCarMessage` containing the header and serialized bytes.

This confirms that the UCar wrapper is a header-plus-protobuf-body format. It does not establish how such UCar messages are transported over a particular AOA socket channel.

### Updated implementation consequence

Do not treat every AOA body as a UCar message. First reproduce the AOA transport's header/body read sequence and socket-creation/data-forwarding behavior. Separately trace which socket channel, if any, carries UCar messages and where that stream is decoded. Keep these two framing layers separate in both code and logs.


## 13. Extracted protocol schema from the official APK

The APK itself packages `ucar.proto` (alongside generated protobuf classes in `classes7.dex`). This is direct source evidence and gives us field names and wire types without inferring them from obfuscated call sites. It is the schema used by the packaged generated classes, although the schema alone does not prove which messages are exchanged first at runtime.

### 13.1 Authentication schema

`AuthRequest` fields:
| Field number | Name | Type / documented meaning |
|---:|---|---|
| 1 | `version` | int32; phone protocol version |
| 2 | `authPk` | bytes; phone authentication public key R, x || y |
| 3 | `authPkHmac` | bytes; MAC for `authPk` |
| 4 | `agreementPk` | bytes; phone agreement public key R' |
| 5 | `agreementPkSig` | bytes; ECDSA signature for R' |
| 6 | `random` | bytes; phone nonce Na |
| 7 | `id` | bytes; phone unique ID, documented as 6 bytes |
| 8 | `model` | string; phone model, documented as UTF-8 and 32 bytes |
| 9 | `userConfirmed` | bool; user confirmation state |
| 10 | `workModes` | optional int32; phone-supported work modes |
| 11 | `productName` | optional string; product name |

`AuthResponse` fields:
| Field number | Name | Type / documented meaning |
|---:|---|---|
| 1 | `version` | int32; car protocol version |
| 2 | `authPk` | bytes; car authentication public key S |
| 3 | `authPkHmac` | bytes; MAC for `authPk` |
| 4 | `agreementPk` | bytes; car agreement public key S' |
| 5 | `agreementPkSig` | bytes; ECDSA signature for S' |
| 6 | `random` | bytes; car nonce Nb |
| 7 | `result` | int32; negotiation result |
| 8 | `workModes` | optional int32; selected work modes |
| 9 | `dayOrNightMode` | optional int32; current display mode |
| 10 | `screenInfo` | optional string; documented as screen width/height/DPI |

`AuthConfirm.cipher` (field 1, bytes) is documented as ciphertext containing the phone's local IP address encrypted with the session key. These comments indicate a public-key/key-agreement authentication design. They do not disclose private keys or the complete cryptographic verification algorithm.

### 13.2 Message indexes and certificate envelope

The schema defines:
- `AuthIndex`: `auth_request=1`, `auth_response=2`, `auth_confirm=3`.
- `CertIndex`: `certificate=1`.
- `CarCertificate.content=1` (bytes).

The APK also contains `UCarCertProtocol.createCarCertificateMessage` and generated `CarCertificate` classes. This proves the existence of a certificate message builder and its protobuf envelope, but does not by itself prove the certificate's DER/ASN.1 format, trust anchor, or when it is sent.

### 13.3 Control message family

The schema's `ControlIndex` is a protobuf envelope with numbered fields 1–35, including heartbeat, port discovery, microphone state, mirror state, audio player state, phone/music/navigation information, key events, camera management, disconnect, car configuration, app lists/details, app invocation/state, POI and call state. It separately defines `AudioIndex.audio_type`.

This is a useful map of the post-auth feature protocol, but field numbers inside a protobuf envelope are **not** the UCar outer `methodIndex`. Do not conflate them.

### 13.4 What this changes in the investigation

We can now document the authentication data contract exactly from the packaged schema and distinguish it from the still-unresolved outer command mapping. The next bytecode task remains finding the runtime caller(s) of `createAuthRequestMessage`, `createAuthResponseMessage`, `createAuthConfirmMessage`, and `createCarCertificateMessage`, then resolving the numeric `methodIndex` values and send order from those callers. Do not implement a synthetic auth exchange: the schema contains cryptographic fields whose validity depends on the actual key/certificate machinery and peer state.


## 14. Runtime caller trace: auth response and certificate send

A targeted DEX instruction pass found the first concrete runtime caller sites for the protocol builders. This is stronger than method inventory alone because it reveals the callback branches and the outbound send calls.

### 14.1 Confirmed numeric method-index map

The generated lookup map in `UCarAuthProtocol$1.<init>()` maps:
- method index `1` → `auth_request`
- method index `2` → `auth_response`
- method index `3` → `auth_confirm`

The corresponding `UCarCertProtocol$1.<init>()` map maps:
- method index `1` → `certificate`

These are outer UCar method-index names registered by each protocol class. They are distinct from the protobuf field numbers in `AuthIndex` / `CertIndex`.

### 14.2 Authentication service callback

The method `com.share.connect.security.UCarAuthService.lambda$new$0$UCarAuthService(int, int, String, boolean, UCarMessage)` has two visible branches:

**Incoming auth request branch**
1. Calls `UCarAuthProtocol.isAuthRequestMessage(message)`.
2. Parses the request with `parseAuthRequestMessage(message)`.
3. Reads the request's phone ID and optional work modes / product name, and reports client key-negotiation information to `UCarAuthObserver`.
4. Calls `ServerKeyNegotiator.generateKeyNegotiationInfo(request, ...)` to build an `AuthResponse`.
5. Calls `createAuthResponseMessage(response, incomingMessage.getSeqId())`.
6. Sends the response through `ConnectChannel.send(message, callback)`.

This is direct bytecode evidence that the receiver's auth service responds to a phone-originated `AuthRequest`, and that the response builder is given the incoming message's sequence ID.

**Incoming auth confirm branch**
1. Calls `isAuthConfirmMessage(message)`, then `parseAuthConfirmMessage(message)`.
2. Reads `AuthConfirm.cipher`, converts it to bytes, and calls `SecurityManager.decrypt(byte[])`.
3. Converts the decrypted bytes to a string and splits it on `:`.
4. Passes the resulting connection information to `UCarAuthObserver.onClientConnectionInfoReceived(String, int)`.

This establishes the receiver-side post-confirm processing path. The bytecode excerpt shows a colon-delimited plaintext being split into connection information; the exact validation/error handling and cryptographic algorithm must be followed into `SecurityManager` before implementing it.

### 14.3 Certificate send caller

`com.ucar.vehiclesdk.datacenter.CarCertManager$1.onConnect()`:
1. Creates a `CarVerifier` using the application context.
2. Calls `CarVerifier.readCarData()`.
3. If the returned string is empty, logs `failed to get car cert data` and returns.
4. Otherwise logs `sending car certificate to phone`.
5. Wraps the returned string's UTF-8 bytes in `CarCertificate.content`.
6. Calls `UCarCertProtocol.createCarCertificateMessage(certificate)`.
7. Sends it with the certificate manager's `send(UCarMessage, SendFutureCallback)` method.

This is evidence that the certificate message body is the UTF-8 bytes of the string returned by `CarVerifier.readCarData()`. It is not evidence that the value is a PEM certificate, DER certificate, or a valid credential when fabricated. We must inspect `CarVerifier` and its storage/verification dependencies next.

### 14.4 Immediate next targets

1. Inspect `ServerKeyNegotiator.generateKeyNegotiationInfo` and `SecurityManager.decrypt` / related key derivation and verification routines.
2. Inspect `CarVerifier.readCarData()` to determine the certificate source and expected representation.
3. Inspect the construction of `UCarAuthService` and `CarCertManager` to establish which channels deliver these messages and when the certificate send is triggered relative to authentication.
4. Inspect `ConnectChannel.send` and the inbound message dispatcher to establish the exact transport path for UCar frames.

Do not assume certificate-send timing from the method name `onConnect` alone. The caller establishes that it runs on a connection callback, but its ordering relative to auth must be confirmed from object wiring and channel lifecycle code.


## 15. Key negotiation, session cipher, and certificate source

The next targeted bytecode pass followed `ServerKeyNegotiator.generateKeyNegotiationInfo`, `SecurityManager`, and `CarVerifier`. The conclusions below are based on method bodies and string/method references in the supplied APK.

### 15.1 Server-side key negotiation path

`ServerKeyNegotiator.generateKeyNegotiationInfo(AuthRequest, ...)` contains explicit calls to:
- read the configured PIN code;
- parse client information from the incoming request;
- generate an EC agreement public key and encode it;
- generate a 32-byte nonce;
- validate/derive peer authentication and agreement public keys;
- call `agreeOnSessionKey`;
- persist or update peer information;
- generate the server authentication public key, HMAC and signature;
- build an `AuthResponse` containing version, public keys, HMAC, agreement-key signature, nonce, result, work modes, day/night mode and screen info.

The method contains separate normal-connection and quick-connection branches, with log strings for successful and failed paths. In the normal branch, the code logs `normal connection initiated by the phone` and then `normal connection succeeded` after the peer-key/session-key steps. This is consistent with the `AuthRequest` being the phone's opening message and the receiver generating `AuthResponse`; it is not evidence that an arbitrary client can pass authentication without valid key material.

### 15.2 Session encryption

The packaged `SecurityManager` contains the transformation string `AES/GCM/NoPadding`, constructs `SecretKeySpec` with algorithm `AES`, and uses `GCMParameterSpec` with a 128-bit authentication tag. The byte-array decrypt path reads a 32-bit value from the input before performing GCM decryption and iterates over framed data. Thus, the `AuthConfirm.cipher` is handled by a real authenticated-encryption implementation, not a simple plaintext/base64 conversion.

The precise encrypted-byte layout, IV extraction, length validation, and session-key derivation must be documented from the full method and helper routines before implementing an interoperable decryptor. Do not reuse or export any provisioned session keys.

### 15.3 Certificate source

`CarVerifier.getDefaultReader()` opens the packaged asset named `ccd.data`. `getCcdReader()` prefers this default asset when the configured data name is `ccd.data`; otherwise the code can fall back to a file-path reader. `readCarData()` reads through `CarVerificationData.Builder`, and the certificate send caller wraps the returned string's UTF-8 bytes as `CarCertificate.content`.

This establishes the packaged asset as the default source for certificate data in this APK. It does not establish the asset's format or prove that the same value is suitable for another device/OEM. The asset contents are intentionally not copied into this document.

### 15.4 Current protocol picture

Evidence now supports this receiver-side path:

1. The phone sends a UCar `AuthRequest` (outer auth method index 1).
2. The receiver parses it and performs EC key negotiation / peer validation.
3. The receiver sends `AuthResponse` (outer auth method index 2) using the incoming sequence ID.
4. The phone sends `AuthConfirm` (outer auth method index 3).
5. The receiver decrypts the confirmation using the session security manager, parses the resulting connection information, and notifies its observer.

Separately, the certificate manager sends a UCar certificate message (certificate method index 1) on its connection callback after reading the certificate data. The exact relative ordering of this certificate send versus the authentication exchange remains to be established by tracing channel construction and lifecycle wiring.

### 15.5 Implementation boundary

The protocol schema and high-level message order are now better established, but this is still not sufficient to implement a compatible authentication exchange. Remaining prerequisites include the exact UCar frame transport over the relevant socket channel, message sequence and source/category values, certificate format and validation, key-pair/peer storage behavior, all GCM framing details, and error/retry transitions. Continue static analysis before writing auth code; do not replace any cryptographic routine with a stub that reports success.


## 16. ConnectChannel transport investigation: current evidence and next gating question

A fresh inventory of the supplied APK's DEX files confirms that the following implementation classes are packaged (the class descriptors are present in the DEX string/type tables):
- `com.share.connect.channel.ConnectChannel` (in `classes4.dex` and `classes5.dex`);
- `com.share.connect.security.ServerKeyNegotiator` and `UCarAuthService` (including generated callback/lambda classes, in `classes5.dex`);
- `com.share.connect.utils.CarVerifier` (in `classes4.dex` / `classes5.dex`);
- `com.ucar.protocol.UCarMessageBuilder` and `com.ucar.protocol.security.SecurityManager` (in `classes7.dex`, with related references in other DEX files).

This confirms that the source-level transport, auth, certificate, and message-builder layers are spread across multiple DEX files; a single-DEX string scan is insufficient to establish a complete call graph.

### What is and is not established for ConnectChannel

From the already inspected auth-service call site, `ConnectChannel.send(UCarMessage, callback)` is the outbound API used to send an `AuthResponse`. From the AOA manager path, channel payload bytes are forwarded to per-channel socket threads. These two facts do **not yet prove** that `ConnectChannel` itself reads directly from the AOA USB endpoint: it may be layered over one of the socket/proxy channels created by the AOA manager.

The next decisive static-analysis task is to inspect `ConnectChannel`'s constructor and send/receive methods, then follow the constructor's channel/stream argument back to its factory. This should establish:
1. whether it owns a TCP/local socket, a stream abstraction, or another transport;
2. how the 20-byte UCar header is accumulated from reads and how the declared total length is enforced;
3. whether the message receiver validates CRC before dispatch;
4. how source device, category, message type, method index, and sequence ID are assigned;
5. which callback routes a decoded `UCarMessage` to `UCarAuthService`.

### Implementation decision

Until these are established, do not put UCar framing directly into `MainActivity`'s USB Bulk IN loop. Keep the current diagnostic receiver read-only. The eventual architecture should separate USB/ AOА transport framing, channel/socket bridging, and UCar message framing so each can be tested independently.


## 17. Next reverse-engineering pass: transport code is the blocker, not the protobuf schema

The official APK was rechecked directly as an archive. It contains `ucar.proto` at the APK root (15,595 bytes), six DEX files beyond the primary DEX (`classes2.dex` through `classes7.dex`), and native libraries including `libusbio.so`. The protobuf schema is therefore available as a packaged source artifact; reconstructing it from generated Java is unnecessary.

A quick targeted check of the existing S9 logcat capture did not find usable runtime log lines containing `ConnectChannel`, `UCarAuthService`, `ServerKeyNegotiator`, `CarCertManager`, or header-parser error strings. The existing log cannot be used to infer the channel construction path or actual message order.

### Consequence for the next pass

The missing evidence is the instruction body and callers for `ConnectChannel`, not more USB probing or more schema extraction. The next pass must produce a DEX-level call graph for:
- `ConnectChannel.<init>`, `send`, receive/read loop, and parser dispatch;
- the factory or owner that constructs `ConnectChannel`;
- the callback that forwards decoded messages to `UCarAuthService`;
- `CarCertManager` construction and `onConnect` registration;
- the channel/socket object passed to `ConnectChannel`.

Until these instruction bodies are recovered, any claim that `ConnectChannel` uses a particular socket, frames reads in a particular way, or validates CRC at a specific point would exceed the evidence. Keep the app implementation unchanged until this call graph is established.


## 18. DEX-level call-site recovery: ConnectChannel inherits the socket protocol engine

This pass used a small local DEX table/code-item scanner against the supplied APK's extracted DEX files. It is **not** a full Java decompilation: the method IDs and direct invoke references below were recovered from DEX metadata/instructions, while branch conditions and all register values still need a proper disassembly/decompiler review.

### 18.1 ConnectChannel is a thin specialization

In `classes5.dex`, `com.share.connect.channel.ConnectChannel` declares three constructors and `onServerBound()`. The constructors delegate to `com.ucar.protocol.channel.socket.SocketChannel`; the one-argument constructor calls `SocketChannel.<init>(ChannelType)`, and the three-argument constructor calls `SocketChannel.<init>(ChannelType, boolean, boolean)`.

Its `onServerBound()` invokes `ConnectManager.getInstance()`, `getServerPort()`, and `ConnectManager.registerServerSocket(int, boolean)`. This is direct evidence that ConnectChannel relies on the shared socket-channel implementation and a manager-controlled server-socket registration path. The `send(UCarMessage, callback)` method previously identified at the call site is inherited/shared behavior rather than a declared method in ConnectChannel's own class body.

### 18.2 Shared SocketChannel owns stream framing and message operations

In `classes7.dex`, `com.ucar.protocol.channel.socket.SocketChannel` is defined. Its recovered method table includes constructors, `startClientChannel`, `startServerChannel`, `startReadData`, `startReadWriteThread`, `sendWithAck`, `closeInternal`, and multiple `lambda$...` callbacks. The send-related synthetic callback invokes `SocketChannel.lambda$send$8$SocketChannel(UCarMessage, SendFutureCallback)`; the request timeout callback invokes `lambda$request$7$SocketChannel(UCarMessage, RequestFuture, long)`. This places the send/request implementation in SocketChannel, not in the USB Bulk IN loop.

The constructor chain includes `NetChannel.<init>(ChannelType, boolean)` and constructs a `com.ucar.protocol.security.SecurityManager`. The socket-channel class also references Java socket and executor/thread APIs, and has explicit client/server channel startup methods. This is consistent with a layered socket transport implementation. The exact byte-level read loop and whether it accumulates the full 20-byte header before allocating/reading the body still require the relevant method bodies to be disassembled and checked; the method names alone do not prove those details.

### 18.3 AOA channel creation and UCar socket channel are separate paths

In `classes5.dex`, `AOAReadThread.run()` calls `AOAHostSetup.bulkTransferIn(byte[], int)`, decodes two integers through `ByteConvert.bytesToInt(byte[], int)`, reads a body, and dispatches through `AOAConnectManager.handleCreateSocket(byte[], int)` or `getReadThreadByChannelId(int)`; the latter path forwards bytes to `SocketReadThread.writeData(byte[], int, int)`.

`AOAConnectManager.handleCreateSocket(byte[], int)` references two 32-bit conversions, two unsigned-byte conversions, then branches into `startServerSocketThread(int, String, int)` or `startClientSocketThread(int, String, int, int)`. Those methods instantiate `LocalServerSocketReadThread` or `LocalClientSocketReadThread`, start the thread, wait for it to run, and store it by channel/port key. This gives a concrete bridge model: AOA messages create or route per-channel local socket threads, while UCar protocol messages are handled by the separate SocketChannel/NetChannel engine.

### 18.4 What this establishes—and what remains open

Established from DEX references:
- `ConnectChannel` is a subclass/specialization of `SocketChannel`, not the USB endpoint reader.
- `SocketChannel` owns shared send/request, client/server channel startup, and read-thread methods.
- AOA's `handleCreateSocket` selects local server/client socket thread paths and maps data to a channel-specific thread.
- The current receiver must not place UCar header parsing directly into its raw USB Bulk IN loop.

Still not established from this pass:
- the exact `SocketChannelReadTask` byte accumulation and `MessageHeader` parse/CRC order;
- the precise `NetChannel` read/write stream behavior and socket endpoints used by the factory;
- how the channel factory selects the `ChannelType`, port, and server/client role for the auth channel;
- the exact callback registration that delivers parsed messages to `UCarAuthService`;
- whether authentication/certificate startup ordering is fixed or lifecycle-dependent.

The next pass should disassemble `SocketChannelReadTask.run()`, `NetChannel`, `SocketChannel.lambda$send$8$SocketChannel`, `SocketChannel.lambda$request$7$SocketChannel`, and the channel factory/owner. No authentication implementation should be written until those points are confirmed.


## 19. SocketChannelReadTask and outbound send path recovered from DEX code items

This pass went beyond class/method string inventory: it parsed the DEX class-data tables, resolved method IDs and code-item offsets, and walked instruction streams to recover actual invoke instructions. It is still a targeted instruction scanner, not a full decompiler; branch predicates and all register values should be checked with JADX/smali before translating the flow into production code.

### 19.1 Receive path: exact high-level sequence

The recovered `SocketChannelReadTask.run()` invoke sequence in `classes7.dex` is:

1. `ProtocolConfig.getLogger()`, then `getSocketInputStream()`.
2. Wrap the input stream using `Channels.newChannel(InputStream)`.
3. Allocate a ByteBuffer for the header and obtain `MessageHeader.newEmptyHeader()`.
4. Call `fillBuffer(ReadableByteChannel, ByteBuffer, int)` to fill the header buffer.
5. Flip the buffer, take its backing array, and call `MessageHeader.assign(byte[])`.
6. Read `MessageHeader.getLength()`; on the error path it logs and calls `skipReadBuff()`.
7. Call `allocBodyBuff(int, ByteBuffer)`, then `fillBuffer(...)` to read the body.
8. Obtain a `UCarMessage`, copy the header into it, and branch on `isEncrypted()`.
9. The encrypted paths call `SecurityManager.decrypt(ByteBuffer, boolean)` or `decrypt(ByteBuffer)`; the method then updates body length and attaches/reuses the body buffer.
10. Dispatch handling through the executor selected by `ProtocolConfig.getMessageHandleExecutor(ChannelType)`. The runnable callback calls `FutureRequestManager.complete(UCarMessage)`, then `SocketChannel.onMessage(UCarMessage)`, and releases the message.

This confirms the UCar parser is a stream-oriented socket reader, distinct from the AOA 8-byte transport header. It also confirms encryption/decryption is integrated in the shared channel read path, rather than implemented in `ConnectChannel` itself.

**Important caveat:** the invoke scan shows calls to `MessageHeader.assign(byte[])` and `getLength()`, but does not by itself prove whether `assign` validates CRC. That must be confirmed by inspecting `MessageHeader.assign` and its callees.

### 19.2 Send path: header and body are written separately

The recovered `SocketChannel.lambda$send$8$SocketChannel(UCarMessage, SendFutureCallback)` method includes these calls in order:

- `Socket.getOutputStream()`;
- check `SocketChannel.isEncrypted()`;
- obtain a temporary `UCarMessage` and copy the original `MessageHeader`;
- when the encrypted branch is taken, pass `getBodyBuffer()` through `SecurityManager.encrypt(ByteBuffer)`;
- update header total length using `MessageHeader.updateLength(int)`;
- serialize the header using `MessageHeader.toTmpBytes()`;
- write the serialized header bytes to the output stream;
- write the body buffer (or temporary body) and flush;
- report completion through `SendFutureCallback.completed(Boolean)`, or report errors through `failed(Exception)`.

The method has both a path that writes the body buffer and a path that writes a temporary body byte array; the exact conditions selecting those paths require control-flow inspection. The observable send design is nevertheless clear: UCar header bytes and body bytes are emitted separately to the socket stream, with encryption performed in the channel layer when enabled.

### 19.3 What this changes for receiver design

- Do not try to decode UCar messages directly from each USB Bulk IN packet.
- First reconstruct AOA's 8-byte packet framing and channel/socket bridge.
- On the UCar socket side, implement a bounded read loop that obtains the fixed header, validates its fields/CRC as the official code does, then reads exactly the declared body length.
- Keep encryption as a distinct stage, and do not mark a message authenticated merely because a header parses or a protobuf decodes.
- Before implementation, inspect `MessageHeader.assign`, `MessageHeader.updateChecksum`, `fillBuffer`, `allocBodyBuff`, `skipReadBuff`, and `NetChannel` to establish CRC behavior, length limits, partial-read handling, and socket lifecycle.

### 19.4 Remaining unknowns

The DEX instruction walk did not yet establish:
- the exact CRC validation point and failure behavior;
- the numeric maximum permitted header/body length and whether `getLength()` is checked before allocation;
- the full `NetChannel` connection lifecycle and how its socket is created;
- the `ChannelType`/port values and which channel is used for auth versus control/media;
- whether all channels use encryption, and the exact encrypted body/nonce framing;
- certificate/auth startup ordering and retry/error transitions.


## 20. Header validation and stream-read helper: confirmed CRC call sites

This pass extracted the APK's DEX files and parsed the relevant method bodies' invoke instructions. Unlike the earlier class inventory, this confirms the exact CRC helper calls inside the header parsing methods.

### 20.1 CRC is checked during header construction/assignment

Both `MessageHeader.createMessageHeaderFromBytes(byte[])` and `MessageHeader.assign(byte[])` invoke `ByteUtil.readIntFromBytes`, `ByteUtil.readShortFromBytes`, and `Crc16.checksum(byte[], int, int)`. Both methods also have a `ProtocolException` construction path after the checksum operation. Therefore, header CRC verification is part of the header decode path; it is not a separate check visible only in the socket reader.

The recovered call sequence is:
- decode the integer header fields;
- decode the two short fields, including the stored checksum;
- calculate CRC using `Crc16.checksum(byte[], offset, length)`;
- take the normal or `ProtocolException` path according to the comparison.

The instruction-reference scan establishes the checksum and exception call sites. The exact branch predicate, exception message, and all field offsets should still be checked against the actual instruction operands before writing a byte-for-byte compatible parser.

### 20.2 Read helper and allocation behavior

`SocketChannelReadTask.fillBuffer(ReadableByteChannel, ByteBuffer, int)` calls `ByteBuffer.limit(int)` and repeatedly invokes `ReadableByteChannel.read(ByteBuffer)`, with logging paths for progress/error conditions. This is the shared helper used to fill the fixed header buffer and then the body buffer, so partial reads are handled at the stream layer rather than assumed to correspond to one read call.

`allocBodyBuff(int, ByteBuffer)` checks the supplied buffer's capacity and calls `clear()` when a reusable buffer is adequate; otherwise it calls `ByteBuffer.allocate(int)`. This shows buffer reuse, but the method-invoke scan alone does not establish a maximum body-size guard. The caller's length validation and branch conditions still need direct control-flow review.

### 20.3 Error handling

The read loop has a `ProtocolException` handling path that logs the failure and closes the `SocketChannel`. The body allocation path and exact handling of negative, undersized, or excessively large declared lengths still require direct branch-level verification. Do not assume malformed frames are safely rejected until those branches are reviewed.

### 20.4 Concrete next items

- Inspect the actual DEX branch around `Crc16.checksum` to record the exact checksum byte range and stored/calculated comparison.
- Decode the `fillBuffer` loop's branch targets to document EOF, zero-byte reads, and retry behavior.
- Confirm whether `SocketChannelReadTask.run()` validates total length against the 20-byte header size and a fixed maximum before allocation.
- Inspect `ChannelType` enum values and the construction call sites to recover which port/channel carries authentication.


## 21. Branch-level header validation and channel-type inventory

This pass decoded the conditional branch targets in the DEX methods rather than relying only on the invoke-reference list.

### 21.1 Header size and CRC mismatch path

Both `MessageHeader.createMessageHeaderFromBytes(byte[])` and `MessageHeader.assign(byte[])` compare the input array length against the constant `20` before decoding fields. Inputs shorter than 20 bytes branch to a `ProtocolException` path whose message begins `Protocol header bytes size[`.

The checksum path is now branch-confirmed:

- the parser reads the stored short checksum from byte offset 18;
- it invokes `Crc16.checksum(byte[], int, int)` after decoding the header fields;
- the conditional branch compares the calculated and stored checksum values;
- a mismatch takes the `ProtocolException` path with message prefix `Invalid header, checksum expect:` and includes both values rendered as hexadecimal;
- a matching checksum continues to construct/update the header.

The same shape is present in both byte-array entry points. This confirms that a compatible implementation must reject undersized headers and checksum mismatches before dispatching a message. The CRC helper's exact algorithm/polynomial and the call's exact offset/length operands still need to be recorded from the helper body and register operands; do not infer those solely from the exception text.

### 21.2 ChannelType enum names recovered

The `ChannelType` enum's static initializer contains these channel names:

- `CUSTOM`
- `UIBC`
- `RTSP`
- `RTP`
- `AUTH`
- `CONTROL`
- `MEDIA`
- `SENSOR`
- `CERT`
- `VIDEO`

`ChannelType.fromPort(int)` iterates the enum values and compares the requested port against each value's `getPort()`. This confirms the official implementation maps ports to named logical channels through the enum rather than using the channel name alone as the lookup key.

The enum initializer contains port constants, but this pass has not yet safely mapped every constant to its corresponding enum value. Consequently, this section does **not** claim numeric ports for AUTH/CONTROL/MEDIA/CERT or the order in which those channels are opened.

### 21.3 Implementation implications

- Enforce the 20-byte minimum before field parsing.
- Reject checksum mismatches as protocol errors; do not pass the frame to protobuf parsing.
- Keep AOA transport framing separate from the UCar 20-byte message header.
- Keep channel-name inventory separate from numeric port assignments until the enum constructor operands and channel creation call sites are decoded.

### 21.4 Remaining reverse-engineering targets

1. Decode `Crc16.checksum` to establish its polynomial, initialization, final XOR, and exact byte range.
2. Decode enum initializer register/constant operands and `getPort()` field assignment to map each channel name to its numeric port.
3. Trace `UCarAuthService` and `CarCertManager` construction to identify which channel types are instantiated first and whether auth/certificate setup has a strict order.
4. Finish branch-level review of `fillBuffer` and `SocketChannelReadTask.run()` for EOF, zero-byte reads, and declared-length validation before allocation.


## 22. CRC helper loop: bounds checks confirmed, algorithm parameters still open

The DEX body of both overloads of `Crc16.checksum` was inspected:

- `checksum(ByteBuffer, int, int)` checks for a null buffer, negative start/length values, and a requested range that exceeds `ByteBuffer.remaining()`. It reads bytes with `ByteBuffer.get(index)` inside a loop.
- `checksum(byte[], int, int)` performs the corresponding null, negative-range, and array-bound checks, then reads the byte array inside a loop.
- Invalid ranges reach `ArrayIndexOutOfBoundsException`; a null input reaches `NullPointerException`.
- Both overloads have a loop with byte-level arithmetic/table-index operations and return a 16-bit result.

This establishes that the helper processes a bounded range and that the ByteBuffer overload's indexes are relative to the buffer's current remaining range as checked by the implementation. It does **not** yet establish the CRC-16 variant: the polynomial, initial value, reflection settings, final XOR, and table contents have not been reconstructed in this pass. Do not substitute a generic CRC-16 implementation until those values are recovered or verified against known official frames.

The enum initializer also visibly contains the numeric constants `4321`, `7236`, and `15550` alongside the `UIBC`, `RTSP`, and `RTP` construction sequences, respectively. Treat these as strong sequence-level evidence, but finish decoding the register/field assignments before treating them as a tested port map. The other channel ports remain unresolved.


## 23. CRC class API shape and what can safely be concluded

A further method inventory of `com.ucar.protocol.Crc16` reveals a notable compatibility detail:

- `checksum(ByteBuffer, int, int)` and `checksum(byte[], int, int)` are the direct range-checking calculation entry points.
- `update(byte[])` delegates to the byte-array checksum overload.
- `update(byte[], int, int)` also delegates to the byte-array checksum overload.
- `update(int)` throws `UnsupportedOperationException` rather than processing a single byte.
- The class also exposes `getValue()` and `reset()`.

This API shape means it should not be treated as a fully conventional, incrementally updateable `java.util.zip.Checksum` implementation without checking the exact return/state semantics. In particular, a receiver implementation should call the official-compatible range checksum operation over the exact header bytes, not assume that repeated `update(int)` calls are valid.

The current DEX invoke scan confirms the delegation and exception path but does not yet establish the CRC table contents or polynomial. The CRC algorithm remains unimplemented in OpenCarlinkReceiver until those details are verified against the method's arithmetic and at least one captured official frame.


## 24. Authentication and certificate channel lifecycle: call-site evidence

The DEX invoke walk was extended to the constructors and startup methods of `UCarAuthService` and `CarCertManager`.

### 24.1 UCarAuthService startup

The `UCarAuthService` constructor:

- creates a `SecurityManager`;
- creates a `ServerKeyNegotiator`;
- creates an inner channel object using a supplied `ChannelType`;
- creates the message-handler callback and installs it through `ConnectChannel.setMessageHandler(...)`.

Its `start(String)` method calls `ConnectChannel.start(int, String)`; `stop()` calls `ConnectChannel.closeQuietly()`.

The installed handler recognizes an AuthRequest, parses it, extracts peer ID and optional work-mode/product/model/version data, calls the observer's key-negotiation callback, calls `ServerKeyNegotiator.generateKeyNegotiationInfo(...)`, then creates an AuthResponse using the incoming message's sequence ID and sends it through the channel callback path. It also recognizes AuthConfirm, parses its cipher field, converts it to a string and splits it before notifying `onClientConnectionInfoReceived(...)`.

This call list confirms the logical authentication path, but does not establish the exact wire encoding of the AuthConfirm cipher or prove that every observer callback means authentication has succeeded.

### 24.2 CarCertManager startup and certificate send

The `CarCertManager` constructor creates its own `ConnectChannel`-derived inner object with a supplied `ChannelType`. Its `start(String)` calls `ConnectChannel.start(int, String)`; `stop()` calls `closeQuietly()`.

On the channel's `onConnect()` callback, the manager creates a `CarVerifier`, calls `readCarData()`, converts the returned string to UTF-8 bytes, places those bytes into `CarCertificate.content`, creates a certificate message through `UCarCertProtocol.createCarCertificateMessage(...)`, and sends it.

### 24.3 What can and cannot be inferred

- Authentication and certificate delivery are represented by separate channel-manager objects, each with its own start/stop lifecycle.
- Both startup APIs accept a channel-related integer and a string address/connection parameter through `ConnectChannel.start(int, String)`.
- The DEX call list alone does **not** establish whether the certificate channel is started before, during, or after authentication in the full application; that requires tracing the parent coordinator's construction and startup calls.
- Do not hard-code a guessed port, invent a certificate, or treat a parsed AuthConfirm as proof that the peer has been accepted. Use a valid, authorized test setup and record the actual runtime transitions.

### 24.4 Next concrete target

Trace callers of `UCarAuthService.start(String)` and `CarCertManager.start(String)` and decode the enum constructor's two integer operands to map channel ports. This is the remaining step needed to connect the authentication sequence to a concrete transport endpoint.


## 25. ChannelType port constants: initializer sequence decoded further

A closer pass over the `ChannelType.<clinit>()` instruction sequence recovers the constructor pattern: each enum instance is created with its enum name, ordinal, and port value. The initializer's values align in declaration order as follows:

| ChannelType | Port constant recovered from initializer |
|---|---:|
| CUSTOM | 0 (no explicit port constant in the shown constructor sequence) |
| UIBC | 4321 |
| RTSP | 7236 |
| RTP | 15550 |
| AUTH | 57209 |
| CONTROL | 57219 |
| MEDIA | 57229 |
| SENSOR | 57239 |
| CERT | 57249 |
| VIDEO | 57259 |

The UIBC/RTSP/RTP constants are directly visible in the initializer sequence; the subsequent values appear as `57209`, `57219`, `57229`, `57239`, `57249`, and `57259` alongside the remaining enum constructors. This is a static DEX-derived mapping, not a runtime packet capture. Validate the AUTH/CERT endpoints against a controlled runtime trace before wiring the receiver to them, because enum values alone do not prove which channels are actually opened on a given connection path.

### Receiver implementation guidance

- Treat these as candidate local TCP/socket channel ports in the UCar channel layer, not USB endpoints and not AOA Bulk endpoint addresses.
- Do not confuse the AOA bridge's `port` field in its create-socket message with a raw USB endpoint number.
- Use the recovered mapping to guide logging and endpoint tracing; only promote it to an implementation constant after verifying the caller's `ChannelType` and observed socket lifecycle.


## 26. CRC lookup table fingerprint: reflected 0xA001 family

The static initializer's lookup table was inspected directly in the DEX code units. Its first entries include `0x0000`, `0xC0C1`, `0xC181`, `0x0140`, `0xC301`, and `0x03C0`. This sequence is a strong fingerprint of the reflected CRC-16 table generated with polynomial `0xA001` (the reflected form commonly associated with CRC-16/ARC and Modbus-family implementations).

**Scope of this finding:** the table fingerprint supports the reflected `0xA001` polynomial family. It does not alone prove the full named CRC variant, because the initialization value, final XOR, accumulator update order, and exact checksum range must also be read from the method's arithmetic and validated against a known official header. Therefore the receiver must not yet label this definitively as CRC-16/ARC or CRC-16/MODBUS, and no implementation constant has been added to the app.

### Verification required before implementation

1. Recover the accumulator's initial value from the method entry path.
2. Recover the per-byte update expression and whether the result is masked to 16 bits at each step.
3. Confirm the header call site's exact offset and length, expected to exclude the stored checksum field only if the operands show that.
4. Validate against at least one complete, authorized official header captured at runtime; a table prefix alone is insufficient.

This is a stronger result than class-name inference, but remains a static-analysis conclusion pending arithmetic and frame validation.


## 27. Parent call sites: certificate startup is part of the vehicle adapter channel sequence

The caller scan now identifies concrete parent methods for two of the service start calls:

- `UCarAdapter.startUCarChannel(String)` creates/starts `ControlManager`, starts `SensorManager`, then invokes `CarCertManager.start(String)`, and later starts `UCarAudioManager`. This establishes that certificate-channel startup is explicitly included in the adapter's channel-start routine.
- `ShareLinkService.startAuthService(String)` is a separate method. It stops an existing `UCarAuthService` if present, constructs a new `UCarAuthService` with context, observer, two integer parameters, a string, and a boolean, then invokes `UCarAuthService.start(String)`.

The two start paths therefore live under separate parent methods: certificate startup is in `UCarAdapter.startUCarChannel`, while authentication startup is in `ShareLinkService.startAuthService`. This alone does not prove which parent is called first at runtime; the caller of `startAuthService` is an accessor/forwarder (`ShareLinkService.access$1900`) in the DEX invoke scan, so the next trace must follow the enclosing service's state/event callbacks to determine actual ordering.

### Runtime validation plan

When collecting a permitted test trace, record timestamps for: AOA accessory connection, `UCarAdapter.startUCarChannel`, `CarCertManager` channel `onConnect`, `ShareLinkService.startAuthService`, `UCarAuthService` channel `onConnect`, first AuthRequest/AuthResponse, AuthConfirm handling, and certificate message send. These events distinguish source-code call ordering from asynchronous socket-connect ordering.

Do not change the receiver to send authentication or certificate payloads merely because a candidate port is known. First establish which side opens each socket and the observed create-socket messages on the AOA bridge.


## 28. Receive dispatch: request/response completion precedes channel handler

The recovered `SocketChannelReadTask.lambda$run$0$SocketChannelReadTask(ProtocolLogger, UCarMessage)` method provides a concrete endpoint for the receive path. Its call sequence is:

1. If debug logging is enabled, dump/log the decoded `UCarMessage`.
2. Obtain the singleton `FutureRequestManager` and call `complete(UCarMessage)`.
3. Call `SocketChannel.onMessage(UCarMessage)`.
4. Call `UCarMessage.release()` after dispatch.

This means a decoded message is first offered to the pending request/response manager, then passed to the channel's message handler. It is not safe to implement the receiver as a simple “every inbound message goes directly to the auth callback” pipeline: request correlation and channel-specific dispatch are separate steps.

### Implications for a compatible receiver

- Keep sequence-ID matching/request completion separate from the channel's application-level handler.
- Ensure the message/body buffer remains valid through both completion and handler dispatch; only release/recycle it at the lifecycle point consistent with the official code.
- Log header metadata, channel type, sequence ID, and dispatch result without logging private keys, certificates, or decrypted authentication payloads.
- Preserve the distinction between “frame decoded”, “request matched”, “handler accepted”, and “authentication succeeded”; they are different states.

### Still unresolved

This call sequence alone does not reveal the full sequence-ID matching rules inside `FutureRequestManager.complete`, nor whether all message types are eligible for completion. The manager's implementation and `SocketChannel.onMessage` branches must be inspected next. The CRC algorithm's initial value/update expression and runtime channel-port mapping also remain unverified.


## 29. Request completion and channel dispatch: boundary of current evidence

The current recovered call graph establishes the order `FutureRequestManager.complete(message)` → `SocketChannel.onMessage(message)` → `UCarMessage.release()`. The available instruction-reference scan does not yet expose enough branch-level detail to state the matching predicate inside `FutureRequestManager.complete` or the exact message-type switch inside `SocketChannel.onMessage`. No claim is made here about whether matching is based solely on sequence ID, whether response type is also required, or which messages are excluded.

### Receiver design decision that is supported now

The receiver should expose two distinct internal stages:

- **Request correlation stage:** may match a response to a pending request and resolve a callback/future. This stage must be able to report “no pending request matched” without automatically dropping the message.
- **Channel handler stage:** receives the decoded message independently and dispatches by the parsed command/category/method fields to the relevant channel handler.

For a first diagnostic implementation, it is safer to log these as separate events and keep the message available until both stages have completed. Only add automatic filtering once the exact matching and dispatch branches are decoded. This avoids silently losing unsolicited messages while preserving the official ordering found in the DEX.

### Next static-analysis target

Inspect `FutureRequestManager` fields and methods (`add`, `complete`, timeout/cancel/remove paths) and then disassemble the conditional branches in `SocketChannel.onMessage`. Record the actual key used for correlation and the response-type conditions only when supported by the instruction operands. In parallel, finish `Crc16` arithmetic and compare it with a valid captured header before implementing a checksum function.


## 30. FutureRequestManager correlation key and SocketChannel handler behavior

The DEX invoke scan of `FutureRequestManager` now confirms the key used for pending-request lookup:

- `addRequest(RequestFuture)` calls `RequestFuture.getRequestId()`, wraps that integer with `Integer.valueOf`, inserts the future into a map, and places the future in a `DelayQueue` for timeout handling.
- `complete(UCarMessage)` calls `UCarMessage.getSeqId()`, wraps that integer, removes the matching map entry, and, if a future was found, invokes `RequestFuture.handleMessage(message)`. If no future is found, it logs a warning rather than returning a matched request.
- `RequestFuture.handleMessage(message)` calls the callback's `convertMessage(message)`, checks cancellation, attempts to set the future result, invokes `completed(...)`, and releases the message in its own path. Because the read-task path also calls `UCarMessage.release()` after `SocketChannel.onMessage`, message ownership/release behavior needs careful compatibility review rather than copying this lifecycle blindly.
- The manager also has `clearRequestsWhileDisconnect()`, `removeRequest(int)`, a daemon timeout worker using `DelayQueue.take()`, and timeout failure handling. Pending requests are therefore not just a plain sequence-ID map; timeout and disconnect cleanup are part of the lifecycle.

`SocketChannel.onMessage(UCarMessage)` calls the configured `UCarChannel.MessageHandler.onMessage(message)`, then calls `UCarMessage.isSendSyncMessage(message)`. The instruction-reference scan also shows a logging/callback lambda on one branch, but this scan alone does not reveal the exact branch predicate or whether that branch handles all synchronous responses. The exact order after the message-handler call and the conditions for the callback branch require direct CFG review.

### Supported conclusions

1. The request correlation key is the integer returned by `UCarMessage.getSeqId()`, matched against `RequestFuture.getRequestId()`.
2. A request match removes the future from the pending map before handing the message to `RequestFuture.handleMessage`.
3. A message without a matching pending future is still passed on to `SocketChannel.onMessage` by the read-task dispatch path; unmatched correlation is not by itself proof that the message is invalid.
4. The official code has timeout and disconnect cleanup paths; a receiver should not leave pending requests indefinitely.

### Still open

- Exact `isSendSyncMessage` branch condition and behavior.
- Whether the configured handler can retain or asynchronously use a message after the read task's release point.
- CRC initial accumulator and update arithmetic, plus runtime verification of channel ports.


## 31. Receive-path implications for a compatible implementation

This section narrows the next implementation boundary using the invoke references found in `classes7.dex`. It is still static analysis of DEX call sites, not a live capture of an authenticated ICCOA session.

### Confirmed call chain

```text
SocketChannelReadTask
  ├─ parse/read a UCarMessage
  ├─ FutureRequestManager.complete(message)
  │    ├─ key = message.getSeqId()
  │    ├─ remove matching RequestFuture from pending map
  │    └─ if found: RequestFuture.handleMessage(message)
  │          ├─ callback.convertMessage(message)
  │          ├─ check cancellation / set future result
  │          ├─ callback.completed(result)
  │          └─ message.release()
  ├─ SocketChannel.onMessage(message)
  │    ├─ configured MessageHandler.onMessage(message)
  │    └─ evaluate UCarMessage.isSendSyncMessage(message)
  └─ message.release() at the read-task dispatch lambda
```

The call sites establish this ordering, but not every branch's runtime reachability. In particular, do not assume the two visible `release()` calls imply a double-free: the object may implement pooled/idempotent release semantics, and the exact ownership contract has not been established. Conversely, a new receiver should define message ownership explicitly and avoid retaining a pooled message after release.

### Consequences for OpenCarlinkReceiver

- Keep request/response correlation separate from general message dispatch. A response can satisfy a pending request and still pass through the channel's registered handler.
- Use a bounded timeout for every request and remove timed-out/cancelled requests from both correlation and timeout structures.
- On disconnect, clear pending requests and fail or cancel their futures; do not allow a previous USB session's responses to satisfy requests from a later session.
- Copy the bytes or parse the protobuf synchronously before releasing/recycling a message if work must continue asynchronously.
- Do not yet reproduce the `isSendSyncMessage` callback branch from names alone. The exact predicate and lambda body still need instruction-level control-flow decoding.

### Remaining evidence gap

The scan confirms invoked methods and order as represented in the read-task lambda, but does not yet establish the full branch graph, callback executor, sequence-ID allocation policy, or whether sequence IDs can be reused across channels. Those should be verified before implementing protocol-level request correlation.


## 32. CRC16 checksum loop: current instruction-level findings

A second pass over the `Crc16.checksum` DEX code items (in `classes7.dex`) confirms both overloads use the shared `CRC16_TABLE` lookup table and iterate over the requested buffer/byte-array range. The table's leading values previously observed include `0x0000`, `0xC0C1`, `0xC181`, `0x0140`, `0xC301`, and `0x03C0`, consistent with a reflected CRC-16 table generated using polynomial `0xA001`.

### What can be said safely

- There is a table-driven checksum loop in both `checksum(ByteBuffer, int, int)` and `checksum(byte[], int, int)`.
- The ByteBuffer overload consults `remaining()` and calls indexed `get(...)`; the array overload accesses the byte array and uses the same table.
- Both overloads contain explicit null/range error paths, including `NullPointerException` and `ArrayIndexOutOfBoundsException`.
- The available invoke-only/partial instruction dump does not yet reliably establish the initial accumulator, signed-byte normalization, exact table index expression, final XOR, or exact CRC byte range used by `MessageHeader`.

### Implementation status

Do not treat `0xA001` alone as enough to select a named CRC-16 variant. For interoperability, recover the full arithmetic and the exact bytes covered by the header checksum, then validate against at least one real captured official frame (or a reproducible official test vector). The DEX instruction-format reference explains why instruction widths and branch offsets must be decoded correctly before drawing conclusions from a control-flow dump: https://source.android.com/docs/core/runtime/instruction-formats.


## 33. Reverse-engineering quality gate: do not infer CRC parameters from a partial disassembly

During the next pass, the raw code-item instruction words for `Crc16.checksum` were extracted from `classes7.dex` at code-item offsets `0x70440` and `0x704b8`. This confirms the methods are available for a direct instruction-level decode, but the lightweight local decoder does not yet handle all DEX instruction formats and branch offsets correctly. Therefore it is not safe to publish a full pseudocode formula from that dump.

This is an explicit quality gate, not a protocol conclusion: the previous evidence still supports a shared table-driven CRC16 loop and a table consistent with reflected polynomial `0xA001`, but it does **not** prove the init value, final XOR, signed-byte treatment, exact loop bounds, or header checksum coverage.

### Next verification procedure

1. Decode every instruction using the official DEX instruction formats, including correct widths for conditional branches, register moves, field accesses, arithmetic, and invokes.
2. Resolve the `CRC16_TABLE` static initializer to its complete 256-entry table, and compare it against a generated candidate table.
3. Recover the accumulator initialization and each loop's table-index/update expression from both overloads; confirm both overloads are equivalent for the same byte sequence.
4. Decode `MessageHeader.assign(byte[])` and `createMessageHeaderFromBytes(byte[])` independently to establish the exact start offset and length passed to `checksum`.
5. Validate the resulting implementation against a captured, non-sensitive official protocol frame or a reproducible test vector before using it in the receiver.

Until steps 1–4 are complete, keep CRC implementation out of the compatibility path rather than shipping a plausible but unverified checksum.


## 34. CRC table fully recovered and verified

The `Crc16.<clinit>` `fill-array-data` payload was decoded directly from `classes7.dex`. It declares element width 4 and exactly 256 entries. The complete array was compared programmatically with a generated 256-entry reflected CRC table using polynomial `0xA001`; all 256 entries match exactly (including the initial values `0x0000, 0xC0C1, 0xC181, 0x0140` and final value `0x4040`).

This upgrades the polynomial/table finding from a fingerprint-based inference to an exact table match. It still does not identify the complete CRC variant: the accumulator initialization, per-byte update expression, final XOR, and precise header byte range must be recovered from the checksum method and MessageHeader call sites.

### Reproducibility note

The verification used the `fill-array-data` payload referenced by the static initializer, parsed as 256 little-endian 32-bit entries, and compared each entry to eight iterations of:
`c = (c >> 1) ^ ((c & 1) ? 0xA001 : 0)`.
The comparison returned `True` for all 256 entries. This is a table validation, not yet an end-to-end frame checksum test.


## 35. CRC header-check boundary: not yet proven by the available evidence

The checksum table is now verified exactly against the reflected `0xA001` table generator, but that does not establish the bytes passed to `Crc16.checksum` from `MessageHeader`. The relevant call sites are `MessageHeader.assign(byte[])` and `MessageHeader.createMessageHeaderFromBytes(byte[])`; the methods also parse fields from fixed offsets and compare a stored 16-bit value at header offset 18.

### Header facts already established

- Header minimum size is 20 bytes.
- The stored checksum field is at byte offsets 18–19.
- The integer and short helpers use big-endian encoding.
- Invalid header length and checksum mismatch follow exception paths.

### What is still unknown

- Whether the checksum input includes the checksum field itself or excludes it.
- Whether checksum begins at offset 0 or a later header field.
- Whether the length passed is a fixed header length, a prefix length, or a field-dependent range.
- The complete accumulator initialization/update/finalization formula.

The safe next step is to inspect the exact DEX invoke arguments and branch around each checksum call, then cross-check both parsing methods for identical coverage. No receiver-side CRC implementation should be considered compatible until that range and arithmetic are both recovered and tested against an actual valid frame.
