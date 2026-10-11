package com.opencarlink.receiver.protocol;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AoaExactReadTest {
    @Test public void accumulatesPartialReadsUntilExactLength() throws Exception {
        final byte[][] chunks = {
                new byte[] {1, 2, 3},
                new byte[] {4, 5},
                new byte[] {6}
        };
        final int[] index = {0};
        byte[] target = new byte[6];
        int count = AoaExactRead.readExactly(target, target.length, (buffer, offset, length) -> {
            byte[] chunk = chunks[index[0]++];
            assertTrue(chunk.length <= length);
            System.arraycopy(chunk, 0, buffer, offset, chunk.length);
            return chunk.length;
        });
        assertEquals(6, count);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, target);
        assertEquals(3, index[0]);
    }

    @Test public void firstZeroReadReturnsNoData() throws Exception {
        int count = AoaExactRead.readExactly(new byte[8], 8, (buffer, offset, length) -> 0);
        assertEquals(0, count);
    }

    @Test public void zeroAfterPartialProgressIsShortReadError() {
        final int[] calls = {0};
        try {
            AoaExactRead.readExactly(new byte[8], 8, (buffer, offset, length) -> {
                if (calls[0]++ == 0) {
                    buffer[offset] = 42;
                    return 1;
                }
                return 0;
            });
            fail("expected short-read error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("short read"));
        }
    }

    @Test public void negativeReadIsFailure() {
        try {
            AoaExactRead.readExactly(new byte[8], 8, (buffer, offset, length) -> -1);
            fail("expected read failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read failed"));
        }
    }

    @Test public void rejectsImpossibleReaderResult() {
        try {
            AoaExactRead.readExactly(new byte[8], 8, (buffer, offset, length) -> length + 1);
            fail("expected impossible count rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("for"));
        }
    }

    @Test public void rejectsInvalidArguments() throws Exception {
        assertInvalid(null, 1, (buffer, offset, length) -> 1);
        assertInvalid(new byte[4], 5, (buffer, offset, length) -> 1);
        assertInvalid(new byte[4], -1, (buffer, offset, length) -> 1);
        assertInvalid(new byte[4], 1, null);
        assertEquals(0, AoaExactRead.readExactly(new byte[0], 0, (buffer, offset, length) -> {
            fail("zero-length request should not read");
            return 0;
        }));
    }

    private static void assertInvalid(byte[] buffer, int length, AoaExactRead.ChunkReader reader) {
        try {
            AoaExactRead.readExactly(buffer, length, reader);
            fail("expected invalid argument");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (IOException unexpected) {
            fail("unexpected IOException: " + unexpected);
        }
    }
}
