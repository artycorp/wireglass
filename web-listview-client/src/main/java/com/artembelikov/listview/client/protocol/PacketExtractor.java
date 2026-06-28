package com.artembelikov.listview.client.protocol;

import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.dto.PacketType;
import org.apache.jmeter.samplers.SampleResult;

public interface PacketExtractor {

    PacketType supportedType();

    boolean supports(SampleResult result);

    CapturedPacket extract(SampleResult result, int maxBodyBytes);
}
