package com.secure.hms.controller;

import com.secure.hms.service.LocationService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    // Attendance link: capture IP location, show it on the admin dashboard,
    // and return a simple success message.
    @GetMapping(value = "/location", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> captureLocation(HttpServletRequest request) {
        locationService.processLocation(getClientIp(request));
        return ResponseEntity.ok("Attendance captured successfully");
    }

    // Real-time event stream consumed by the admin dashboard.
    @GetMapping(
            value = "/api/location/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamLocations() {
        return locationService.subscribe();
    }

    // Admin page.
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
    <title>Admin - Live Attendance Dashboard</title>
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
            min-width: 700px;
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
    <h2>Live Attendance Dashboard</h2>

    <div class="status">
        <span class="dot" id="statusDot"></span>
        <span id="connectionStatus">Connecting...</span>
        | Reports: <strong id="count">0</strong>
    </div>

    <div class="panel">
        <table>
            <thead>
                <tr>
                    <th>Reported At</th>
                    <th>IP Address</th>
                    <th>City / Region</th>
                    <th>Country</th>
                    <th>Map</th>
                </tr>
            </thead>
            <tbody id="reports">
                <tr id="placeholder">
                    <td colspan="5" class="muted">
                        Waiting for attendance reports...
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

        let total = 0;

        function addCell(row, value) {
            const td = document.createElement("td");
            td.textContent =
                value === null || value === undefined || value === ""
                    ? "\\u2014" : String(value);
            row.appendChild(td);
        }

        function renderReport(data) {
            const placeholder = document.getElementById("placeholder");
            if (placeholder) placeholder.remove();

            const row = document.createElement("tr");

            addCell(row, data.capturedAt);
            addCell(row, data.ipAddress);
            addCell(
                row,
                [data.ipCity, data.ipRegion].filter(Boolean).join(", ")
            );
            addCell(row, data.ipCountry);

            const mapCell = document.createElement("td");
            const latitude = data.ipLatitude;
            const longitude = data.ipLongitude;

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
            count.textContent = ++total;
        }

        const stream = new EventSource("/api/location/stream");

        stream.addEventListener("open", () => {
            statusText.textContent = "Connected \\u2014 live updates enabled";
            statusDot.style.background = "#16a34a";
        });

        stream.addEventListener("location", event => {
            try {
                renderReport(JSON.parse(event.data));
            } catch (error) {
                console.error("Invalid attendance report", error);
            }
        });

        stream.addEventListener("error", () => {
            statusText.textContent =
                "Disconnected \\u2014 attempting to reconnect";
            statusDot.style.background = "#dc2626";
        });
    </script>
</body>
</html>
""";
}