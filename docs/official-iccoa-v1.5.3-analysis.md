# ICCOA v1.5.3 static-analysis notes

Source artifact: `ICCOA - v1.5.3_202312071948_d8ad20b.apk`. This is a working evidence log. It distinguishes DEX metadata verified from the supplied APK from wire-level conclusions that require reliable instruction decompilation and runtime validation.

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

This is a verified method inventory, not a full decompilation. It confirms where framing, CRC, AOA setup and authentication logic live; it does not establish their exact implementation.

## 2. Current receiver prototype versus official implementation

The prototype currently implements standard AOA control requests 51/52/53, waits for USB re-enumeration, opens the first standard AOA interface, and performs a read-only Bulk IN test. It does not yet implement the UCar frame parser, CRC verification, channel demultiplexing, authentication, certificate handling, or request/response state machine.

The six-part serial preamble currently accepts user-entered values and uses project placeholders. A successful USB control transfer proves only that the transfer completed; it does not prove the phone recognizes the accessory identity or that values are valid OEM provisioning data.

## 3. Interpretation of the 2026-10-12 trace

After AOA request 53, the phone re-enumerated as `18D1:2D01`. The trace then read four packets from endpoint `0x81` on interface 0:

```text
00 00 00 01 00 00 00 0A
00 00 1C 44 FF FF FF FF 01 02
00 00 00 01 00 00 00 0A
00 00 10 E1 FF FF FF FF 01 02
```

The read completed with 4 successful reads (36 bytes), 10 timeouts and no other USB errors. This proves bytes were available on Bulk IN; it does not by itself prove they are valid ICCOA/UCar messages or that authentication failed. No phone-side Samsung Carlink confirmation appeared.

Do not label these bytes as a specific command/error without verifying `MessageHeader.createMessageHeaderFromBytes`, `MessageHeader.writeBytesTo`, `ProtocolParser` and `Crc16` method bodies against the captured data.

## 4. AOA interface selection

For Android AOA PID `0x2D01`, the standard accessory interface is interface 0 and the second interface is ADB. The receiver's current choice of interface 0 / Bulk IN `0x81` is consistent with that layout. The trace also found Bulk OUT endpoint `0x01`, but the prototype intentionally does not write business data.

## 5. Next implementation steps

1. Use a reliable DEX decompiler/disassembler to inspect the method bodies listed above, beginning with `MessageHeader.createMessageHeaderFromBytes`, both `Crc16.checksum` overloads, and `AOAPreambleBuilder.build`.
2. Record verified header field offsets, endianness, message length semantics, checksum coverage and channel/message IDs.
3. Test a read-only parser against the four captured packets. If the reads are chunks of a stream rather than complete frames, add a stream reassembler before parsing.
4. Only after framing is validated, trace the authentication state machine and certificate handling.
5. Implement documented protocol stages with explicit outcomes. Do not hard-code fabricated OEM IDs, pairing codes, private keys, certificates or guessed Bulk OUT commands.
6. Keep raw HEX capture opt-in because future frames may contain identifiers or authentication material.

## 6. Evidence boundary

The supplied APK's DEX tables have been parsed locally far enough to verify class names, method names, descriptors and code-item locations. A reliable instruction-level decompilation has not yet been completed in this environment. Therefore exact header layout, CRC parameters, protobuf field mapping, certificate verification and authentication exchange remain unverified. The method inventory above is concrete progress, but it must not be presented as a recovered protocol specification.
