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

## Exact-length USB helper behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)` was inspected:

- It rejects use when the USB connection or IN endpoint is unavailable.
- It repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- Positive reads advance the offset and the loop continues until the requested byte count is satisfied.
- If the accumulated byte count is zero, it can return zero.
- If the accumulated byte count is nonzero but differs from the requested count, it raises an `IOException` reporting expected and received byte counts. It does not intentionally return a short positive read as if the requested read had completed.
- The internal method calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper may combine lower-level reads.

## New pure-Java test seam

`AoaExactRead` models the wrapper's accumulation contract behind a fakeable `ChunkReader` interface. It is intentionally not connected to Android USB APIs and does not implement the native layer's reconnection/retry policy. Its unit tests cover:

- multiple partial reads that collectively satisfy the requested length;
- zero bytes before any progress;
- zero bytes after partial progress (short-read exception);
- a negative/failed read;
- impossible return counts larger than the requested chunk;
- invalid arguments and a zero-length request.

This gives the exact-length behavior a deterministic unit-test surface without claiming to test the native USB stack. The caller must still handle `IOException`, reconnection, cancellation, and timeouts at the Android transport layer.

## Pure-Java parser and allocation policy

- `AoaTransportHeaderProbe.decode(...)` validates the two big-endian header words and returns channel ID plus declared body length without allocating a body.
- `AoaBodyLengthPolicy` applies a **1 MiB local allocation cap** by default, separately from the official 64 MiB limit.
- `AoaCreateSocketRecord.decode(...)` decodes the create-socket record fields at offsets 0, 4, 8 and 9, with a 10-byte minimum.
- These components are not wired into the raw USB Bulk IN diagnostic path. The path has not yet established reliable logical record boundaries and must not feed raw USB chunks directly to the UCar 20-byte parser.

## Implementation guardrails

The official code accepts declared lengths up to 64 MiB, but the receiver's default local cap is intentionally much smaller until realistic valid traffic is measured. Do not allocate directly from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream; this is documented as an observed quirk, not necessarily desirable behavior.

No outbound auth/certificate data should be implemented on the strength of framing analysis alone.

## Next gate

1. Review parent-method dispatch and verify that create-socket records cannot be truncated by a wrapper.
2. Confirm the new unit tests pass in CI.
3. Only integrate a reassembler after boundary behavior and runtime traces agree. Keep USB I/O and authentication outside the pure parser.
