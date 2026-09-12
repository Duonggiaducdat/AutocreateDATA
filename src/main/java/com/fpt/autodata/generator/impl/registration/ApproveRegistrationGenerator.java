package com.fpt.autodata.generator.impl.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 1 of "Phê duyệt Hợp đồng/Phụ lục (HĐ/PL)" — the BMS-side approval of
 * a service's "registration" (đăng ký triển khai), done by the opportunity's
 * own BGĐ approver (same account as {@code usersCanApproval}).
 *
 * <p>Looks up the opportunity's service registrations (each service has its
 * own {@code registrationId}) and approves every one that isn't already
 * approved. See {@code memory/api-contracts.md} "Approve Hợp đồng/Phụ lục"
 * for how {@code approvalStatus: 1 = Approved} was confirmed (2 = chờ duyệt).
 * This is only step 1 — step 2 (CM) is a separate generator
 * ({@code registration.approve-cm}) because it needs a completely different
 * token from a different app.</p>
 */
@Component
public class ApproveRegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int APPROVED = 1;
    private static final int PENDING = 2;

    private final AutodataProperties properties;

    public ApproveRegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "registration.approve-bms"; }
    @Override public String displayName() { return "Duyệt HĐ/PL — bước 1 (BMS, role BGĐ)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST approval-opportunities/approve-registration cho từng service registration của cơ hội — bước 1/2 trước khi duyệt tiếp bên CM.";
    }
    @Override public String signature() {
        return "POST {api.baseUrl}/approval-opportunities/approve-registration";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token (BGĐ)").required(),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00084099"),
                ParamSpec.text("reason").label("Ý kiến phê duyệt")
                        .defaultValue("Phe duyet tu dong qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String reason = Params.optString(p, "reason", "Phe duyet tu dong qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        JsonNode opportunity = get(client, baseUrl + "/opportunities/" + opportunityId, bearerToken, log);
        JsonNode services = opportunity.path("data").path("services");
        if (!services.isArray() || services.isEmpty()) {
            throw new GeneratorException("Opportunity " + opportunityId + " has no services/registrations to approve");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> regCodes = new ArrayList<>();
        for (JsonNode svc : services) {
            int registrationId = svc.path("registrationId").asInt(0);
            if (registrationId == 0) continue; // service without a registration yet — nothing to approve
            int currentStatus = svc.path("bgdApprovalStatus").isMissingNode() || svc.path("bgdApprovalStatus").isNull()
                    ? PENDING : svc.path("bgdApprovalStatus").asInt(PENDING);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("registrationId", registrationId);
            if (currentStatus == APPROVED) {
                trace(log, "registrationId " + registrationId + " already approved, skipping");
                row.put("skipped", true);
                row.put("status", "already approved");
            } else {
                approveOne(client, baseUrl, bearerToken, registrationId, reason, log);
                row.put("skipped", false);
                row.put("status", "approved");
            }
            results.add(row);
        }

        if (results.isEmpty()) {
            throw new GeneratorException("Opportunity " + opportunityId + " has services but none carry a registrationId yet");
        }

        // Look up the CM-side refCode (regCode, e.g. "CMR2614586") for each
        // just-touched registration so the frontend can chain straight into
        // registration.approve-cm without a second round-trip.
        for (Map<String, Object> row : results) {
            String regCode = lookupRegCode(client, baseUrl, bearerToken, opportunityId, (Integer) row.get("registrationId"), log);
            row.put("regCode", regCode);
            if (regCode != null) regCodes.add(regCode);
        }

        return result()
                .put("opportunityId", opportunityId)
                .put("registrations", results)
                .put("regCodes", regCodes)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private void approveOne(HttpClient client, String baseUrl, String bearerToken, int registrationId, String reason, List<String> log) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registrationId", registrationId);
        payload.put("approvalStatus", APPROVED);
        payload.put("reason", reason);

        String url = baseUrl + "/approval-opportunities/approve-registration";
        trace(log, "POST " + url + " registrationId=" + registrationId);
        JsonNode response = post(client, url, bearerToken, toJson(payload), log);
        if (response != null && response.path("success").isBoolean() && !response.path("success").asBoolean()) {
            throw new GeneratorException("BMS API returned success=false approving registrationId " + registrationId + ": " + response);
        }
    }

    // Keyword search only matches opportunityId/customer/sale fields — NOT the
    // bare numeric registrationId (confirmed empirically 2026-08-27: searching
    // by registrationId alone returned zero rows even for a just-approved
    // record). Search by opportunityId and disambiguate by registrationId in
    // case the opportunity has more than one service/registration.
    private String lookupRegCode(HttpClient client, String baseUrl, String bearerToken, String opportunityId, int registrationId, List<String> log) {
        try {
            String url = baseUrl + "/approval-opportunities/registration-opportunity";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("keyword", opportunityId);
            payload.put("approvalStatus", APPROVED);
            payload.put("pageIndex", 1);
            payload.put("pageSize", 20);
            JsonNode response = post(client, url, bearerToken, toJson(payload), log);
            JsonNode rows = response != null ? response.path("data").path("data") : null;
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    if (row.path("registrationId").asInt(-1) == registrationId) {
                        String ref = row.path("refCode").asText(null);
                        if (ref != null) return ref;
                    }
                }
            }
        } catch (RuntimeException e) {
            trace(log, "regCode lookup failed for registrationId " + registrationId + ": " + e.getMessage());
        }
        return null;
    }

    private JsonNode get(HttpClient client, String url, String bearerToken, List<String> log) {
        trace(log, "GET " + url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return send(client, request, log);
    }

    private JsonNode post(HttpClient client, String url, String bearerToken, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(client, request, log);
    }

    private JsonNode send(HttpClient client, HttpRequest request, List<String> log) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + request.uri(), HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode body = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
        }
        return body;
    }

    private String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new GeneratorException("Failed to serialize payload", e);
        }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private GenerationResult.Builder result() {
        return GenerationResult.builder(type());
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
