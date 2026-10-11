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

## Byte order and USB helper behavior

The `ByteConvert.bytesToInt(byte[], int)` helper combines four unsigned bytes with shifts of 24, 16, 8 and 0 bits; `intToBytes` writes the most significant byte at the lowest offset. The outer header is big-endian according to this helper implementation.

`AOAHostSetup.bulkTransferIn(byte[], int)` repeatedly reads in chunks of at most 16,384 bytes until the requested count is satisfied. It can return zero if no bytes were accumulated, raises `IOException` for a nonzero short total, and can retry a failed native read once after waiting for USB reconnection. This is exact-length behavior at the Java helper boundary, not a claim that a logical record equals one physical USB packet.

## Safe pure-Java components

- `AoaTransportHeaderProbe.decode(...)` validates the 8-byte header and decodes channel ID/body length without allocating or doing USB I/O.
- `AoaCreateSocketRecord.decode(...)` decodes the create-socket record fields at offsets 0, 4, 8 and 9, with a 10-byte minimum.
- `AoaBodyLengthPolicy` applies a **1 MiB local allocation cap** by default, separately from the official 64 MiB limit. It validates the declaration before allocation and checks that the returned body length is consistent with the available array.
- Unit tests cover header endian/field decoding, create-socket selection, short-body read sizing, invalid official bounds, local cap rejection, truncated/inconsistent body lengths, and create-socket field boundaries.

These pure-Java components are not wired into the raw USB Bulk IN diagnostic path. The path has not yet established reliable logical record boundaries and must not feed raw USB chunks directly to the UCar 20-byte parser.

## Implementation guardrails

The official code accepts declared lengths up to 64 MiB, but the receiver's default local cap is intentionally much smaller until realistic valid traffic is measured. Do not allocate directly from an untrusted length without a local cap. The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream; this is documented as an observed quirk, not necessarily desirable behavior.

No outbound auth/certificate data should be implemented on the strength of framing analysis alone.

## Next gate

1. Review parent-method dispatch and verify that create-socket records cannot be truncated by a wrapper.
2. Add tests around USB-read outcomes (zero bytes, failed reads/retry, exact length) using a fake reader abstraction, not real device I/O.
3. Only integrate a reassembler after boundary behavior and runtime traces agree. Keep USB I/O and authentication outside the pure parser.
