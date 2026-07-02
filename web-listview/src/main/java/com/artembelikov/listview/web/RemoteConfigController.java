package com.artembelikov.listview.web;

import com.artembelikov.listview.capture.RemoteConfigService;
import com.artembelikov.listview.dto.RemoteConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class RemoteConfigController {

    private final RemoteConfigService service;

    public RemoteConfigController(RemoteConfigService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    public RemoteConfig rules() {
        return service.load();
    }
}
