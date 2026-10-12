# AOA hardware trace regression fixture (2026-10-12)

Receiver: Samsung SM-G9650, Android 10 / API 29
Sender: Samsung S25
Diagnostic app: OpenCarlinkReceiver 0.2.4
Trace: read-only Bulk IN capture on AOA accessory 0x18D1:0x2D01, interface 0, endpoint 0x81.

## Captured sequence

The four successful reads were:

1. `00 00 00 01 00 00 00 0A` (8 bytes)
2. `00 00 1C 44 FF FF FF FF 01 02` (10 bytes)
3. `00 00 00 01 00 00 00 0A` (8 bytes)
4. `00 00 10 E1 FF FF FF FF 01 02` (10 bytes)

The strongest current interpretation is two repetitions of an 8-byte AOA header followed by its declared 10-byte body. The headers decode big-endian as channel ID 1 and body length 10. This matches the static analysis documented in `aoa-transport-boundary.md`, where channel 1 is the create-socket discriminator.

The two body candidates decode by the known field offsets (0, 4, 8, 9) as:

| Field | Body A | Body B |
|---|---:|---:|
| bytes 0..3 | 7236 | 4321 |
| bytes 4..7 | 0xFFFFFFFF (Java int -1) | 0xFFFFFFFF (Java int -1) |
| byte 8 | 1 | 1 |
| byte 9 | 2 | 2 |

These are framing/parser observations only. Do not infer that the first field is definitely a network port, that the second is a valid channel identifier, or that bytes 8 and 9 have a known business meaning until the official APK's `handleCreateSocket` implementation and a real connection lifecycle establish it.

## Regression scope

`AoaHardwareTraceRegressionTest` uses the existing pure-Java `AoaRecordReader` and captured bytes to validate:

- big-endian outer header decoding;
- a channel-1 record with a 10-byte body;
- decoding at the known body offsets;
- rejection of an invalid header before reading its body.

This test fixture does not send USB data, open sockets, authenticate, or claim end-to-end ICCOA compatibility.

## Integration gate

Before wiring this parser into the live Android USB path, implement and test a USB exact-length adapter that accumulates short positive reads until the requested count is reached, handles timeout/no-data only before a record starts, and fails closed on partial headers or bodies. Preserve endpoint/interface details and USB detach/re-enumeration handling. Do not pass raw Bulk IN chunks directly to the UCar parser.
