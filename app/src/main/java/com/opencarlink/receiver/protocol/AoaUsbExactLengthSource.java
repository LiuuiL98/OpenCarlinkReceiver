package com.opencarlink.receiver.protocol;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;

/**
 * Android USB adapter for the pure-Java AOA record reader.
 *
 * It accumulates short positive Bulk IN reads until the requested length is
 * satisfied. A timeout/no-data before a record starts returns zero; a timeout
 * after partial progress is treated as an incomplete read and fails closed.
 */
public final class AoaUsbExactLengthSource implements AoaRecordReader.ExactLengthSource {
    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpoint;
    private final int timeoutMs;

    public AoaUsbExactLengthSource(UsbDeviceConnection connection,
                                   UsbEndpoint endpoint,
                                   int timeoutMs) {
        if (connection == null) throw new IllegalArgumentException("connection == null");
        if (endpoint == null) throw new IllegalArgumentException("endpoint == null");
        if (timeoutMs < 1) throw new IllegalArgumentException("timeoutMs must be positive");
        this.connection = connection;
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public int readExact(final byte[] destination, final int requestedLength) throws IOException {
        return AoaExactRead.readExactly(destination, requestedLength,
                new AoaExactRead.ChunkReader() {
                    @Override
                    public int read(byte[] target, int offset, int length) {
                        int count = connection.bulkTransfer(endpoint, target, offset, length, timeoutMs);
                        if (count < 0) {
                            // Android commonly reports a timeout as a negative result.
                            // Treat it as no-data only before any bytes of this exact read.
                            return offset == 0 ? 0 : -1;
                        }
                        return count;
                    }
                });
    }
}
