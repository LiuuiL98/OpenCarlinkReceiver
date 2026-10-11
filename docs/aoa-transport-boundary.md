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

The `ByteConvert.bytesToInt(byte[], int)` method combines four unsigned bytes with shifts of 24, 16, 8 and 0 bits. Its matching `intToBytes` method writes the most significant byte at the lowest array offset. The outer header is therefore big-endian according to the helper implementation.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)`:

- rejects use when the USB connection or IN endpoint is unavailable;
- repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking offset and total received;
- continues after positive reads until the requested byte count is satisfied;
- can return zero if no bytes were accumulated;
- raises an `IOException` if a nonzero accumulated count differs from the requested count, rather than returning a short positive read as complete;
- calls `UsbNative.bulkRead(...)`; on a negative result it logs failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper can combine lower-level reads.

## Safe parser components added

- `AoaTransportHeaderProbe.decode(...)` decodes and bounds-checks the channel ID and declared body length without allocating a body or performing USB I/O.
- `AoaCreateSocketRecord.decode(...)` parses the create-socket body fields at offsets 0, 4, 8 and 9, and rejects a reported length below the official 10-byte minimum or a reported length beyond the available array.
- Unit tests cover big-endian header decoding, create-socket channel identification, the official minimum body-read request length, invalid header ranges, and create-socket record boundaries/unsigned byte fields.

These components are intentionally not wired into the raw USB Bulk IN diagnostic path: that path has not yet established reliable logical record boundaries.

## Important safety boundary

The official code accepts declared lengths up to 64 MiB. A receiver implementation should use a much smaller local allocation cap until realistic legitimate message sizes have been captured and measured. Never allocate directly from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream; preserve this as a documented quirk rather than assuming it is desirable behavior.

Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. No outbound auth/certificate data should be implemented on the strength of this framing analysis alone.

## Next gate

1. Inspect the parent method's record dispatch and verify that create-socket records are never truncated by a local wrapper.
2. Add a pure-Java, bounded body-length policy that separates the official 64 MiB upper bound from a deliberately smaller receiver allocation cap.
3. Add tests for body lengths above the local cap, truncated body input, zero-byte reads, failed reads, and channel lookup misses. Keep USB I/O and authentication outside the parser.
