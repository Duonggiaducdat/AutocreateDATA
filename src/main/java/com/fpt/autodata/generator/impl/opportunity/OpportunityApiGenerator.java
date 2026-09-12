package com.fpt.autodata.generator.impl.opportunity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.config.AutodataProperties;
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
 * Creates an Opportunity via one direct REST call to the BMS backend
 * ({@code POST /opportunities}), instead of driving the 3-step wizard through
 * Selenium ({@link OpportunityStep1Generator}, {@code Step2}, {@code Step3}).
 *
 * <p>The Selenium path opens a browser, clicks through 3 wizard pages, and is
 * the source of most entries in {@code backend/screenshots/} (timing/element
 * waits are inherently flaky). This generator builds the exact JSON payload
 * the wizard itself submits on step 3 and posts it once — no browser, no
 * multi-page navigation, so it fails far less often. See
 * {@code memory/api-contracts.md} for the payload reference this was built
 * from (captured live from STG + cross-checked against the real UI).</p>
 *
 * <p>Requires a bearer token obtained by logging into BMS (paste it into the
 * {@code bearerToken} param — this generator does not perform login itself).</p>
 */
@Component
public class OpportunityApiGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cao=1/Trung bình=2/Thấp=3 — confirmed 2026-08-25 from the real frontend's priority.js chunk. */
    private static final Map<String, Integer> PRIORITY_CODES = Map.of(
            "Cao", 1,
            "Trung bình", 2,
            "Thấp", 3
    );

    private final AutodataProperties properties;

    public OpportunityApiGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "opportunity.api"; }
    @Override public String displayName() { return "Create Opportunity · API (fast)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "One direct REST call to BMS — same result as Step 1-3, no Selenium, far less flaky.";
    }
    @Override public String signature() {
        return "POST {api.baseUrl}/opportunities";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token").required()
                        .hint("paste token from a logged-in BMS session (DevTools > Application > local storage, or Network tab)"),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),

                ParamSpec.text("customerId").label("Customer ID").required()
                        .placeholder("FTI260088"),

                ParamSpec.text("name").label("Opportunity name").required()
                        .defaultValue("Dat test").placeholder("<=200 characters"),
                ParamSpec.text("description").label("Description").required()
                        .defaultValue("Auto-created via API generator"),

                // NUMBER renders as a 1-10 range slider in the UI (see ui.js renderField) —
                // fine for successRate, useless for currency/id-sized values, so those stay TEXT
                // and get parsed with Params.optInt (handles numeric strings fine).
                ParamSpec.text("expectedRevenue").label("Expected revenue (VND)").required()
                        .defaultValue("100000000"),
                ParamSpec.text("expectedRevenuePeriod").label("Revenue period")
                        .defaultValue("1").hint("UNCONFIRMED enum — see memory/api-contracts.md"),
                ParamSpec.text("expectedBudget").label("Expected budget (VND)")
                        .defaultValue("50000000"),
                ParamSpec.number("successRate").label("Success rate (%)").required()
                        .range(0, 100).defaultValue(50),
                ParamSpec.text("endDate").label("End date").required()
                        .placeholder("yyyy-MM-dd").defaultValue(LocalDateTime.now().plusMonths(3).toLocalDate().toString()),
                ParamSpec.enumOf("priority", List.of("Cao", "Trung bình", "Thấp"))
                        .label("Priority").defaultValue("Trung bình"),

                ParamSpec.text("services").label("Services (group:type[:packCode], phân cách dấu phẩy)").required()
                        .defaultValue("7000:7001")
                        .hint("vd 2 dịch vụ: 7000:7001,7000:7002 — mã raw, xem memory/api-contracts.md, chưa có label map. UI thật cho 1-10 dịch vụ/opportunity."),

                ParamSpec.text("saleName").label("AM (sale phụ trách)").required()
                        .defaultValue("ThiTLH"),
                ParamSpec.text("tppCanApproval").label("TPP đánh giá (Trưởng/Phó phòng)").required()
                        .defaultValue("AnhDTT12").hint("comma-separated for multiple"),
                ParamSpec.text("usersCanApproval").label("BGĐ phê duyệt").required()
                        .defaultValue("Huyennhk").hint("comma-separated for multiple"),
                ParamSpec.text("userReceiveNotification").label("Gửi thông báo (optional)")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl());
        String customerId = Params.requireString(p, "customerId");

        String name = Params.requireString(p, "name");
        String description = Params.requireString(p, "description");
        // VND amounts can exceed Integer.MAX_VALUE (URD boundary tests go up to 13 digits) — Params has
        // no optLong, and Params.optInt would silently truncate, so parse these two as long directly.
        long expectedRevenue = optLong(p, "expectedRevenue", 0L);
        int expectedRevenuePeriod = Params.optInt(p, "expectedRevenuePeriod", 1);
        Long expectedBudget = p.get("expectedBudget") == null ? null : optLong(p, "expectedBudget", 0L);
        int successRate = Params.optInt(p, "successRate", 0);
        String endDate = Params.requireString(p, "endDate");
        String priorityLabel = Params.optString(p, "priority", "Trung bình");
        Integer priority = PRIORITY_CODES.get(priorityLabel);
        if (priority == null) {
            throw new GeneratorException("Unknown priority: " + priorityLabel);
        }

        List<Map<String, Object>> services = parseServices(Params.requireString(p, "services"));

        String saleName = Params.requireString(p, "saleName");
        List<String> tpp = Params.optList(p, "tppCanApproval", List.of());
        List<String> bgd = Params.optList(p, "usersCanApproval", List.of());
        String notifyUser = Params.optString(p, "userReceiveNotification", null);

        Map<String, Object> opportunity = new LinkedHashMap<>();
        opportunity.put("name", name);
        opportunity.put("description", description);
        opportunity.put("expectedRevenue", expectedRevenue);
        opportunity.put("expectedRevenuePeriod", expectedRevenuePeriod);
        opportunity.put("expectedBudget", expectedBudget);
        opportunity.put("successRate", successRate);
        opportunity.put("endDate", endDate);
        opportunity.put("priority", priority);
        opportunity.put("numberRack", null);
        opportunity.put("services", services);
        opportunity.put("documents", null);

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("saleName", saleName);
        approval.put("tppCanApproval", tpp);
        approval.put("usersCanApproval", bgd);
        approval.put("userReceiveNotification", notifyUser);
        approval.put("campaignId", null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("leadId", null);
        payload.put("leadDescription", null);
        payload.put("opportunity", opportunity);
        payload.put("approval", approval);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        String url = baseUrl.replaceAll("/$", "") + "/opportunities";
        trace(log, "POST " + url);

        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new GeneratorException("Failed to serialize payload", e);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();
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
            throw new GeneratorException(
                    "Could not reach " + url + " — check network/VPN, not necessarily a BMS validation error",
                    HttpStatus.BAD_GATEWAY, e);
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

        // Real success shape confirmed 2026-08-25: {"data":"CH00085262","success":true,...} —
        // `data` is the created opportunity's code, a plain string. Surface it (and a direct
        // link to it) as the headline result fields instead of making the caller dig through
        // responseBody every time.
        String opportunityCode = responseBody != null && responseBody.path("data").isTextual()
                ? responseBody.path("data").asText()
                : null;
        String detailUrl = opportunityCode != null
                ? properties.getWebdriver().getBaseUrl().replaceAll("/$", "") + "/bmsv2/opportunity/detail/" + opportunityCode
                : null;

        GenerationResult.Builder result = result()
                .put("opportunityCode", opportunityCode)
                .put("link", detailUrl)
                .put("opportunityName", name)
                .put("customerId", customerId)
                .put("httpStatus", response.statusCode())
                .put("responseBody", responseBody != null ? responseBody : response.body())
                .log(log)
                .durationMs(System.currentTimeMillis() - t0);

        return result.build();
    }

    /**
     * Parses {@code "7000:7001,7000:7002,7100:7101:PACK1"} into the API's
     * {@code services[]} shape. Each entry is {@code group:type[:packCode]}.
     * See {@code memory/api-contracts.md} — no confirmed label→code map yet,
     * hence raw codes instead of a proper cascading group/type selector.
     */
    private static List<Map<String, Object>> parseServices(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length < 2) {
                throw new GeneratorException(
                        "Invalid service entry '" + trimmed + "' — expected group:type[:packCode]");
            }
            Map<String, Object> service = new LinkedHashMap<>();
            try {
                service.put("serviceGroup", Integer.parseInt(parts[0].trim()));
                service.put("serviceType", Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                throw new GeneratorException(
                        "Invalid numeric code in service entry '" + trimmed + "'");
            }
            service.put("servicePackCode", parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null);
            result.add(service);
        }
        if (result.isEmpty()) {
            throw new GeneratorException("At least 1 service is required");
        }
        return result;
    }

    private static long optLong(Map<String, Object> p, String key, long fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new GeneratorException("Invalid number for param: " + key);
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
