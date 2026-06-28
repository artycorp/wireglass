package com.artembelikov.listview.client.capture;

import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.protocol.PacketExtractor;
import java.io.Serializable;
import java.util.List;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JMeter test element that turns each {@link SampleResult} into a {@link CapturedPacket} (via the
 * registered {@link PacketExtractor}s) and forwards it to a {@link PacketSink}. Shared by the web
 * app's in-process runner and the remote client.
 *
 * <p>{@link NoThreadClone} keeps a single instance (and a single sink connection) for the whole test,
 * regardless of the thread count.
 */
public class CapturingReporter extends AbstractTestElement
        implements NoThreadClone, Serializable, SampleListener, TestStateListener {

    private static final Logger LOG = LoggerFactory.getLogger(CapturingReporter.class);
    private static final long serialVersionUID = 1L;

    private transient PacketSink sink;
    private transient List<PacketExtractor> extractors;
    private transient int maxBodyBytes;

    public CapturingReporter() {
        super();
    }

    public CapturingReporter(PacketSink sink, List<PacketExtractor> extractors, int maxBodyBytes) {
        this.sink = sink;
        this.extractors = extractors;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void testStarted() {
        openSink();
    }

    @Override
    public void testStarted(String host) {
        openSink();
    }

    private void openSink() {
        if (sink != null) {
            try {
                sink.open();
            } catch (RuntimeException e) {
                LOG.warn("Failed to open capture sink: {}", e.toString());
            }
        }
    }

    @Override
    public void testEnded() {
        closeSink();
    }

    @Override
    public void testEnded(String host) {
        closeSink();
    }

    private void closeSink() {
        if (sink != null) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                LOG.debug("Capture sink close: {}", e.toString());
            }
        }
    }

    @Override
    public void sampleOccurred(SampleEvent event) {
        if (sink == null || extractors == null) {
            return;
        }
        process(event.getResult());
    }

    private void process(SampleResult result) {
        if (result == null) {
            return;
        }
        capture(result);
        SampleResult[] subResults = result.getSubResults();
        if (subResults != null) {
            for (SampleResult sub : subResults) {
                process(sub);
            }
        }
    }

    private void capture(SampleResult result) {
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

    @Override
    public void sampleStarted(SampleEvent e) {
        // no-op
    }

    @Override
    public void sampleStopped(SampleEvent e) {
        // no-op
    }
}
