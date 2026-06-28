package com.artembelikov.listview.protocol;

import com.artembelikov.listview.dto.CapturedPacket;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;

public abstract class AbstractPacketExtractor implements PacketExtractor {

    protected abstract String resolveMethod(SampleResult result);

    protected abstract String resolveUrl(SampleResult result);

    protected abstract String resolveRequestBody(SampleResult result);

    @Override
    public CapturedPacket extract(SampleResult result, int maxBodyBytes) {
        byte[] responseBytes = result.getResponseData() == null ? new byte[0] : result.getResponseData();
        Map<String, String> requestHeaders = PacketPayload.parseHeaders(result.getRequestHeaders());
        Map<String, String> responseHeaders = PacketPayload.parseHeaders(result.getResponseHeaders());
        Charset charset = PacketPayload.resolveCharset(result);

        boolean bodyTruncated = responseBytes.length > maxBodyBytes;
        String contentType = result.getContentType();
        boolean isText = PacketPayload.isTextContentType(contentType)
                && !"bin".equalsIgnoreCase(result.getDataType());
        String responseBody;
        boolean bodyBinary;
        if (isText) {
            responseBody = PacketPayload.decodeText(responseBytes, charset, maxBodyBytes);
            bodyBinary = false;
        } else {
            responseBody = PacketPayload.hexDump(responseBytes, maxBodyBytes);
            bodyBinary = true;
        }

        return new CapturedPacket(
                UUID.randomUUID(),
                supportedType(),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(result.getStartTime()), ZoneOffset.UTC),
                nullToEmpty(result.getThreadName()),
                nullToEmpty(result.getSampleLabel()),
                nullToEmpty(resolveMethod(result)),
                nullToEmpty(resolveUrl(result)),
                requestHeaders,
                nullToEmpty(resolveRequestBody(result)),
                parseStatus(result.getResponseCode()),
                nullToEmpty(result.getResponseMessage()),
                responseHeaders,
                responseBody,
                contentType,
                bodyBinary,
                bodyTruncated,
                Math.max(0, result.getTime()),
                Math.max(0, result.getLatency()),
                Math.max(0, result.getConnectTime()),
                result.isSuccessful(),
                nullToEmpty(firstFailureMessage(result)),
                result.getGroupThreads(),
                result.getAllThreads());
    }

    protected static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstFailureMessage(SampleResult result) {
        AssertionResult[] assertions = result.getAssertionResults();
        if (assertions == null) {
            return null;
        }
        for (AssertionResult assertion : assertions) {
            if (assertion.isFailure() || assertion.isError()) {
                String msg = assertion.getFailureMessage();
                if (msg != null && !msg.isBlank()) {
                    return msg;
                }
            }
        }
        return null;
    }

    private static int parseStatus(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
