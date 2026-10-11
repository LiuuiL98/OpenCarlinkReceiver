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

## What is not established yet

- The semantic names and units of the two 32-bit words in the outer 8-byte header.
- Which word controls the second read's requested byte count, including whether the value is a body length, total record length, or another quantity.
- Whether a USB Bulk IN return boundary always corresponds to one complete outer header/body segment, or whether the official loop retries/accumulates partial reads.
- The exact discriminator that chooses create-socket handling versus ordinary channel delivery.

Do not treat the two endian renderings in the app's probe as proof of byte order or field meaning. The current diagnostic intentionally prints candidates only.

## Next analysis gate

Before writing a reassembler, disassemble the method body with register tracking and record, instruction by instruction:

1. The destination registers of both `bytesToInt` calls.
2. The comparison/branch using each value.
3. The value passed as the length argument to the second `bulkTransferIn` call and the allocation size of its body buffer.
4. The branch condition selecting `handleCreateSocket` versus `getReadThreadByChannelId`.
5. Error, short-read, and loop-back behavior.

Only after those operands and branches are documented should a bounded parser be introduced. Its tests must cover split header, split body, multiple records in one read, invalid/oversized lengths, truncated input, and the create-socket minimum length. Until then, keep raw Bulk IN capture read-only and do not feed it directly into the UCar 20-byte parser.

## Current implementation boundary

`AoaTransportHeaderProbe` is a diagnostic display helper only: it reports raw bytes and both endian interpretations without assigning semantic field names. The raw Bulk IN path logs the captured bytes but does not parse them as UCar messages. This separation is intentional and must remain until the outer transport framing is proven.
