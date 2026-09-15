package com.fpt.autodata.generator.impl.service.ivoice.voice1800;

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
 * BGĐ TTKD duyệt Yêu cầu Hợp đồng (YCHD) cho dịch vụ Đầu số thoại - 1800.
 *
 * <p>Flow BMS v2:
 * <ol>
 *   <li>GET /opportunities/{opportunityId} — lấy danh sách services + registrationId</li>
 *   <li>POST /approval-opportunities/approve-registration — duyệt từng registration</li>
 *   <li>POST /approval-opportunities/registration-opportunity — lookup regCode (CMR...) cho CM</li>
 * </ol>
 * Mirrors {@link com.fpt.autodata.generator.impl.registration.ApproveRegistrationGenerator}
 * nhưng scoped riêng cho Voice 1800 và trả về regCode để chain sang voice1800.cm-execute.
 * </p>
 *
 * <p>Endpoint xác nhận từ existing ApproveRegistrationGenerator (confirmed live 2026-08-27).
 * Cần xác nhận lại live với YCHD 1800 — endpoint có thể khác nếu BGĐ TTKD có approval
 * route riêng.</p>
 */
@Component
public class Approve1800RegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int APPROVED = 1;
    private static final int PENDING   = 2;
    private static final int TIMEOUT_SECONDS = 20;

    private final AutodataProperties properties;

    public Approve1800RegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "voice1800.approve-registration"; }
    @Override public String displayName() { return "Duyệt YCHD 1800 — BGĐ TTKD (BMS v2)"; }
    @Override public String category()    { return "Voice 1800"; }
    @Override public String description() {
        return "POST /approval-opportunities/approve-registration cho registration 1800 — đưa lên trạng thái \"Chờ CM\". " +
               "Trả về regCode (CMR...) để dùng tiếp với voice1800.cm-execute.";
    }
    @Override public String signature() {
        return "GET /opportunities/{id}, POST /approval-opportunities/approve-registration, " +
               "POST /approval-opportunities/registration-opportunity";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token (BGĐ TTKD)").required()
                        .hint("lấy qua auth.get-token-api với account có role BGĐ TTKD"),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override chỉ khi đổi môi trường"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085565"),
                ParamSpec.text("reason").label("Ý kiến phê duyệt")
                        .defaultValue("Phe duyet YCHD 1800 tu dong qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken   = Params.requireString(p, "bearerToken");
        String baseUrl       = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String reason        = Params.optString(p, "reason", "Phe duyet YCHD 1800 tu dong qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        // Step 1: get registrationId(s)
        JsonNode opportunity = get(client, baseUrl + "/opportunities/" + opportunityId, bearerToken, log);
        JsonNode services = opportunity.path("data").path("services");
        if (!services.isArray() || services.isEmpty()) {
            throw new GeneratorException("Opportunity " + opportunityId + " không có services/registrations");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> regCodes = new ArrayList<>();

        for (JsonNode svc : services) {
            int registrationId = svc.path("registrationId").asInt(0);
            if (registrationId == 0) continue;

            int currentStatus = svc.path("bgdApprovalStatus").isMissingNode() || svc.path("bgdApprovalStatus").isNull()
                    ? PENDING : svc.path("bgdApprovalStatus").asInt(PENDING);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("registrationId", registrationId);

            if (currentStatus == APPROVED) {
                trace(log, "registrationId=" + registrationId + " đã duyệt rồi, bỏ qua");
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
            throw new GeneratorException("Không tìm thấy registrationId nào trong opportunity " + opportunityId);
        }

        // Step 2: lookup regCode (CMR...) để chain sang CM
        for (Map<String, Object> row : results) {
            String regCode = lookupRegCode(client, baseUrl, bearerToken, opportunityId,
                    (Integer) row.get("registrationId"), log);
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

    private void approveOne(HttpClient client, String baseUrl, String bearerToken,
            int registrationId, String reason, List<String> log) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registrationId", registrationId);
        payload.put("approvalStatus", APPROVED);
        payload.put("reason", reason);

        String url = baseUrl + "/approval-opportunities/approve-registration";
        trace(log, "POST " + url + " registrationId=" + registrationId);
        JsonNode resp = postJson(client, url, bearerToken, toJson(payload), log);
        if (resp != null && resp.path("success").isBoolean() && !resp.path("success").asBoolean()) {
            throw new GeneratorException("BMS trả success=false cho registrationId=" + registrationId + ": " + resp);
        }
    }

    private String lookupRegCode(HttpClient client, String baseUrl, String bearerToken,
            String opportunityId, int registrationId, List<String> log) {
        try {
            String url = baseUrl + "/approval-opportunities/registration-opportunity";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("keyword", opportunityId);
            payload.put("approvalStatus", APPROVED);
            payload.put("pageIndex", 1);
            payload.put("pageSize", 20);
            JsonNode resp = postJson(client, url, bearerToken, toJson(payload), log);
            JsonNode rows = resp != null ? resp.path("data").path("data") : null;
            if (rows != null && rows.isArray()) {
                for (JsonNode r : rows) {
                    if (r.path("registrationId").asInt(-1) == registrationId) {
                        String ref = r.path("refCode").asText(null);
                        if (ref != null) {
                            trace(log, "regCode=" + ref + " cho registrationId=" + registrationId);
                            return ref;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            trace(log, "lookup regCode lỗi: " + e.getMessage());
        }
        return null;
    }

    private JsonNode get(HttpClient client, String url, String token, List<String> log) {
        trace(log, "GET " + url);
        return send(client, HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .GET().build(), log);
    }

    private JsonNode postJson(HttpClient client, String url, String token, String body, List<String> log) {
        return send(client, HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), log);
    }

    private JsonNode send(HttpClient client, HttpRequest request, List<String> log) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Không kết nối được " + request.uri(), HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API HTTP " + response.statusCode() + ": " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        return parsed;
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new GeneratorException("Serialize JSON lỗi", e); }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception e) { return null; }
    }

    private GenerationResult.Builder result() { return GenerationResult.builder(type()); }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
