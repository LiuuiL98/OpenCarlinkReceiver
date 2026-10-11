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

## Byte order

The `ByteConvert.bytesToInt(byte[], int)` method combines unsigned byte values with shifts of 24, 16, 8 and 0 bits in that order. Its matching `intToBytes` method writes the most significant byte at the lowest array offset. The AOA outer header is therefore big-endian according to the helper's implementation; this conclusion is based on DEX arithmetic, not merely on the diagnostic's two-endian display.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)`:

- Rejects use when the USB connection or IN endpoint is unavailable.
- Repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- Continues after positive reads until the requested byte count is satisfied.
- Can return zero if no bytes have been accumulated.
- Raises an `IOException` when a nonzero accumulated byte count differs from the requested count; it does not intentionally return a short positive read as if the requested read had completed.
- Calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper may combine lower-level reads.

## Local body-size cap added

`AoaTransportHeaderProbe.decode(data, returnedLength, localBodyLimit)` now validates a caller-supplied local cap before a caller allocates a body buffer. The local cap must be between 1 byte and the official 64 MiB upper bound, and the declared body length must not exceed it. The original two-argument overload preserves the official bound for diagnostics but does not allocate memory.

Tests cover normal big-endian decoding, create-socket identification, the official minimum body read length, invalid channel IDs, zero and over-limit lengths, an invalid local cap, a declared length above a chosen local cap, and incomplete/out-of-range input.

This is a safety boundary, not a full stream reassembler. The UI's raw Bulk IN diagnostic has not been wired to this decoder because it does not yet establish a complete logical record boundary.

## Important implementation boundary

The official code accepts declared lengths up to 64 MiB. That is an observed upstream limit, not a safe default allocation size for this receiver. Callers should choose a smaller local cap based on expected message types and real traces; never allocate directly from an untrusted length without a local cap.

The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream. Preserve this as a documented quirk rather than assuming it is desirable behavior. Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. No outbound auth/certificate data should be implemented on the strength of this framing analysis alone.

## Next gate

1. Inspect `handleCreateSocket(byte[], int)` instruction by instruction and confirm the record minimum and field offsets.
2. Select and document a conservative local allocation cap for the actual receiver use case.
3. Add a separate bounded body-read abstraction with tests for short reads, failed reads, oversized lengths, and channel lookup misses, keeping USB I/O and authentication outside the parser.
