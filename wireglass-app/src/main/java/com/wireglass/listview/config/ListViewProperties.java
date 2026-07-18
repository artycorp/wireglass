package com.wireglass.listview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.listview")
public class ListViewProperties {

    private int ringBufferSize = 5000;
    private int maxBodyBytes = 262144;
    private String remoteConfigUrl;

    public int getRingBufferSize() {
        return ringBufferSize;
    }

    public void setRingBufferSize(int ringBufferSize) {
        this.ringBufferSize = ringBufferSize;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public String getRemoteConfigUrl() {
        return remoteConfigUrl;
    }

    public void setRemoteConfigUrl(String remoteConfigUrl) {
        this.remoteConfigUrl = remoteConfigUrl;
    }
}
