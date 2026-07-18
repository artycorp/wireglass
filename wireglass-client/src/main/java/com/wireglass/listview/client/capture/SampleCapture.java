package com.wireglass.listview.client.capture;

import com.wireglass.listview.client.dto.CapturedPacket;
import com.wireglass.listview.client.protocol.PacketExtractor;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a JMeter {@link SampleResult} into {@link CapturedPacket}s and forwards them to a
 * {@link PacketSink}. Shared by every capture front-end: the jmeter-dsl listener
 * ({@link CapturingReporter}) and the stock-JMeter {@code BackendListenerClient}.
 *
 * <p>Only leaf results are captured: a parent transaction with sub-results reports aggregated
 * time/headers, so recording it as one packet would double-count. The recursion here is the single
 * source of truth for that rule.
 */
public class SampleCapture {

    private static final Logger LOG = LoggerFactory.getLogger(SampleCapture.class);

    private final List<PacketExtractor> extractors;
    private final int maxBodyBytes;

    public SampleCapture(List<PacketExtractor> extractors, int maxBodyBytes) {
        this.extractors = extractors;
        this.maxBodyBytes = maxBodyBytes;
    }

    public void capture(SampleResult result, PacketSink sink) {
        if (result == null || sink == null) {
            return;
        }
        SampleResult[] subResults = result.getSubResults();
        if (subResults != null && subResults.length > 0) {
            for (SampleResult sub : subResults) {
                capture(sub, sink);
            }
        } else {
            captureLeaf(result, sink);
        }
    }

    private void captureLeaf(SampleResult result, PacketSink sink) {
        for (PacketExtractor extractor : extractors) {
            try {
                if (extractor.supports(result)) {
                    CapturedPacket packet = extractor.extract(result, maxBodyBytes);
                    sink.publish(packet);
                    return;
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed to capture sample {}: {}", result.getSampleLabel(), e.toString());
                return;
            }
        }
    }
}
