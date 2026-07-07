package com.artembelikov.listview.client.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jmeter.samplers.SampleResult;

public final class PacketPayload {

    private PacketPayload() {
    }

    public static Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return headers;
        }
        for (String line : raw.split("\\r?\\n")) {
            int sep = line.indexOf(':');
            if (sep > 0) {
                String name = line.substring(0, sep).trim();
                String value = line.substring(sep + 1).trim();
                headers.merge(name, value, (a, b) -> a + ", " + b);
            }
        }
        return headers;
    }

    public static Charset resolveCharset(SampleResult result) {
        String dataEncoding = result.getDataEncodingNoDefault();
        if (dataEncoding != null && !dataEncoding.isBlank()) {
            try {
                return Charset.forName(dataEncoding);
            } catch (IllegalArgumentException ignored) {
                // fall back to UTF-8 if the encoding name is invalid
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static boolean isTextContentType(String contentType) {
        if (contentType == null) {
            return true;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("javascript")
                || lower.contains("html")
                || lower.contains("form")
                || lower.contains("websocket");
    }

    public static String hexDump(byte[] bytes, int limit) {
        int len = Math.min(bytes.length, limit);
        StringBuilder sb = new StringBuilder(len * 4);
        for (int i = 0; i < len; i += 16) {
            sb.append(String.format("%04X  ", i));
            int end = Math.min(i + 16, len);
            for (int j = i; j < i + 16; j++) {
                sb.append(j < end ? String.format("%02X ", bytes[j]) : "   ");
            }
            sb.append(' ');
            for (int j = i; j < end; j++) {
                int b = bytes[j] & 0xFF;
                sb.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            if (i + 16 < len) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static String decodeText(byte[] bytes, Charset charset, int limit) {
        int len = Math.min(bytes.length, limit);
        return new String(bytes, 0, len, charset);
    }

    public static boolean looksLikeText(byte[] bytes) {
        if (bytes.length == 0) {
            return true;
        }
        int sampleLen = Math.min(bytes.length, 8192);
        CharBuffer decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, sampleLen));
        } catch (CharacterCodingException e) {
            return false;
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
