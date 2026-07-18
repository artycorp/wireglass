package com.wireglass.listview.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    @GetMapping("/http")
    public Map<String, Object> httpDemo() {
        return Map.of(
                "ok", true,
                "service", "wireglass-demo",
                "timestamp", Instant.now().toString());
    }
}
