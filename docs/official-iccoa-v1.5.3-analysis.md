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
