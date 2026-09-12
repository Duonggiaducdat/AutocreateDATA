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
 * Given a "Mã YC" (registration refCode, e.g. {@code CMR2614586}), finds
 * everything {@code registration.approve-bms} needs WITHOUT the caller
 * having to already know the opportunity code or who the assigned BGĐ is:
 * {@code opportunityId}, {@code registrationId}, and {@code usersCanApproval}
 * (the real BGĐ username to auto-login as).
 *
 * <p>Uses {@code approval-opportunities/registration-opportunity}'s
 * {@code keyword} search — confirmed live 2026-08-27 that it matches a
 * regCode directly (not just an opportunity code), and the row it returns
 * already carries {@code usersCanApproval} + {@code registrationId} —
 * exactly the two things a caller would otherwise have to cross-reference
 * from a separate {@code GET opportunities/{id}} call.</p>
 *
 * <p>Any authenticated BMS account can run this lookup regardless of
 * whether they're the assigned approver — it's a read/search endpoint, not
 * the actual approve mutation (that's still gated to whoever
 * {@code registration.approve-bms} logs in as). Searches both the
 * "chờ duyệt" (2) and "đã duyệt" (1) buckets since the caller doesn't know
 * the current state up front.</p>
 */
@Component
public class LookupRegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int APPROVED = 1;
    private static final int PENDING = 2;

    private final AutodataProperties properties;

    public LookupRegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "registration.lookup"; }
    @Override public String displayName() { return "Tra cứu Mã YC (regCode)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST approval-opportunities/registration-opportunity?keyword=<regCode> — tìm opportunityId/registrationId/BGĐ phụ trách từ Mã YC.";
    }
    @Override public String signature() {
        return "POST {api.baseUrl}/approval-opportunities/registration-opportunity";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token (bất kỳ account BMS)").required(),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("regCode").label("Mã YC").required()
                        .placeholder("CMR2614586")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String regCode = Params.requireString(p, "regCode");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        JsonNode row = search(client, baseUrl, bearerToken, regCode, APPROVED, log);
        if (row == null) row = search(client, baseUrl, bearerToken, regCode, PENDING, log);
        if (row == null) {
            throw new GeneratorException("Không tìm thấy registration nào với Mã YC=" + regCode + " (đã tìm cả 2 trạng thái đã duyệt/chờ duyệt)");
        }

        String opportunityId = row.path("opportunityId").asText(null);
        int registrationId = row.path("registrationId").asInt(0);
        String bgdUsername = row.path("usersCanApproval").asText(null);
        int currentStatus = row.path("bgdApprovalStatus").asInt(PENDING);

        return result()
                .put("regCode", regCode)
                .put("opportunityId", opportunityId)
                .put("registrationId", registrationId)
                .put("bgdUsername", bgdUsername)
                .put("alreadyApproved", currentStatus == APPROVED)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    // "regCode" param doubles as a generic keyword — the search endpoint matches
    // opportunityId as well as refCode, and a JUST-created registration has no
    // refCode yet (only assigned once BMS approves it). Prefer an exact refCode
    // match; fall back to an exact opportunityId match; fall back to the sole
    // row when the keyword search is unambiguous (single hit).
    private JsonNode search(HttpClient client, String baseUrl, String bearerToken, String regCode, int status, List<String> log) {
        String url = baseUrl + "/approval-opportunities/registration-opportunity";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyword", regCode);
        payload.put("approvalStatus", status);
        payload.put("pageIndex", 1);
        payload.put("pageSize", 5);
        trace(log, "POST " + url + " keyword=" + regCode + " approvalStatus=" + status);
        JsonNode response = post(client, url, bearerToken, toJson(payload), log);
        JsonNode rows = response != null ? response.path("data").path("data") : null;
        if (rows == null || !rows.isArray() || rows.isEmpty()) return null;
        for (JsonNode row : rows) {
            if (regCode.equalsIgnoreCase(row.path("refCode").asText(""))) return row;
        }
        for (JsonNode row : rows) {
            if (regCode.equalsIgnoreCase(row.path("opportunityId").asText(""))) return row;
        }
        return rows.size() == 1 ? rows.get(0) : null;
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
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        return parsed;
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
