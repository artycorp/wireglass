package com.artembelikov.listview.protocol;

import org.apache.jmeter.samplers.SampleResult;
import org.springframework.stereotype.Component;

@Component
public class TcpPacketExtractor extends AbstractPacketExtractor {

    @Override
    public PacketType supportedType() {
        return PacketType.TCP;
    }

    @Override
    public boolean supports(SampleResult result) {
        return true;
    }

    @Override
    protected String resolveMethod(SampleResult result) {
        return "TCP";
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
