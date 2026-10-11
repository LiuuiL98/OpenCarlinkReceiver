# ICCOA v1.5.3 static-analysis notes

Source artifact examined locally: `ICCOA - v1.5.3_202312071948_d8ad20b.apk` (version label 1.5.3). These notes separate directly observed APK symbols from conclusions that still require method-body decompilation or runtime evidence.

## 1. Observed implementation symbols

DEX string/class metadata contains the following implementation names:

- AOA setup and transport: `com.ucar.connect.aoa.AOAHostSetup`, `AOAPreambleBuilder`, `AOAReadThread`, `AOAConnectManager`.
- Framing: `com.ucar.protocol.MessageHeader`, `ProtocolParser`, `Crc16`.
- Authentication: `com.ucar.protocol.UCarAuthProtocol`, protobuf types `UCarProto.AuthRequest`, `AuthResponse`, `AuthConfirm`, and `CarCertificate`.
- Channel identifiers include auth, certificate, command, audio, UIBC, sensor, cast RTSP/RTP and socket creation.
- Symbols include `createAuthRequestMessage`, `createAuthResponseMessage`, `createAuthConfirmMessage`, `createCarCertificateMessage`, `parseAuthRequestMessage`, `parseAuthResponseMessage`, `parseAuthConfirmMessage`, `getAuthPk`, `getAuthPkHmac`, `authPk`, and `authPkMac`.

These names establish that the APK contains a framed UCar protocol and authentication/certificate messages. Names alone do not establish the exact wire format, state transitions, cryptographic inputs, or values.

## 2. Current receiver prototype versus official implementation

The prototype currently implements standard AOA control requests 51/52/53, waits for USB re-enumeration, opens the first standard AOA interface, and performs a read-only Bulk IN test. It does not yet implement the UCar frame parser, CRC verification, channel demultiplexing, authentication, certificate handling, or request/response state machine.

The six-part serial preamble currently accepts user-entered values and uses obvious project placeholders. A successful USB control transfer only proves the transfer was accepted; it does not prove the phone recognizes the accessory identity or that the values are valid OEM provisioning data.

## 3. Interpretation of the 2026-10-12 trace

After AOA request 53, the phone re-enumerated as `18D1:2D01`. The trace then read four packets from endpoint `0x81` on interface 0:

```text
00 00 00 01 00 00 00 0A
00 00 1C 44 FF FF FF FF 01 02
00 00 00 01 00 00 00 0A
00 00 10 E1 FF FF FF FF 01 02
```

The read completed with 4 successful reads (36 bytes), 10 timeouts and no other USB errors. This proves bytes were available on Bulk IN; it does not by itself prove they are valid ICCOA/UCar messages or that authentication failed. No phone-side Samsung Carlink confirmation appeared.

Do not label these bytes as a specific command/error without verifying the official `MessageHeader` and `ProtocolParser` method bodies and matching framing rules.

## 4. AOA interface selection

For Android AOA PID `0x2D01`, the standard interface is interface 0 and the second interface is ADB. The receiver's current choice of interface 0 / Bulk IN `0x81` is consistent with the AOA layout. The current trace also found the corresponding Bulk OUT endpoint `0x01`, but the prototype intentionally does not write business data.

## 5. Safe implementation order

1. Decompile and inspect the method bodies for `AOAPreambleBuilder`, `MessageHeader`, `ProtocolParser`, `Crc16`, and `UCarAuthProtocol`.
2. Record the verified header fields, byte order, message length semantics, CRC coverage, channel/message IDs and parser error paths in a separate protocol document.
3. Add a read-only frame inspector that reports parsed fields only after the parser has been verified against official code and sample frames.
4. Implement transport framing and CRC validation before channel dispatch.
5. Implement the documented authentication state machine only with the actual official protocol requirements. Do not hard-code fabricated OEM IDs, pairing codes, private keys, certificates, or guessed Bulk OUT commands.
6. Test with raw traces and explicit stage outcomes. Keep raw HEX opt-in because it can expose identifiers or authentication material.

## 6. Evidence limitations

This pass extracted DEX class/method/string metadata from the supplied APK, but did not yet produce a reliable decompiled method-body listing. Therefore the exact frame layout, CRC algorithm parameters, protobuf field mapping, certificate verification and authentication exchange remain unverified. This document intentionally records that boundary instead of presenting inferred details as facts.
