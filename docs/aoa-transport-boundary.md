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

## Byte order confirmed from helper code

The `ByteConvert.bytesToInt(byte[], int)` method combines unsigned bytes using shifts of 24, 16, 8 and 0 bits. Its matching `intToBytes` method writes the most significant byte at the lowest array offset. The AOA outer header is therefore big-endian according to the helper's implementation; this conclusion is based on DEX arithmetic, not merely on the diagnostic's two-endian display.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)` was also inspected:

- It rejects use when the USB connection or IN endpoint is unavailable.
- It repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- Positive reads advance the offset and the loop continues until the requested byte count is satisfied.
- If the accumulated byte count is zero, it can return zero.
- If the accumulated byte count is nonzero but differs from the requested count, it raises an `IOException` reporting expected and received byte counts. It does not intentionally return a short positive read as if the requested read had completed.
- The internal method calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper may combine lower-level reads.

## New pure-Java implementation

`AoaTransportHeaderProbe.decode(...)` decodes the two big-endian words and enforces the observed channel and official length ranges without allocating the body.

`AoaTransportRecordDecoder` is a separate, incremental utility. It can accept split headers/bodies and multiple records in one input range. It enforces a configurable local body-length cap before allocating the body buffer; the default is 1 MiB rather than the official code's 64 MiB maximum. It models the observed wire read length `max(declaredLength, 8)`, while exposing only the declared logical body bytes to callers. It supports reset after disconnect/error.

`AoaCreateSocketMessage.parse(...)` now decodes the create-socket payload fields recovered from the official DEX: 32-bit big-endian values at offsets 0 and 4, followed by unsigned type bytes at offsets 8 and 9. It rejects payloads shorter than 10 bytes and rejects declared lengths that exceed the available backing array. The type bytes remain unnamed semantically, and this parser does not open sockets.

These pure-Java utilities are **not connected to the raw USB Bulk IN path**. The diagnostic reads a header-sized transfer and subsequent body reads through the Android USB API; integrating a stream decoder there without changing that I/O model could double-frame or desynchronize the stream. First validate the USB helper contract and real captures.

Unit tests cover split headers and bodies, multiple records in one input range, create-socket discriminator handling, short-body 8-byte wire-read behavior, invalid ranges, over-cap lengths, reset after partial input, create-socket minimum length, big-endian fields and unsigned trailing bytes.

## Important safety boundary

The official code accepts declared lengths up to 64 MiB. The receiver uses a conservative 1 MiB local cap by default until realistic legitimate message sizes have been captured and measured. Never allocate directly from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream; this is a documented quirk, not a claim that the extra bytes are padding in every runtime scenario.

Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. No outbound auth/certificate data should be implemented on the strength of this framing analysis alone.

## Next gate

1. Run CI for the header, record and create-socket parser tests.
2. Inspect `handleCreateSocket(byte[], int)` for any extra branch constraints before using these fields in a runtime transport.
3. Validate the 1 MiB local cap against realistic read-only captures; increase it only with evidence.
4. Keep the new parsers isolated until transport ownership and the USB helper's read model are reconciled.
