package com.artembelikov.listview.client.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PacketPayloadTest {

    @Test
    void plainTextLooksLikeText() {
        String stackTrace = "java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.java:10)\n";
        assertTrue(PacketPayload.looksLikeText(stackTrace.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void randomBinaryDoesNotLookLikeText() {
        byte[] binary = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x81, (byte) 0x8D, 0x01, 0x02, 0x03};
        assertFalse(PacketPayload.looksLikeText(binary));
    }

    @Test
    void emptyBytesLookLikeText() {
        assertTrue(PacketPayload.looksLikeText(new byte[0]));
    }
}
