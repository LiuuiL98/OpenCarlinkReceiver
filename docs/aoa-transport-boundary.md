# AOA receive-path investigation checkpoint

Branch: `feature/usb-aoa-diagnostic-flow`

This note records static DEX evidence from the supplied ICCOA v1.5.3 APK. It is not a claim that the byte order has been validated against a known-good runtime packet.

## Confirmed AOA outer record structure

In `AOAConnectManager$AOAReadThread.run()V` in `classes5.dex`:

1. The first call to `AOAHostSetup.bulkTransferIn(byte[], int)` reads exactly 8 bytes into the `mMsgHead` array.
2. The first 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 0)`. It must be in the inclusive range 1..240. The value `1` is the create-socket discriminator; other accepted values are passed to `getReadThreadByChannelId(int)`.
3. The second 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 4)`. It must be positive and no greater than `0x04000000` (67,108,864 bytes, 64 MiB).
4. The requested body buffer length is `Math.max(decodedLength, 8)`. The body is then read by a second call to `bulkTransferIn(body, requestedLength)`.
5. The body dispatch uses the decoded first word as the channel ID and the decoded second word as the logical body length. For ordinary channel records, `SocketReadThread.writeData(body, 0, decodedLength)` is called. For channel ID 1, the body and decoded length are sent to `handleCreateSocket(byte[], int)`.
6. If an ordinary channel ID does not resolve to a read thread, the implementation logs the missing channel and returns to the receive loop without calling `writeData`.

The lower-bound allocation behavior is noteworthy: for a declared body length from 1 through 7, the code requests an 8-byte body read but passes only the declared length to the downstream handler. This is a literal observation of the DEX, not a recommendation to reproduce it without validating the consequences.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)` was also inspected:

- It rejects use when the USB connection or IN endpoint is unavailable.
- It repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- A successful positive read advances the offset and the loop continues until the requested byte count is satisfied.
- If the accumulated byte count is zero, it can return zero.
- If the accumulated byte count is nonzero but differs from the requested count, it raises an `IOException` whose message reports expected and received byte counts. It does not intentionally return a short positive read as if the requested read had completed.
- The internal method calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`. This means the Java helper wraps lower-level USB reads and should not be equated with a single USB packet or a single endpoint transfer.

This evidence establishes exact-length behavior at the Java helper boundary. It does not establish that the OS/USB stack delivers an entire logical record in one physical USB packet; the helper itself can combine multiple lower-level reads.

## Byte order: still needs explicit confirmation

The two words are decoded through `ByteConvert.bytesToInt`, but the byte order should be confirmed by inspecting that helper's arithmetic or by comparing a known official capture with its expected channel ID and length. The UI's big-endian/little-endian display remains diagnostic only.

## Validation limits and implementation decision

- The 64 MiB check is a limit imposed by this code path; do not assume it is a sensible receiver allocation limit. A new implementation should use a much smaller configurable safety cap until realistic legitimate record sizes are captured.
- A bounded parser should not allocate from an untrusted length before validating both the field and local cap.
- Because the official helper's contract is exact-length-or-error, tests should model partial underlying reads being accumulated, zero-byte reads, negative reads, failed retry, truncated body, over-limit length, and channel lookup miss.
- Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. The outer AOA record has its own 8-byte header and must be handled first.
- No outbound auth/certificate data should be implemented on the strength of this framing analysis alone.

## Next gate

1. Inspect `ByteConvert.bytesToInt` to settle byte order.
2. Inspect `handleCreateSocket(byte[], int)` and confirm its minimum record length and all field offsets from the method body.
3. Implement and unit-test a pure-Java AOA framing component with a safe allocation cap, leaving USB I/O and authentication outside it.
