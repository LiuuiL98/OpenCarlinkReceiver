package com.opencarlink.receiver.protocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.Assert.*;

public class UCarStreamParserTest {
    private byte[] frame(int totalLength, int seed) {
        if (totalLength < UCarHeaderChecksum.HEADER_MIN_LENGTH) {
            throw new IllegalArgumentException();
        }
        byte[] result = new byte[totalLength];
        result[0] = (byte) (totalLength >>> 24);
        result[1] = (byte) (totalLength >>> 16);
        result[2] = (byte) (totalLength >>> 8);
        result[3] = (byte) totalLength;
        for (int i = 4; i < 18; i++) {
            result[i] = (byte) (seed + i * 13);
        }
        int crc = UCarHeaderChecksum.computeChecksum(result);
        result[18] = (byte) (crc >>> 8);
        result[19] = (byte) crc;
        for (int i = 20; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }

    @Test
    public void emitsFrameOnlyAfterAllBytesArrive() {
        UCarStreamParser parser = new UCarStreamParser();
        byte[] frame = frame(27, 1);
        assertTrue(parser.feed(slice(frame, 0, 9)).isEmpty());
        assertEquals(9, parser.getPendingByteCount());
        assertTrue(parser.feed(slice(frame, 9, 19)).isEmpty());
        List<byte[]> output = parser.feed(slice(frame, 19, frame.length));
        assertEquals(1, output.size());
        assertArrayEquals(frame, output.get(0));
        assertEquals(1, parser.getParsedFrames());
        assertEquals(0, parser.getPendingByteCount());
    }

    @Test
    public void emitsMultipleFramesFromOneChunk() {
        UCarStreamParser parser = new UCarStreamParser();
        byte[] first = frame(20, 2);
        byte[] second = frame(24, 7);
        byte[] combined = concat(first, second);
        List<byte[]> output = parser.feed(combined);
        assertEquals(2, output.size());
        assertArrayEquals(first, output.get(0));
        assertArrayEquals(second, output.get(1));
    }

    @Test
    public void skipsInvalidCandidateAndResynchronizes() {
        UCarStreamParser parser = new UCarStreamParser();
        byte[] valid = frame(22, 3);
        byte[] corrupt = valid.clone();
        corrupt[7] ^= 0x40;
        List<byte[]> output = parser.feed(concat(corrupt, valid));
        assertEquals(1, output.size());
        assertArrayEquals(valid, output.get(0));
        assertTrue(parser.getInvalidChecksumCandidates() > 0);
        assertTrue(parser.getDiscardedBytes() > 0);
    }

    @Test
    public void rejectsImpossibleMaximum() {
        try {
            new UCarStreamParser(19);
            fail("Expected invalid max length");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullChunk() {
        try {
            new UCarStreamParser().feed(null);
            fail("Expected null rejection");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    private static byte[] slice(byte[] input, int start, int end) {
        byte[] output = new byte[end - start];
        System.arraycopy(input, start, output, 0, output.length);
        return output;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(a, 0, a.length);
        output.write(b, 0, b.length);
        return output.toByteArray();
    }
}
