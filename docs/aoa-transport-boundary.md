# AOA receive-path investigation checkpoint

Branch: `feature/usb-aoa-diagnostic-flow`

This note narrows the next implementation step to the official APK's AOA read loop. It is deliberately an evidence checkpoint, not a guessed wire-format specification.

## Confirmed from the supplied ICCOA v1.5.3 DEX scan

1. `AOAConnectManager$AOAReadThread.run()` performs a Bulk IN read into an 8-byte buffer named `mMsgHead`.
2. It decodes two 32-bit values by calling `ByteConvert.bytesToInt(mMsgHead, 0)` and `ByteConvert.bytesToInt(mMsgHead, 4)`.
3. The loop then reads a separate body buffer through another Bulk IN operation.
4. The resulting record is dispatched down one of two paths:
   - a create-socket record reaches `handleCreateSocket(byte[], int)`;
   - an ordinary channel record looks up a `SocketReadThread` using the channel ID, then passes the body to `writeData(byte[], int, int)`, which writes and flushes the channel output stream.
5. `handleCreateSocket(byte[], int)` rejects records shorter than 10 bytes and reads fields at offsets 0, 4, 8 and 9. Its first two 32-bit values are logged as port and channel ID; the two following unsigned bytes are logged as socket type and channel message type.

## New instruction-level findings

The `run()V` code item in `classes5.dex` was located and the relevant branch operands were inspected:

- The first Bulk IN call passes the `mMsgHead` byte array and literal length `8`.
- The code invokes `bytesToInt(mMsgHead, 0)` and `bytesToInt(mMsgHead, 4)` into separate integer registers.
- The first decoded integer is checked against `1` at the dispatch branch. If it equals `1`, the body is sent to `handleCreateSocket(byte[], int)`. Otherwise it is passed to `getReadThreadByChannelId(int)`, so this first word acts as the ordinary channel lookup key, with `1` reserved for the create-socket path.
- The second decoded integer is checked for a positive value and against an upper-bound constant corresponding to 64 MiB before the body-buffer/read sequence. This strongly suggests it is length-related, but I am not yet treating it as the definitive body-length field because the full register flow into the allocation, second Bulk IN call, and `writeData` arguments still needs independent validation.
- The loop treats a failed header read and failed body read as error paths; a zero-length header read returns to the loop. The current diagnostic code must continue to distinguish no-data/timeout from actual payload.

These are static DEX observations, not a runtime-confirmed protocol specification. In particular, do not infer that one USB Bulk IN return necessarily equals one complete logical record.

## What is not established yet

- The byte order of the two outer-header words from a known official capture.
- The exact value used as the second Bulk IN request length and as the logical body length after all register moves are resolved.
- Whether a USB Bulk IN return boundary always corresponds to one complete outer header/body segment, or whether the official loop retries/accumulates partial reads.
- Whether the 64 MiB bound is an intended protocol maximum or merely defensive implementation logic.

Do not treat the two endian renderings in the app's probe as proof of byte order or field meaning. The current diagnostic intentionally prints candidates only.

## Next analysis gate

Before writing a reassembler, finish the register trace across the second `bulkTransferIn` and the `writeData` call, then document:

1. The exact register passed as the second Bulk IN length.
2. The array allocation length and any reuse/resizing logic.
3. Whether the returned read count must equal the declared length or is passed through independently.
4. The error, short-read, and loop-back behavior.
5. Whether the underlying USB helper loops internally or performs one transfer per call.

Only after those operands and branches are reconciled should a bounded parser be introduced. Its tests must cover split header, split body, multiple records in one read, invalid/oversized lengths, truncated input, and the create-socket minimum length. Until then, keep raw Bulk IN capture read-only and do not feed it directly into the UCar 20-byte parser.

## Current implementation boundary

`AoaTransportHeaderProbe` is a diagnostic display helper only: it reports raw bytes and both endian interpretations without assigning semantic field names. The raw Bulk IN path logs the captured bytes but does not parse them as UCar messages. This separation is intentional and must remain until the outer transport framing is proven.
