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
    private transient SampleCapture capture;

    public CapturingReporter() {
        super();
    }

    public CapturingReporter(PacketSink sink, List<PacketExtractor> extractors, int maxBodyBytes) {
        this.sink = sink;
        this.capture = new SampleCapture(extractors, maxBodyBytes);
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
        if (sink == null || capture == null) {
            return;
        }
        capture.capture(event.getResult(), sink);
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
