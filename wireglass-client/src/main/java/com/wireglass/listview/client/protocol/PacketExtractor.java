package com.wireglass.listview.client.protocol;

import com.wireglass.listview.client.dto.CapturedPacket;
import com.wireglass.listview.client.dto.PacketType;
import org.apache.jmeter.samplers.SampleResult;

public interface PacketExtractor {

    PacketType supportedType();

    boolean supports(SampleResult result);

    CapturedPacket extract(SampleResult result, int maxBodyBytes);
}
