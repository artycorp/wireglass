package com.artembelikov.listview.capture;

import com.artembelikov.listview.client.dto.CapturedPacket;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClientRuntimeVerifier {

    @PostConstruct
    void verify() {
        requireMethod("runId");
        requireMethod("withRunId", UUID.class);
    }

    private void requireMethod(String name, Class<?>... parameterTypes) {
        try {
            Method ignored = CapturedPacket.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Incompatible web-listview-client detected on the runtime classpath. "
                            + "Rebuild with `mvn -pl web-listview -am -DskipTests install` and "
                            + "start with `mvn -pl web-listview -am spring-boot:run` or `./web-listview/run.sh`.",
                    e);
        }
    }
}
