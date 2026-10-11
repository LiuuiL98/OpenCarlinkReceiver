# OpenCarlinkReceiver

Experimental Android head-unit receiver foundation for researching ICCOA Carlink compatibility.

> **Current status:** diagnostic prototype only. This project does not yet implement the ICCOA Carlink protocol and does not claim compatibility with any phone brand. Real protocol support requires verified specifications and/or an authorized SDK plus physical-device testing.

## Goals

- Broad Android compatibility, targeting **Android 4.4 (API 19)+**.
- Java-first, lightweight Android application.
- USB device diagnostics and Wi-Fi capability/status diagnostics.
- MediaCodec capability inspection and a SurfaceView rendering foundation.
- Separate transport, protocol, rendering, and diagnostics modules so verified Carlink support can be added later.
- GitHub Actions build workflow.

## Build

Open this repository in Android Studio, or build with Gradle 8.9 and JDK 17:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## First prototype features

- Device/API/ABI summary.
- USB Host feature and attached USB device list.
- Wi-Fi state and Wi-Fi Direct feature availability.
- Available H.264 decoders from Android MediaCodec.
- A basic receiver screen and diagnostic log.

## Important protocol notes

- USB being visible to Android does not establish an ICCOA session.
- Wi-Fi connectivity does not establish Carlink compatibility.
- Do not invent protocol packet formats or handshake messages.
- The protocol adapter is intentionally a placeholder until the relevant specification/SDK and legal integration conditions are verified.
- Android 4.4 support describes the app's platform baseline, not guaranteed compatibility with every SDK or phone.

## Roadmap

1. Build and install the diagnostic prototype.
2. Collect diagnostics from real head units and supported phones.
3. Confirm ICCOA Carlink SDK availability, API-level support, native ABI requirements, and integration/approval terms.
4. Implement and test a real session for one phone model.
5. Add video, audio, touch/control channels, reconnect handling, then expand device coverage.
6. Investigate desktop-fusion features only after basic projection works.

## License

No license has been selected yet. Until one is added, assume all rights are reserved. Review third-party SDK terms before distribution.
