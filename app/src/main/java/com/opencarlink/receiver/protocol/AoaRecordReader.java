package com.opencarlink.receiver.protocol;

import java.io.IOException;

/**
 * Reads one logical AOA record using a transport adapter that guarantees an
 * exact-length read or reports failure. It intentionally knows nothing about
 * Android USB classes, sockets, or authentication.
 */
public final class AoaRecordReader {
    public interface ExactLengthSource {
        /**
         * Fill {@code destination} with exactly {@code requestedLength} bytes.
         * Return that exact length, return zero only when no bytes are available
         * before a record starts, or throw IOException for an incomplete read.
         */
        int readExact(byte[] destination, int requestedLength) throws IOException;
    }

    public static final class Record {
        public final AoaTransportHeaderProbe.Header header;
        /** Buffer length is the official requested read length; logicalBodyLength may be smaller. */
        public final byte[] body;
        public final int logicalBodyLength;
        public final AoaCreateSocketRecord createSocket;

        private Record(AoaTransportHeaderProbe.Header header, byte[] body,
                       int logicalBodyLength, AoaCreateSocketRecord createSocket) {
            this.header = header;
            this.body = body;
            this.logicalBodyLength = logicalBodyLength;
            this.createSocket = createSocket;
        }

        public boolean isCreateSocketRecord() {
            return header.isCreateSocketRecord();
        }
    }

    private AoaRecordReader() {}

    /**
     * Read one record. A return value of {@code null} means no header bytes
     * were available; partial headers/bodies are errors, never dispatched.
     */
    public static Record readOne(ExactLengthSource source, int localBodyLimit)
            throws IOException {
        if (source == null) throw new IllegalArgumentException("source == null");
        if (localBodyLimit < AoaTransportHeaderProbe.MIN_BODY_LENGTH
                || localBodyLimit > AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("local body limit out of range: " + localBodyLimit);
        }

        byte[] rawHeader = new byte[AoaTransportHeaderProbe.HEADER_LENGTH];
        int headerCount = source.readExact(rawHeader, rawHeader.length);
        if (headerCount == 0) return null;
        AoaBodyReadPolicy.requireCompleteRead(rawHeader.length, headerCount);

        AoaTransportHeaderProbe.Header header =
                AoaTransportHeaderProbe.decode(rawHeader, headerCount, localBodyLimit);
        int requestedBodyLength = AoaBodyReadPolicy.requestedReadLength(header, localBodyLimit);
        byte[] body = new byte[requestedBodyLength];
        int bodyCount = source.readExact(body, requestedBodyLength);
        AoaBodyReadPolicy.requireCompleteRead(requestedBodyLength, bodyCount);

        AoaCreateSocketRecord createSocket = null;
        if (header.isCreateSocketRecord()) {
            createSocket = AoaCreateSocketRecord.decode(body, header.bodyLength);
        }
        return new Record(header, body, header.bodyLength, createSocket);
    }
}
