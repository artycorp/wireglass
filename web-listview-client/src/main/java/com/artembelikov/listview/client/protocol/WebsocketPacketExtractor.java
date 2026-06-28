package com.artembelikov.listview.client.protocol;

import com.artembelikov.listview.client.dto.PacketType;

import org.apache.jmeter.samplers.SampleResult;

public class WebsocketPacketExtractor extends AbstractPacketExtractor {

    @Override
    public PacketType supportedType() {
        return PacketType.WEBSOCKET;
    }

    @Override
    public boolean supports(SampleResult result) {
        String typeName = result.getClass().getName();
        String label = nullToEmpty(result.getSampleLabel());
        String samplerData = nullToEmpty(result.getSamplerData());
        return typeName.contains("luminis")
                || typeName.toLowerCase().contains("websocket")
                || label.toLowerCase().contains("websocket")
                || samplerData.contains("ws://")
                || samplerData.contains("wss://");
    }

    @Override
    protected String resolveMethod(SampleResult result) {
        return "WS";
    }

    @Override
    protected String resolveUrl(SampleResult result) {
        return result.getUrlAsString();
    }

    @Override
    protected String resolveRequestBody(SampleResult result) {
        return result.getSamplerData();
    }
}
