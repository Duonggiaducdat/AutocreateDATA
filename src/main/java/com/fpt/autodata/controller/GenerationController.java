package com.fpt.autodata.controller;

import com.fpt.autodata.audit.AuditEntry;
import com.fpt.autodata.audit.AuditService;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.model.GenerateRequest;
import com.fpt.autodata.model.GenerateResponse;
import com.fpt.autodata.model.GeneratorMetadata;
import com.fpt.autodata.service.GenerationService;
import com.fpt.autodata.service.GeneratorRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import models.ServiceGroup;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * REST surface for the data generation tool.
 *
 * <ul>
 *   <li>{@code GET  /api/health}      — liveness</li>
 *   <li>{@code GET  /api/generators}  — full catalog (type, name, params)</li>
 *   <li>{@code GET  /api/services}    — service catalog from Services.json</li>
 *   <li>{@code POST /api/generate}    — execute one generator</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generationService;
    private final GeneratorRegistry registry;
    private final AuditService auditService;
    private final AutodataProperties properties;

    public GenerationController(GenerationService generationService,
                                GeneratorRegistry registry,
                                AuditService auditService,
                                AutodataProperties properties) {
        this.generationService = generationService;
        this.registry = registry;
        this.auditService = auditService;
        this.properties = properties;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "status", "UP",
                "generators", registry.all().size()));
    }

    @GetMapping("/generators")
    public ResponseEntity<List<GeneratorMetadata>> catalog() {
        return ResponseEntity.ok(registry.catalog());
    }

    /**
     * Returns the {@code Services.json} content. Frontend uses this to:
     * <ul>
     *   <li>populate the service-group dropdown</li>
     *   <li>populate the service-type chips per chosen group</li>
     *   <li>decide whether to show the rack-option field</li>
     * </ul>
     */
    @GetMapping("/services")
    public ResponseEntity<ServiceGroup> services() {
        return ResponseEntity.ok(JsonUtil.loadJsonData("Services.json", ServiceGroup.class));
    }

    /**
     * Generic proxy for any {@code GET} endpoint on the real BMS API — service
     * groups/types ({@code master-data/service-types/groups}), TPP/BGĐ approvers
     * ({@code bam/employees/{email}/department-managers} /
     * {@code .../central-managers}), etc. One endpoint instead of one per
     * lookup: forward path + query string, attach the caller's bearer token,
     * pass the response straight through. Frontend calls e.g.
     * {@code /api/proxy/master-data/service-types/groups} or
     * {@code /api/proxy/bam/employees/ThiTLH%40fpt.com/department-managers}.
     *
     * <p>Proxied server-side (not called directly from the browser) for two
     * reasons: (1) the BMS API's CORS policy only allows its own frontend
     * origin, a direct browser call from this app's origin would be blocked;
     * (2) keeps the bearer token off the wire to any origin other than our own
     * backend and the real BMS API it forwards to.</p>
     *
     * <p>Response shape is passed through as-is (raw JSON string) — shapes
     * vary per endpoint (see {@code memory/api-contracts.md}), so the frontend
     * normalizes defensively rather than this endpoint assuming one.</p>
     */
    @GetMapping("/proxy/**")
    public ResponseEntity<String> apiProxy(
            HttpServletRequest httpReq,
            @RequestHeader("Authorization") String authorization) {
        String prefix = httpReq.getContextPath() + "/api/proxy/";
        String uri = httpReq.getRequestURI();
        String rest = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        String query = httpReq.getQueryString();
        String url = properties.getApi().getBaseUrl().replaceAll("/$", "") + "/" + rest
                + (query != null ? "?" + query : "");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Could not reach " + url + ": " + e.getMessage() + "\"}");
        }
        HttpStatus status = HttpStatus.resolve(response.statusCode());
        return ResponseEntity
                .status(status != null ? status : HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerateRequest request,
                                                     HttpServletRequest httpReq) {
        String ip = resolveIp(httpReq);
        long t0 = System.currentTimeMillis();
        boolean success = false;
        String summary = "";
        try {
            GenerationResult result = generationService.execute(request);
            success = true;
            summary = extractSummary(result);
            return ResponseEntity.ok(GenerateResponse.of(result));
        } catch (RuntimeException e) {
            summary = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw e;
        } finally {
            auditService.record(ip, request.type(), request.params(),
                    success, System.currentTimeMillis() - t0, summary);
        }
    }

    @GetMapping("/generate")
    public ResponseEntity<?> info() {
        return ResponseEntity.ok("Use POST /api/generate");
    }

    @GetMapping("/log")
    public ResponseEntity<List<AuditEntry>> log(
            @RequestParam(defaultValue = "50") int n) {
        return ResponseEntity.ok(auditService.recent(Math.min(n, 200)));
    }

    private String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }

    // Mirrors AuditService's own sensitive-key check — a generator's result data
    // (e.g. auth.get-token's "token" field) is just as sensitive as its input
    // params, and this summary lands in the same audit.jsonl / Activity Log UI.
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "passsale", "password", "pass", "secret", "token", "bearertoken", "bearer", "otp", "cookie"
    );

    private String extractSummary(GenerationResult result) {
        Object maCH = result.getData().get("maCH");
        if (maCH != null) return "maCH=" + maCH;
        for (var entry : result.getData().entrySet()) {
            String key = entry.getKey().toLowerCase();
            boolean sensitive = SENSITIVE_KEYS.stream().anyMatch(key::contains);
            if (!sensitive) return entry.toString();
        }
        return "ok";
    }
}
