package com.wireglass.listview.client.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wireglass.listview.client.dto.CapturedPacket;
import com.wireglass.listview.client.dto.PacketType;
import java.nio.charset.StandardCharsets;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class AbstractPacketExtractorTest {

    private static class TestExtractor extends AbstractPacketExtractor {
        @Override
        public PacketType supportedType() {
            return PacketType.HTTP;
        }

        @Override
        public boolean supports(SampleResult result) {
            return true;
        }

        @Override
        protected String resolveMethod(SampleResult result) {
            return "GET";
        }

        @Override
        protected String resolveUrl(SampleResult result) {
            return "http://example.test";
        }

        @Override
        protected String resolveRequestBody(SampleResult result) {
            return "";
        }
    }

    @Test
    void aStackTraceFlaggedBinaryByJMeterIsShownAsText() {
        String stackTrace = "java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.java:10)\n";
        SampleResult result = new SampleResult();
        result.setResponseData(stackTrace.getBytes(StandardCharsets.UTF_8));
        result.setDataType(SampleResult.BINARY);
        result.setContentType(null);

        CapturedPacket packet = new TestExtractor().extract(result, 4096);

        assertFalse(packet.bodyBinary());
        assertEquals(stackTrace, packet.responseBody());
    }

    @Test
    void actualBinaryFlaggedBinaryStillHexDumps() {
        byte[] binary = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x81, (byte) 0x8D, 0x01, 0x02, 0x03};
        SampleResult result = new SampleResult();
        result.setResponseData(binary);
        result.setDataType(SampleResult.BINARY);
        result.setContentType(null);

        CapturedPacket packet = new TestExtractor().extract(result, 4096);

        assertTrue(packet.bodyBinary());
    }
}
