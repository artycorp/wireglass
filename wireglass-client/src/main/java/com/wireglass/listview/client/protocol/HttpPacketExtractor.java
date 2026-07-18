package com.wireglass.listview.client.protocol;

import com.wireglass.listview.client.dto.PacketType;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;

public class HttpPacketExtractor extends AbstractPacketExtractor {

    @Override
    public PacketType supportedType() {
        return PacketType.HTTP;
    }

    @Override
    public boolean supports(SampleResult result) {
        return result instanceof HTTPSampleResult;
    }

    @Override
    protected String resolveMethod(SampleResult result) {
        return ((HTTPSampleResult) result).getHTTPMethod();
    }

    @Override
    protected String resolveUrl(SampleResult result) {
        return ((HTTPSampleResult) result).getUrlAsString();
    }

    @Override
    protected String resolveRequestBody(SampleResult result) {
        return ((HTTPSampleResult) result).getQueryString();
    }
}
