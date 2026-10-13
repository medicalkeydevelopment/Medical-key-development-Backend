package com.secure.hms.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface LocationService {

    // Captures IP-based location for the given client and broadcasts it
    // to the admin dashboard.
    void processLocation(String clientIp);

    // Admin dashboard subscribes to the live attendance stream.
    SseEmitter subscribe();
}