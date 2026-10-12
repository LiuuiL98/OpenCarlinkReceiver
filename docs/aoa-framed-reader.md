# Read-only AOA framed receive path (0.2.5)

## What changed

The diagnostic UI now has two separate receive actions:

- **AOA 按帧读取并解析（只读）** — uses `AoaUsbExactLengthSource` and `AoaRecordReader` to request an 8-byte transport header, validate it before allocation, then read exactly the declared body length (with the official minimum request size of 8 bytes). Local body allocation is capped at 1 MiB.
- **原始 Bulk IN 抓包（诊断）** — preserves the prior raw USB read probe for packet/transfer observation.

The USB adapter accumulates short positive reads. A negative/no-data result before any bytes of a requested segment is treated as no data; a no-data result after partial progress becomes an I/O failure. Incomplete header/body data is never dispatched as a record. The capture loop treats a 500 ms no-header timeout as an idle interval rather than ending immediately, and listens for up to 10 seconds (or 50 complete records); idle counters are logged periodically.

## Trace format

The framed path logs each completed record with channel ID, declared logical body length, and at most 64 bytes of body HEX. For channel-1 records, it additionally logs the current static-analysis-derived create-socket field extraction (port, channel ID, socket type, channel message type). These decoded field names are not proof of business semantics, and the diagnostic path does not open a socket. See [protocol reverse-engineering notes](protocol-reverse-engineering.md) for the consolidated official-APK findings and confidence levels.

The receive path stops at outer framing/channel metadata. It does not open sockets, authenticate, send protocol messages, or feed raw USB chunks to the UCar parser.

## Validation status

The existing pure-Java tests cover exact-read accumulation, invalid/oversized headers, truncated reads, and captured 10-byte channel-1 records. The Android USB adapter itself requires a real device for end-to-end validation. CI must pass before merging; hardware testing on the S9+ receiver and S25 sender is still required.

## Test procedure

1. Install the build produced by the branch CI; confirm the UI identifies itself as 0.2.5.
2. Start a diagnostic session and complete the existing AOA handshake.
3. After the phone re-enumerates as an AOA accessory, grant USB permission.
4. Run **AOA 按帧读取并解析（只读）** first; export the communication trace.
5. If no complete records appear, separately run **原始 Bulk IN 抓包（诊断）** and compare the transfer trace.
6. Do not merge or enable socket creation/business writes until the observed record sequence and official APK behavior agree.
