package com.artembelikov.listview.capture;

import com.artembelikov.listview.config.ListViewProperties;
import com.artembelikov.listview.protocol.PacketExtractor;
import com.artembelikov.listview.protocol.PacketType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrafficCaptureListenerFactory {

    private final PacketBus bus;
    private final ListViewProperties props;
    private final List<PacketExtractor> extractors;

    @Autowired
    public TrafficCaptureListenerFactory(PacketBus bus, ListViewProperties props,
                                         List<PacketExtractor> extractors) {
        this.bus = bus;
        this.props = props;
        this.extractors = ordered(extractors);
    }

    private static List<PacketExtractor> ordered(List<PacketExtractor> injectedList) {
        List<PacketExtractor> copy = new ArrayList<>(injectedList);
        copy.sort(Comparator.comparingInt(TrafficCaptureListenerFactory::order));
        return copy;
    }

    private static int order(PacketExtractor extractor) {
        PacketType type = extractor.supportedType();
        if (type == PacketType.HTTP) {
            return 0;
        }
        if (type == PacketType.WEBSOCKET) {
            return 1;
        }
        return 2;
    }

    public TrafficCaptureListener newListener() {
        return new TrafficCaptureListener(bus, extractors, props.getMaxBodyBytes());
    }
}
