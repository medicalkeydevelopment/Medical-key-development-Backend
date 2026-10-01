package com.secure.hms.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LocationServiceImpl implements LocationService {
	
	
	private final List<SseEmitter> admins = new CopyOnWriteArrayList<>();
	 
    @Override
    public String processLocation(Map<String, Object> request, String clientIp) {
        Map<String, Object> location = extractLocation(request, clientIp);
        broadcast(location);   // render live in the admin page
        System.out.println("Location processed -> " + location);
        return "Location received successfully";
    }
 
    @Override
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no server-side timeout
        admins.add(emitter);
 
        emitter.onCompletion(() -> admins.remove(emitter));
        emitter.onTimeout(() -> admins.remove(emitter));
        emitter.onError(e -> admins.remove(emitter));
 
        System.out.println("Admin subscribed. Active admins: " + admins.size());
 
        send(emitter, "connected", "ok");  // handshake so the browser knows it's live
        return emitter;
    }
 
    // Builds the full location from the browser GPS (if allowed) + the network/IP lookup.
    private Map<String, Object> extractLocation(Map<String, Object> request, String clientIp) {
        Map<String, Object> location = new LinkedHashMap<>();
 
        // 1. Browser GPS (only present if the student allowed it)
        boolean gpsAvailable = false;
        if (request != null) {
            location.put("userId", request.get("userId"));
            Double lat = toDouble(request.get("latitude"));
            Double lng = toDouble(request.get("longitude"));
            if (lat != null && lng != null) {
                gpsAvailable = true;
                location.put("gpsLatitude", lat);
                location.put("gpsLongitude", lng);
                location.put("gpsAccuracy", toDouble(request.get("accuracy")));
            }
        }
        location.put("gpsAvailable", gpsAvailable);
 
        // 2. Network / IP (always runs, no permission needed)
        location.put("ipAddress", clientIp);
        try {
            String url = "http://ip-api.com/json/" + clientIp;
            @SuppressWarnings("unchecked")
            Map<String, Object> ipData = new RestTemplate().getForObject(url, Map.class);
            if (ipData != null && "success".equals(ipData.get("status"))) {
                location.put("ipCity", ipData.get("city"));
                location.put("ipRegion", ipData.get("regionName"));
                location.put("ipCountry", ipData.get("country"));
                location.put("ipLatitude", toDouble(ipData.get("lat")));
                location.put("ipLongitude", toDouble(ipData.get("lon")));
            }
        } catch (Exception e) {
            System.out.println("IP lookup failed: " + e.getMessage());
        }
 
        // 3. Best map link: prefer precise GPS, else fall back to IP
        if (gpsAvailable) {
            location.put("mapsLink", mapsLink((Double) location.get("gpsLatitude"),
                    (Double) location.get("gpsLongitude")));
        } else if (location.get("ipLatitude") != null) {
            location.put("mapsLink", mapsLink((Double) location.get("ipLatitude"),
                    (Double) location.get("ipLongitude")));
        }
        return location;
    }
 
    // Push the location to every connected admin, dropping any dead connections.
    private void broadcast(Map<String, Object> location) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : admins) {
            if (!send(emitter, "location", location)) {
                dead.add(emitter);
            }
        }
        admins.removeAll(dead);
        System.out.println("Broadcast location to " + admins.size() + " admin(s)");
    }
 
    private boolean send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (Exception e) {
            return false; // emitter closed/broken -> caller drops it
        }
    }
 
    private String mapsLink(Double lat, Double lng) {
        return "https://www.google.com/maps?q=" + lat + "," + lng;
    }
 
    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        return Double.parseDouble(value.toString());
    }

}
