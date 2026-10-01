package com.secure.hms.controller;

import com.secure.hms.service.LocationService;
import jakarta.servlet.http.HttpServletRequest; // Spring Boot 2 -> javax.servlet.http.HttpServletRequest
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    // ---- Student link ----  just OPENING this captures the network/IP location, then returns nothing ----
    // Optional: pass the student id -> /location?userId=STUDENT123  (so the admin sees who)
    @GetMapping("/location")
    public ResponseEntity<Void> captureLocation(@RequestParam(required = false) String userId,
                                                HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);

        Map<String, Object> body = new HashMap<>();
        if (userId != null) {
            body.put("userId", userId);
        }
        // No GPS coords -> service uses network/IP location only, then broadcasts to the admin.
        locationService.processLocation(body, clientIp);

        return ResponseEntity.noContent().build();   // 204, blank page
    }

    // ---- Admin dashboard subscribes here for the live feed ----
    @GetMapping("/api/location/stream")
    public SseEmitter stream() {
        return locationService.subscribe();
    }

    // ---- Admin page ----  https://your-app.onrender.com/location/admin
    @GetMapping(value = "/location/admin", produces = MediaType.TEXT_HTML_VALUE)
    public String adminPage() {
        return ADMIN_PAGE;
    }

    // Reads the real client IP even behind Render's / any reverse proxy.
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();   // first IP = original client
        }
        return request.getRemoteAddr();
    }

    private static final String ADMIN_PAGE = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>HMS - Admin Live Location</title>
        <style>
          body{font-family:system-ui,Arial,sans-serif;max-width:900px;margin:30px auto;padding:0 16px;}
          #status{font-size:13px;margin-bottom:16px;}
          .on{color:#16a34a;} .off{color:#dc2626;}
          table{width:100%;border-collapse:collapse;font-size:14px;}
          th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #e5e7eb;}
          th{background:#f9fafb;}
          .badge{padding:2px 8px;border-radius:999px;font-size:12px;font-weight:600;}
          .gps{background:#dcfce7;color:#166534;} .ip{background:#fef3c7;color:#92400e;}
          tr.fresh{animation:flash 1.2s ease-out;}
          @keyframes flash{from{background:#dbeafe;}to{background:transparent;}}
          a{color:#2563eb;}
        </style>
        </head>
        <body>
        <h1>Live Student Locations</h1>
        <div id="status" class="off">Connecting...</div>
        <table>
        <thead><tr><th>Time</th><th>Student</th><th>Source</th><th>Coordinates</th><th>Area</th><th>Map</th></tr></thead>
        <tbody id="rows"><tr id="empty"><td colspan="6" style="color:#6b7280">No requests yet...</td></tr></tbody>
        </table>
        <script>
          const statusEl=document.getElementById('status');
          const rows=document.getElementById('rows');
          const es=new EventSource('/api/location/stream');
          es.addEventListener('connected',()=>{ statusEl.textContent='Live - connected'; statusEl.className='on'; });
          es.addEventListener('location',e=>addRow(JSON.parse(e.data)));
          es.onerror=()=>{ statusEl.textContent='Disconnected - retrying...'; statusEl.className='off'; };
          function addRow(d){
            const empty=document.getElementById('empty'); if(empty) empty.remove();
            const gps=d.gpsAvailable;
            const lat=gps?d.gpsLatitude:d.ipLatitude;
            const lng=gps?d.gpsLongitude:d.ipLongitude;
            const area=gps?('~'+d.gpsAccuracy+' m'):[d.ipCity,d.ipRegion,d.ipCountry].filter(Boolean).join(', ');
            const tr=document.createElement('tr'); tr.className='fresh';
            tr.innerHTML='<td>'+new Date().toLocaleTimeString()+'</td>'
              +'<td>'+(d.userId||'-')+'</td>'
              +'<td><span class="badge '+(gps?'gps':'ip')+'">'+(gps?'GPS':'IP')+'</span></td>'
              +'<td>'+fmt(lat)+', '+fmt(lng)+'</td>'
              +'<td>'+(area||'-')+'</td>'
              +'<td>'+(d.mapsLink?'<a href="'+d.mapsLink+'" target="_blank">open</a>':'-')+'</td>';
            rows.prepend(tr);
          }
          function fmt(v){ return (v==null)?'-':Number(v).toFixed(5); }
        </script>
        </body>
        </html>
        """;
}