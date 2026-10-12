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

The log does **not** prove that the receiver should connect to either port. No socket is opened by the diagnostic path. The two distinct values should be traced through the official APK before any active connection is implemented.

The 10-second capture still produced only these two records, with repeated 500 ms idle intervals. This shows no additional readable record arrived at the selected Bulk IN endpoint during that capture window; it does not prove that no later message exists or that authentication was attempted.

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
