package com.opencarlink.receiver.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, incremental decoder for AOA records. This is a pure-Java utility;
 * it is deliberately not wired into raw USB reads until the caller has a
 * trustworthy byte stream and verified record boundaries.
 *
 * The official implementation requests max(declared body length, 8) bytes
 * after each 8-byte header, but passes only the declared length downstream.
 * This decoder consumes that many wire-body bytes while exposing only the
 * declared logical body bytes.
 */
public final class AoaTransportRecordDecoder {
    public static final int DEFAULT_MAX_BODY_LENGTH = 1024 * 1024;

    public static final class Record {
        public final int channelId;
        public final int declaredBodyLength;
        private final byte[] body;

        private Record(int channelId, int declaredBodyLength, byte[] body) {
            this.channelId = channelId;
            this.declaredBodyLength = declaredBodyLength;
            this.body = body;
        }

        public boolean isCreateSocketRecord() {
            return channelId == AoaTransportHeaderProbe.CREATE_SOCKET_CHANNEL_ID;
        }

        public byte[] copyBody() {
            byte[] copy = new byte[body.length];
            System.arraycopy(body, 0, copy, 0, body.length);
            return copy;
        }
    }

    private final int maxBodyLength;
    private final byte[] header = new byte[AoaTransportHeaderProbe.HEADER_LENGTH];
    private int headerCount;
    private AoaTransportHeaderProbe.Header currentHeader;
    private byte[] wireBody;
    private int bodyCount;

    public AoaTransportRecordDecoder() {
        this(DEFAULT_MAX_BODY_LENGTH);
    }

    public AoaTransportRecordDecoder(int maxBodyLength) {
        if (maxBodyLength < AoaTransportHeaderProbe.HEADER_LENGTH
                || maxBodyLength > AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("maxBodyLength must be 8..64 MiB");
        }
        this.maxBodyLength = maxBodyLength;
    }

    /**
     * Consume a byte range and return all complete records found in it.
     * Partial header/body bytes are retained for the next call.
     */
    public List<Record> feed(byte[] input, int offset, int length) {
        if (input == null) throw new IllegalArgumentException("input == null");
        if (offset < 0 || length < 0 || offset > input.length - length) {
            throw new IllegalArgumentException("invalid input range");
        }
        List<Record> completed = new ArrayList<Record>();
        int cursor = offset;
        int end = offset + length;
        while (cursor < end) {
            if (currentHeader == null) {
                int copied = Math.min(AoaTransportHeaderProbe.HEADER_LENGTH - headerCount, end - cursor);
                System.arraycopy(input, cursor, header, headerCount, copied);
                cursor += copied;
                headerCount += copied;
                if (headerCount < AoaTransportHeaderProbe.HEADER_LENGTH) continue;

                currentHeader = AoaTransportHeaderProbe.decode(header, header.length);
                if (currentHeader.bodyLength > maxBodyLength) {
                    resetCurrentRecord();
                    throw new IllegalArgumentException("body length exceeds local cap: "
                            + currentHeader.bodyLength + " > " + maxBodyLength);
                }
                wireBody = new byte[currentHeader.requestedBodyReadLength()];
                bodyCount = 0;
            }

            int copied = Math.min(wireBody.length - bodyCount, end - cursor);
            System.arraycopy(input, cursor, wireBody, bodyCount, copied);
            cursor += copied;
            bodyCount += copied;
            if (bodyCount == wireBody.length) {
                byte[] logicalBody = new byte[currentHeader.bodyLength];
                System.arraycopy(wireBody, 0, logicalBody, 0, logicalBody.length);
                completed.add(new Record(currentHeader.channelId,
                        currentHeader.bodyLength, logicalBody));
                resetCurrentRecord();
            }
        }
        return Collections.unmodifiableList(completed);
    }

    public boolean hasPartialRecord() {
        return headerCount != 0 || currentHeader != null;
    }

    /** Discard partial input after a transport error or disconnect. */
    public void reset() {
        resetCurrentRecord();
    }

    private void resetCurrentRecord() {
        headerCount = 0;
        currentHeader = null;
        wireBody = null;
        bodyCount = 0;
    }
}
