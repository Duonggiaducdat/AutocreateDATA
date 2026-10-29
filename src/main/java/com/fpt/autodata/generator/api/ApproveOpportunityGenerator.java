package com.fpt.autodata.generator.api;

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
 * Runs the "Phê duyệt" (BGĐ approval) step for an already-evaluated
 * Opportunity. Per BR03 this is only reachable/meaningful when
 * {@code expectedRevenue >= 300,000,000} — the caller (frontend checkbox
 * logic) decides whether to invoke this generator at all; it does not
 * re-check the threshold itself.
 *
 * <p>Endpoint found by guessing against the naming convention of the sibling
 * evaluate endpoint — see {@code memory/api-contracts.md} "Approve Opportunity"
 * for how {@code PUT evaluation-opportunities/bgd-approve} was confirmed.</p>
 */
@Component
public class ApproveOpportunityGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutodataProperties properties;

    public ApproveOpportunityGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "evaluation.approve"; }
    @Override public String displayName() { return "Phê duyệt cơ hội (BGĐ)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "PUT evaluation-opportunities/bgd-approve — chỉ áp dụng khi doanh thu kỳ vọng >= 300,000,000 (BR03) và cơ hội đã được đánh giá.";
    }
    @Override public String signature() {
        return "PUT {api.baseUrl}/evaluation-opportunities/bgd-approve";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token").required(),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085280"),
                ParamSpec.text("reason").label("Ý kiến phê duyệt")
                        .defaultValue("Phê duyệt tự động qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String reason = Params.optString(p, "reason", "Phê duyệt tự động qua tool");

        // Real payload shape reverse-engineered from ApproveNewOppModal.*.js (2026-08-25) —
        // see memory/api-contracts.md. The component calls its RTK Query mutation hook with
        // { bgdApproveEvaluationRequest: {...} }, but (same pattern as the stage-update mutation
        // found earlier) that outer key is just the JS call argument's name — the RTK query
        // function unwraps it and sends ONLY the inner object as the actual HTTP body. Confirmed
        // live: POSTing the outer-wrapped shape as the literal wire body 400s with the exact same
        // "REASON"/"OPPORTUNITYID missing" errors as an empty body (server never saw those fields).
        // CRITICAL: bgdApprovalStatus is NOT optional in practice — omitting it (the original flat
        // {opportunityId, reason} shape this generator first used) is silently accepted by the API
        // but defaults to REJECT (4), not approve. Confirmed live: a call without this field showed
        // up in the opportunity's own activity feed as "Đã từ chối đánh giá cơ hội" (rejected), not
        // "Đã phê duyệt" (approved).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opportunityId", opportunityId);
        payload.put("bgdApprovalStatus", 3); // 3 = Approve, 4 = Reject
        payload.put("reason", reason);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        String url = baseUrl + "/evaluation-opportunities/bgd-approve";
        trace(log, "PUT " + url);

        String body = toJson(payload);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }

        trace(log, "-> HTTP " + response.statusCode());
        JsonNode responseBody = parseQuietly(response.body());

        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
        }
        if (responseBody != null && responseBody.path("success").isBoolean() && !responseBody.path("success").asBoolean()) {
            trace(log, "response: " + response.body());
            throw new GeneratorException("BMS API returned success=false: " + response.body());
        }

        return result()
                .put("opportunityId", opportunityId)
                .put("responseBody", responseBody != null ? responseBody : response.body())
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
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
