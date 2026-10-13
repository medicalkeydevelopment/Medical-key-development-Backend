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

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LocationServiceImpl implements LocationService {

    private final RestTemplate restTemplate;

    private final List<SseEmitter> emitters =
            new CopyOnWriteArrayList<>();

    // In-memory recent reports. A database is needed for permanent storage.
    private final List<Map<String, Object>> recentReports =
            new CopyOnWriteArrayList<>();

    private static final int MAX_REPORTS = 500;

    public LocationServiceImpl() {
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void processLocation(String clientIp) {

        Map<String, Object> event = new LinkedHashMap<>();

        event.put("capturedAt", Instant.now().toString());
        event.put("ipAddress", clientIp);
        event.put("ipCity", null);
        event.put("ipRegion", null);
        event.put("ipCountry", null);
        event.put("ipLatitude", null);
        event.put("ipLongitude", null);
        event.put("ipLookupMessage", "IP location unavailable");

        // Normalized result: city, region, country, latitude, longitude.
        Map<String, Object> ipData = lookupIpLocation(clientIp);

        if (ipData != null) {
            event.put("ipCity", ipData.get("city"));
            event.put("ipRegion", ipData.get("region"));
            event.put("ipCountry", ipData.get("country"));
            event.put("ipLatitude", toDouble(ipData.get("latitude")));
            event.put("ipLongitude", toDouble(ipData.get("longitude")));
            event.put("ipLookupMessage", "IP lookup successful");
        }

        // Save recent report before broadcasting it.
        recentReports.add(new LinkedHashMap<>(event));

        while (recentReports.size() > MAX_REPORTS) {
            recentReports.remove(0);
        }

        broadcast(event);

        System.out.println("Attendance captured -> " + event);
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

    // ==================== CHANGE START ====================
    // Tries three free providers in order and normalizes to the same keys:
    // city, region, country, latitude, longitude.
    //   1. ipwho.is     (HTTPS, no key)
    //   2. ip-api.com   (HTTP,  no key)
    //   3. ipapi.co     (HTTPS, no key)
    // Every call sends a browser-like User-Agent (ipapi.co rejects the
    // default Java UA) and logs its outcome so failures are visible in logs.
    private Map<String, Object> lookupIpLocation(String ip) {
        if (!isPublicIp(ip)) {
            System.out.println("IP lookup skipped (not a public IP): " + ip);
            return null;
        }

        Map<String, Object> result = lookupFromIpWhoIs(ip);
        if (result != null) {
            return result;
        }

        result = lookupFromIpApiCom(ip);
        if (result != null) {
            return result;
        }

        return lookupFromIpApiCo(ip);
    }

    // Shared GET that sends a User-Agent + Accept header.
    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url, Object... uriVars) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (attendance-app)");
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class, uriVars);

        return response.getBody();
    }

    // Primary: ipwho.is
    private Map<String, Object> lookupFromIpWhoIs(String ip) {
        try {
            Map<String, Object> data =
                    getJson("https://ipwho.is/{ip}", ip);

            if (data == null || !Boolean.TRUE.equals(data.get("success"))) {
                System.out.println("ipwho.is failed for " + ip + " -> " + data);
                return null;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("city", data.get("city"));
            normalized.put("region", data.get("region"));
            normalized.put("country", data.get("country"));
            normalized.put("latitude", data.get("latitude"));
            normalized.put("longitude", data.get("longitude"));
            System.out.println("ipwho.is success for " + ip);
            return normalized;
        } catch (RestClientException e) {
            System.out.println("ipwho.is error for " + ip + " -> " + e.getMessage());
            return null;
        }
    }

    // Fallback 1: ip-api.com (HTTP only on the free plan)
    private Map<String, Object> lookupFromIpApiCom(String ip) {
        try {
            Map<String, Object> data = getJson(
                    "http://ip-api.com/json/{ip}"
                            + "?fields=status,message,country,regionName,city,lat,lon",
                    ip);

            if (data == null || !"success".equals(data.get("status"))) {
                System.out.println("ip-api.com failed for " + ip + " -> " + data);
                return null;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("city", data.get("city"));
            normalized.put("region", data.get("regionName"));
            normalized.put("country", data.get("country"));
            normalized.put("latitude", data.get("lat"));
            normalized.put("longitude", data.get("lon"));
            System.out.println("ip-api.com success for " + ip);
            return normalized;
        } catch (RestClientException e) {
            System.out.println("ip-api.com error for " + ip + " -> " + e.getMessage());
            return null;
        }
    }

    // Fallback 2: ipapi.co
    private Map<String, Object> lookupFromIpApiCo(String ip) {
        try {
            Map<String, Object> data =
                    getJson("https://ipapi.co/{ip}/json/", ip);

            if (data == null || Boolean.TRUE.equals(data.get("error"))) {
                System.out.println("ipapi.co failed for " + ip + " -> " + data);
                return null;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("city", data.get("city"));
            normalized.put("region", data.get("region"));
            normalized.put("country", data.get("country_name"));
            normalized.put("latitude", data.get("latitude"));
            normalized.put("longitude", data.get("longitude"));
            System.out.println("ipapi.co success for " + ip);
            return normalized;
        } catch (RestClientException e) {
            System.out.println("ipapi.co error for " + ip + " -> " + e.getMessage());
            return null;
        }
    }
    // ==================== CHANGE END ====================

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