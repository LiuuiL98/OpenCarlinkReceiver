# AOA receive-path investigation checkpoint

Branch: `feature/usb-aoa-diagnostic-flow`

This note records static DEX evidence from the supplied ICCOA v1.5.3 APK. Runtime validation against a real phone/receiver trace is still required before treating the transport implementation as end-to-end compatible.

## Confirmed AOA outer record structure

In `AOAConnectManager$AOAReadThread.run()V` in `classes5.dex`:

1. The first call to `AOAHostSetup.bulkTransferIn(byte[], int)` reads exactly 8 bytes into `mMsgHead`.
2. The first 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 0)`. It must be in the inclusive range 1..240. The value `1` is the create-socket discriminator; other accepted values are passed to `getReadThreadByChannelId(int)`.
3. The second 32-bit word is decoded by `ByteConvert.bytesToInt(mMsgHead, 4)`. It must be positive and no greater than `0x04000000` (67,108,864 bytes, 64 MiB).
4. The requested body buffer length is `Math.max(decodedLength, 8)`. The body is then read by a second call to `bulkTransferIn(body, requestedLength)`.
5. The body dispatch uses the decoded first word as the channel ID and the decoded second word as the logical body length. For ordinary channel records, `SocketReadThread.writeData(body, 0, decodedLength)` is called. For channel ID 1, the body and decoded length are sent to `handleCreateSocket(byte[], int)`.
6. If an ordinary channel ID does not resolve to a read thread, the implementation logs the missing channel and returns to the receive loop without calling `writeData`.

## Byte order

The `ByteConvert.bytesToInt(byte[], int)` method combines unsigned byte values with shifts of 24, 16, 8 and 0 bits in that order. Its matching `intToBytes` method writes the most significant byte at the lowest array offset. The AOA outer header is therefore big-endian according to the helper's implementation; runtime captures should still verify the expected field values.

## BulkTransferIn exact-read behavior

The implementation of `AOAHostSetup.bulkTransferIn(byte[], int)`:

- Rejects use when the USB connection or IN endpoint is unavailable.
- Repeatedly calls `bulkTransferInInternal(byte[], int, int)` in chunks of at most 16,384 bytes, tracking the offset and total bytes received.
- Continues after positive reads until the requested byte count is satisfied.
- Can return zero if no bytes have been accumulated.
- Raises an `IOException` when a nonzero accumulated byte count differs from the requested count; it does not intentionally return a short positive read as if the requested read had completed.
- Calls `UsbNative.bulkRead(...)`; on a negative result it logs the failure and may wait for USB reconnection and retry once. A failed retry raises an `IOException`.

This establishes exact-length behavior at the Java helper boundary. It does not mean one logical record is necessarily one physical USB packet; the helper may combine lower-level reads.

## Existing pure-Java safety helpers

- `AoaTransportHeaderProbe` decodes the big-endian channel ID and declared body length, validating channel IDs 1..240 and the observed official length ceiling of 64 MiB. It supports a caller-supplied local size cap.
- `AoaBodyReadPolicy` calculates the official requested body-read length (`max(declaredLength, 8)`), allocates only after local-cap validation, and rejects incomplete reads before dispatch.
- `AoaCreateSocketRecord` decodes the create-socket body fields at offsets 0, 4, 8 and 9 after checking the 10-byte minimum.
- `AoaRecordReader` reads one logical record through an injected exact-length source. It validates the header before allocating the body, rejects incomplete reads, and decodes create-socket bodies. It has no Android USB dependency and does not open sockets or send protocol messages.

Unit tests exercise normal channel records, create-socket decoding, the official 8-byte minimum read length, no-data before a header starts, incomplete header/body reads, invalid channel IDs, invalid/oversized lengths, and local-cap enforcement. These are deterministic unit tests; they do not substitute for a real USB capture.

## Important implementation boundary

The official code accepts declared lengths up to 64 MiB. That is an observed upstream limit, not a safe default allocation size for this receiver. The caller must choose a smaller local cap appropriate for expected message types and real traces; never allocate directly from an untrusted length without a local cap.

The official behavior for declared lengths 1..7 requests 8 bytes but passes the declared length downstream. Preserve this as a documented quirk rather than assuming it is desirable behavior. Do not pass raw Bulk IN bytes directly to the UCar 20-byte message parser. No outbound auth/certificate data should be implemented on the strength of static framing analysis alone.

## Next gate: hardware validation and integration

1. Run the pure-Java unit tests and build the debug APK on CI.
2. On the Galaxy S9+ receiver, capture the 8-byte header and exact requested/returned body lengths while the official ICCOA sender connects. Record decoded channel IDs/lengths and exception types, but redact authentication, certificate, and decrypted payload data.
3. Compare those observed values with this DEX-derived model. In particular, validate the `channelId == 1` create-socket path and the expected 10-byte minimum.
4. Only after a trace matches should `AoaRecordReader` be connected to the live USB path. Keep USB reconnection/retry behavior in the USB adapter, not in the pure parser.
5. Then test full channel lifecycle and UCar message parsing on captured, non-sensitive fixtures.

**Answer to the hardware-testing question:** no, real-device testing is not optional for declaring this protocol path complete. We can and should implement bounded parsing and automated tests offline first; the actual sender/receiver trace is required to validate assumptions, lifecycle ordering, and end-to-end interoperability.
