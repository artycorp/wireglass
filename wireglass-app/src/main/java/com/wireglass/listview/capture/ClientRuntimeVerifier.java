package com.wireglass.listview.capture;

import com.wireglass.listview.client.dto.CapturedPacket;
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
                    "Incompatible wireglass-client detected on the runtime classpath. "
                            + "Rebuild with `mvn -pl wireglass-app -am -DskipTests install` and "
                            + "start with `mvn -pl wireglass-app -am spring-boot:run` or `./wireglass-app/run.sh`.",
                    e);
        }
    }
}
