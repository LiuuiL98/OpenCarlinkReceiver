# AOA receive-path investigation checkpoint

Branch: `feature/usb-aoa-diagnostic-flow`

This note records static DEX evidence from the supplied ICCOA v1.5.3 APK. It is not a claim that runtime packet capture has independently validated every edge case.

## Confirmed AOA outer record structure

In `AOAConnectManager$AOAReadThread.run()V` in `classes5.dex`:

1. The first call to `AOAHostSetup.bulkTransferIn(byte[], int)` reads exactly 8 bytes into `mMsgHead`.
2. The first 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 0)`. It must be in the inclusive range 1..240. The value `1` is the create-socket discriminator; other accepted values are passed to `getReadThreadByChannelId(int)`.
3. The second 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 4)`. It must be positive and no greater than `0x04000000` (67,108,864 bytes, 64 MiB).
4. The requested body buffer length is `Math.max(decodedLength, 8)`. The body is then read by a second call to `bulkTransferIn(body, requestedLength)`.
5. The body dispatch uses the decoded first word as the channel ID and the decoded second word as the logical body length. For ordinary channel records, `SocketReadThread.writeData(body, 0, decodedLength)` is called. For channel ID 1, the body and decoded length are sent to `handleCreateSocket(byte[], int)`.
6. If an ordinary channel ID does not resolve to a read thread, the implementation logs the missing channel and returns to the receive loop without calling `writeData`.

## Byte order and read contract

The `ByteConvert.bytesToInt(byte[], int)` helper combines unsigned bytes using shifts of 24, 16, 8 and 0 bits; its matching `intToBytes` writes the most significant byte at the lowest offset. The outer header is big-endian according to this DEX arithmetic.

`AOAHostSetup.bulkTransferIn(byte[], int)` requests exactly the supplied count, accumulating lower-level reads in chunks up to 16,384 bytes. A positive short total is an `IOException`, not a successful short return. A zero total can return zero. The internal `UsbNative.bulkRead(...)` path can wait for reconnection and retry once after a negative result. Therefore Java's helper boundary is exact-length-or-error, but it is not equivalent to one physical USB packet.

## Create-socket message: branch and constructor findings

Instruction-level inspection of `AOAConnectManager.handleCreateSocket(byte[], int)` confirms:

- It rejects a payload shorter than 10 bytes before reading any fields.
- Offsets 0 and 4 are decoded as signed 32-bit big-endian integers named `port` and `channelId`; offsets 8 and 9 are unsigned bytes named `socketType` and `channelMsgType`.
- `socketType == 1` selects the server-socket path. That branch expects channel ID `-1`, obtains the socket name from `ChannelType.fromPort(port).name()`, and starts `LocalServerSocketReadThread`.
- The server thread's constructor passes the socket name, port and channel ID sentinel `-1` to the base `SocketReadThread`, constructs a Java `ServerSocket(port)`, and stores the channel-message type.
- `socketType == 2` selects `LocalClientSocketReadThread`. Its constructor passes socket name, port and the supplied channel ID to the base class, sets socket type 2, logs if the channel ID is `-1`, and stores the channel-message type.
- Other type values do not enter either recognized branch in the inspected method.

The mapping establishes the code's server/client role selection and constructor arguments, but it does **not** prove that a candidate port is open, that the phone will connect successfully, or that the same lifecycle is used for every phone. `AoaCreateSocketMessage` now exposes constants for the observed server/client type values and the server channel sentinel, plus convenience predicates. It does not reject unknown types, open sockets, or perform network operations.

## Pure-Java AOA framing implementation

`AoaTransportHeaderProbe.decode(...)` decodes the two big-endian words and enforces the observed channel and official length ranges without allocating a body.

`AoaTransportRecordDecoder` is a separate incremental utility. It accepts split headers/bodies and multiple records in one input range, checks a configurable local body-length cap before body allocation, and models the observed wire-read length `max(declaredLength, 8)` while exposing only the declared logical body bytes. Its default local cap is 1 MiB rather than the official 64 MiB maximum. It supports reset after disconnect/error.

These pure-Java utilities are **not connected to the raw USB Bulk IN path**. The diagnostic reads a header-sized transfer and subsequent body reads through the Android USB API; integrating a stream decoder there without changing that I/O model could double-frame or desynchronize the stream. The helper's read contract is now better understood, but actual runtime traffic and transport ownership still need validation.

Unit tests cover split headers/bodies, multiple records in one input range, the short-body 8-byte wire-read behavior, invalid ranges and over-cap lengths, decoder reset, create-socket minimum length, big-endian fields, unsigned type bytes, and server/client request classification.

## Safety boundary and next step

The official code accepts declared lengths up to 64 MiB; this receiver uses a conservative 1 MiB local cap until realistic legitimate sizes have been captured. Never allocate from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes only the declared length downstream; this is a documented quirk, not a claim that extra bytes are universally padding.

Do not pass raw Bulk IN bytes directly to the UCar 20-byte parser, and do not send outbound auth/certificate data on the strength of this framing analysis alone.

Next, validate the header/record/create-socket tests in CI, then trace the local client socket thread's connect/read loop and the server thread's accept loop to distinguish the listener endpoint from the remote endpoint. Keep runtime socket creation disabled until that is grounded by an authorized capture.
