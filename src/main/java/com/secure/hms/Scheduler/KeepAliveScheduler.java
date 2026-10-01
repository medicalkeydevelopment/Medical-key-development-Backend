package com.secure.hms.Scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@Profile("render")
public class KeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveScheduler.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    
    
    
    @Value("${app.keep-alive.url}")
    private String keepAliveUrl;

    // Every 14 minutes (840000 ms), first ping 1 minute after startup
    @Scheduled(
            fixedRateString = "${app.keep-alive.interval-ms:840000}",
            initialDelayString = "${app.keep-alive.initial-delay-ms:60000}"
    )
    public void ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keepAliveUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            log.info("Keep-alive ping -> {} ({})", keepAliveUrl, response.statusCode());
        } catch (Exception e) {
            log.warn("Keep-alive ping failed: {}", e.getMessage());
        }
    }
}