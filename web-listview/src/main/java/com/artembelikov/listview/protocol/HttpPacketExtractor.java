package com.artembelikov.listview.protocol;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.springframework.stereotype.Component;

@Component
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
