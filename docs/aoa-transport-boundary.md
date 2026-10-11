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

The `ByteConvert.bytesToInt(byte[], int)` method reads four unsigned byte values and combines them with shifts of 24, 16, 8 and 0 bits in that order. Its matching `intToBytes` method writes the most significant byte at the lowest array offset. The AOA outer header is therefore big-endian according to the helper's implementation; this conclusion is based on DEX arithmetic, not merely on the diagnostic's two-endian display.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)` was also inspected:

- It rejects use when the USB connection or IN endpoint is unavailable.
- It repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- Positive reads advance the offset and the loop continues until the requested byte count is satisfied.
- If the accumulated byte count is zero, it can return zero.
- If the accumulated byte count is nonzero but differs from the requested count, it raises an `IOException` reporting expected and received byte counts. It does not intentionally return a short positive read as if the requested read had completed.
- The internal method calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper may combine lower-level reads.

## Safe implementation progress

`AoaTransportHeaderProbe` now has a pure-Java `decode` method that returns the channel ID and declared body length after validating the observed ranges. It also exposes the official minimum body-read request length (`max(declaredLength, 8)`) without allocating a body buffer or performing USB I/O.

Unit tests cover big-endian decoding, the create-socket channel discriminator, the minimum read length for short declared bodies, incomplete headers, invalid channel IDs, zero body length, and lengths above the official 64 MiB check. This helper is intentionally not wired into the raw USB Bulk IN diagnostic path because that path has not yet established record boundaries.

## Important safety boundary

The official code accepts declared lengths up to 64 MiB. A receiver implementation should use a much smaller local allocation cap until realistic legitimate message sizes have been captured and measured. Never allocate directly from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream; preserve this as a documented quirk rather than assuming it is desirable behavior.

Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. No outbound auth/certificate data should be implemented on the strength of this framing analysis alone.

## Next gate

1. Inspect `handleCreateSocket(byte[], int)` instruction by instruction and confirm the record minimum and field offsets.
2. Decide and document a conservative local allocation cap based on the actual receiver's intended message types.
3. Add a separate bounded body-read/reassembly abstraction with tests for short reads, failed reads, oversized lengths, and channel lookup misses, keeping USB I/O and authentication outside the parser.
