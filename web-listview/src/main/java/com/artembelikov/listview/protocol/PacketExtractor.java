package com.artembelikov.listview.protocol;

import com.artembelikov.listview.dto.CapturedPacket;
import org.apache.jmeter.samplers.SampleResult;

public interface PacketExtractor {

    PacketType supportedType();

    boolean supports(SampleResult result);

    CapturedPacket extract(SampleResult result, int maxBodyBytes);
}
