package com.artembelikov.listview.capture;

import com.artembelikov.listview.dto.CapturedPacket;
import com.artembelikov.listview.protocol.PacketExtractor;
import java.io.Serializable;
import java.util.List;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrafficCapturingReporter extends AbstractTestElement
        implements NoThreadClone, Serializable, SampleListener {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficCapturingReporter.class);
    private static final long serialVersionUID = 1L;

    private transient PacketBus bus;
    private transient List<PacketExtractor> extractors;
    private transient int maxBodyBytes;

    public TrafficCapturingReporter() {
        super();
    }

    public TrafficCapturingReporter(PacketBus bus, List<PacketExtractor> extractors, int maxBodyBytes) {
        this.bus = bus;
        this.extractors = extractors;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void sampleOccurred(SampleEvent event) {
        if (bus == null || extractors == null) {
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
                    bus.publish(packet);
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
