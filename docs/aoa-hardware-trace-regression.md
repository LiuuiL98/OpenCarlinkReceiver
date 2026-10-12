# AOA hardware trace regression fixture (2026-10-12)

Receiver: Samsung SM-G9650, Android 10 / API 29
Sender: Samsung S25
Diagnostic app: OpenCarlinkReceiver 0.2.4
Trace: read-only Bulk IN capture on AOA accessory 0x18D1:0x2D01, interface 0, endpoint 0x81.

## Captured sequence

The four successful reads were:

1. `00 00 00 01 00 00 00 0A` (8 bytes)
2. `00 00 1C 44 FF FF FF FF 01 02` (10 bytes)
3. `00 00 00 01 00 00 00 0A` (8 bytes)
4. `00 00 10 E1 FF FF FF FF 01 02` (10 bytes)

The strongest current interpretation is two repetitions of an 8-byte AOA header followed by its declared 10-byte body. The headers decode big-endian as channel ID 1 and body length 10. This matches the static analysis documented in `aoa-transport-boundary.md`, where channel 1 is the create-socket discriminator.

The two body candidates decode by the known field offsets (0, 4, 8, 9) as:

| Field | Body A | Body B |
|---|---:|---:|
| bytes 0..3 | 7236 | 4321 |
| bytes 4..7 | 0xFFFFFFFF (Java int -1) | 0xFFFFFFFF (Java int -1) |
| byte 8 | 1 | 1 |
| byte 9 | 2 | 2 |

These are framing/parser observations only. Do not infer that the first field is definitely a network port, that the second is a valid channel identifier, or that bytes 8 and 9 have a known business meaning until the official APK's `handleCreateSocket` implementation and a real connection lifecycle establish it.

## Regression scope

`AoaHardwareTraceRegressionTest` uses the existing pure-Java `AoaRecordReader` and captured bytes to validate:

- big-endian outer header decoding;
- a channel-1 record with a 10-byte body;
- decoding at the known body offsets;
- rejection of an invalid header before reading its body.

This test fixture does not send USB data, open sockets, authenticate, or claim end-to-end ICCOA compatibility.

## Integration gate

Before wiring this parser into the live Android USB path, implement and test a USB exact-length adapter that accumulates short positive reads until the requested count is reached, handles timeout/no-data only before a record starts, and fails closed on partial headers or bodies. Preserve endpoint/interface details and USB detach/re-enumeration handling. Do not pass raw Bulk IN chunks directly to the UCar parser.


## 2026-10-12：USB 专项下一轮（0.2.6-dev.3）

- 本轮不把 Wi-Fi/Wi-Fi Direct 状态作为排查前提；当前阶段聚焦 USB AOA Bulk IN/OUT。
- 将主界面原“RTSP/UIBC 套接字生命周期”按钮改为只读 AOA 外层记录读取入口，避免本轮测试误触发实验性 TCP 生命周期探测及猜测控制记录发送。
- 原始 Bulk IN 抓取窗口由约 5 秒延长到约 15 秒，最多记录 100 次成功读取、累计 256 KiB；每次返回块仍记录返回长度和 HEX。该模式是原始 USB 传输块，不应直接视为完整协议消息。
- 下一次硬件测试建议先执行 AOA 切换，再运行“原始 Bulk IN 抓包（诊断）”导出逐次 HEX；随后运行“USB AOA 连续记录读取（只读，不发送控制帧）”对照记录头和正文边界。两次测试应分别执行，避免多个读线程同时占用同一 Bulk IN 端点。
- 仍不发送未经验证的 Bulk OUT 业务帧；只有原始字节、官方解析逻辑与测试向量相互印证后，才实现下一步协议处理。


## 2026-10-12：并发 USB 读取保护（0.2.6-dev.4）

- 0.2.5 日志中，按帧读取分别在 09:31:24 与 09:31:30 启动；第一轮约 10 秒后才结束，因此两轮时间窗口重叠。两个读取线程可能同时消费同一个 Bulk IN 端点，导致数据被分流、第二轮读不到头部，以及异常高的 `AOA_FRAMED_IDLE` 计数。
- 新版本在原始 Bulk IN、AOA 按帧读取与实验性套接字探测之间增加共享原子互斥锁；同一时刻只允许一个 USB 诊断读取线程运行，重复触发会记录 `USB_READ_GUARD_BLOCK` 并提示等待。
- 原始 HEX 中的每条创建套接字记录显示为 8 字节外层头与 10 字节正文分两次 Bulk IN 返回；`AoaUsbExactLengthSource` 应在读取正文时累积到完整长度。下一轮应先单独运行按帧读取，避免原始抓包先消费完手机只发送一次的请求。
- 建议测试顺序：先 AOA 切换后直接运行按帧读取并导出日志；如果需要原始逐次 HEX，再重新触发一次完整 AOA 流程，单独运行原始抓包。不要在读取进行时再次点击其他 USB 读取按钮。
