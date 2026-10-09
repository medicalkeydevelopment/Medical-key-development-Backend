package com.secure.hms.service;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import tools.jackson.databind.ObjectMapper;

@Service
public class LocationServiceImpl implements LocationService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final List<SseEmitter> emitters =
            new CopyOnWriteArrayList<>();

    // In-memory recent reports. A database is needed for permanent storage.
    private final List<Map<String, Object>> recentReports =
            new CopyOnWriteArrayList<>();

    private static final int MAX_REPORTS = 500;

    public LocationServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String processLocation(
            Map<String, Object> request,
            String clientIp) {

        Map<String, Object> event = new LinkedHashMap<>();

        event.put("capturedAt", Instant.now().toString());
        event.put(
                "userId",
                request == null ? null : request.get("userId")
        );

        Double latitude = request == null
                ? null : toDouble(request.get("gpsLatitude"));

        Double longitude = request == null
                ? null : toDouble(request.get("gpsLongitude"));

        Double accuracy = request == null
                ? null : toDouble(request.get("gpsAccuracy"));

        boolean gpsAvailable = request != null
                && Boolean.TRUE.equals(request.get("gpsAvailable"))
                && validCoordinates(latitude, longitude);

        event.put("gpsAvailable", gpsAvailable);
        event.put("gpsLatitude", gpsAvailable ? latitude : null);
        event.put("gpsLongitude", gpsAvailable ? longitude : null);
        event.put("gpsAccuracy", gpsAvailable ? accuracy : null);

        event.put("ipAddress", clientIp);
        event.put("ipCity", null);
        event.put("ipRegion", null);
        event.put("ipCountry", null);
        event.put("ipLatitude", null);
        event.put("ipLongitude", null);
        event.put("ipLookupMessage", "IP location unavailable");

        Map<String, Object> ipData = lookupIpLocation(clientIp);

        if (ipData != null && !Boolean.TRUE.equals(ipData.get("error"))) {
            event.put("ipCity", ipData.get("city"));
            event.put("ipRegion", ipData.get("region"));
            event.put("ipCountry", ipData.get("country_name"));
            event.put("ipLatitude", toDouble(ipData.get("latitude")));
            event.put("ipLongitude", toDouble(ipData.get("longitude")));
            event.put("ipLookupMessage", "IP lookup successful");
        }

        // Prefer GPS coordinates for the map when valid.
        Double mapLatitude = gpsAvailable
                ? latitude : toDouble(event.get("ipLatitude"));

        Double mapLongitude = gpsAvailable
                ? longitude : toDouble(event.get("ipLongitude"));

        String mapsLink = null;

        if (validCoordinates(mapLatitude, mapLongitude)) {
            mapsLink = "https://www.google.com/maps?q="
                    + mapLatitude + "," + mapLongitude;
        }

        event.put("mapsLink", mapsLink);

        // Save recent report before broadcasting it.
        recentReports.add(new LinkedHashMap<>(event));

        while (recentReports.size() > MAX_REPORTS) {
            recentReports.remove(0);
        }

        broadcast(event);

        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to serialize location report", e);
        }
    }

    @Override
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));

        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });

        emitter.onError(error -> emitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("connected", true)));

            // Replay recent reports when the admin dashboard connects.
            for (Map<String, Object> report :
                    new ArrayList<>(recentReports)) {
                emitter.send(SseEmitter.event()
                        .name("location")
                        .data(report));
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private void broadcast(Map<String, Object> event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("location")
                        .data(event));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lookupIpLocation(String ip) {
        if (!isPublicIp(ip)) {
            return null;
        }

        try {
            return restTemplate.getForObject(
                    "https://ipapi.co/{ip}/json/",
                    Map.class,
                    ip
            );
        } catch (RestClientException e) {
            return null;
        }
    }

    private boolean isPublicIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        try {
            // Reject hostnames and malformed IP strings.
            if (ip.contains("%") || ip.contains(" ")) {
                return false;
            }

            boolean ipv4 = ip.matches(
                    "^(\\d{1,3}\\.){3}\\d{1,3}$");

            boolean ipv6 = ip.contains(":")
                    && ip.matches("^[0-9a-fA-F:.]+$");

            if (!ipv4 && !ipv6) {
                return false;
            }

            InetAddress address = InetAddress.getByName(ip);

            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }

            if (address.getAddress().length == 4) {
                byte[] bytes = address.getAddress();

                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;

                // Exclude IPv4 carrier-grade NAT range 100.64.0.0/10.
                if (first == 100 && second >= 64 && second <= 127) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            double result = number.doubleValue();
            return Double.isFinite(result) ? result : null;
        }

        if (value instanceof String string) {
            try {
                double result = Double.parseDouble(string);
                return Double.isFinite(result) ? result : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }
}