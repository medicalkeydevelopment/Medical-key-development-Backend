package com.secure.hms.service;

import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface LocationService {

    String processLocation(
            Map<String, Object> request,
            String clientIp
    );

    SseEmitter subscribe();
}