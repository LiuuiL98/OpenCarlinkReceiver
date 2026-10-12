package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaCreateSocketFrameEncoderTest {
    @Test public void encodesObservedRtspSocketResponseLayout() {
        byte[] frame = AoaCreateSocketFrameEncoder.encode(
                7236, 16, AoaCreateSocketFrameEncoder.SOCKET_TYPE_RTSP, 2);
        assertEquals(18, frame.length);
        assertArrayEquals(new byte[] {
                0,0,0,1, 0,0,0,10,
                0,0,0x1c,0x44, 0,0,0,16, 3,2
        }, frame);
    }

    @Test public void encodesUibcSocketType() {
        byte[] frame = AoaCreateSocketFrameEncoder.encode(
                4321, 17, AoaCreateSocketFrameEncoder.SOCKET_TYPE_UIBC, 2);
        assertEquals(0x10, frame[10] & 0xff);
        assertEquals(0xe1, frame[11] & 0xff);
        assertEquals(17, frame[15] & 0xff);
        assertEquals(4, frame[16] & 0xff);
        assertEquals(2, frame[17] & 0xff);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidAssignedChannel() {
        AoaCreateSocketFrameEncoder.encode(7236, -1, 3, 2);
    }
}
