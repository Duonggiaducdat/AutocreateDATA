package com.fpt.autodata.generator.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 2 of "Phê duyệt Hợp đồng/Phụ lục (HĐ/PL)" — the CM-side approval,
 * done in a completely separate app ({@code ftms-stag.fpt.net/cm/}, gateway
 * {@code gwcm/contract/api/v1/...}) with its own JWT (see
 * {@link GetCmTokenGenerator}). Mirrors the real UI's 2-click flow exactly:
 *
 * <ol>
 *   <li><b>Tạo hợp đồng &amp; PL</b> — {@code POST Contract/CreateContractCM}
 *       {@code {CMRTransactionId, createBy}} — mints the actual contract/phụ
 *       lục numbers.</li>
 *   <li><b>Gửi yêu cầu triển khai</b> — {@code PUT Registration/UpdateStatus}
 *       {@code {updateby, status: 4, cmrTransactionId}} — advances the
 *       registration to "Hoàn thành".</li>
 * </ol>
 *
 * <p>Looked up first by {@code regCode} (e.g. {@code CMR2614586} — the CM
 * refCode assigned once BMS approves the registration, see
 * {@link ApproveRegistrationGenerator}) via {@code Registration/Get}, which
 * is also where the stable {@code crTransactionId} GUID comes from — this is
 * the id both mutations need, NOT the newer {@code transactionId} the same
 * row also carries once a contract exists. Idempotent: if the lookup already
 * shows {@code status: 4}, both mutations are skipped.</p>
 *
 * <p>End-to-end confirmed live 2026-08-27 on {@code CH00084099}/{@code
 * CMR2614586} — see {@code memory/api-contracts.md} "Approve Hợp đồng/Phụ
 * lục".</p>
 */
@Component
public class ApproveContractCmGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://ftms-stag.fpt.net/gwcm/contract/api";
    private static final int DONE_STATUS = 4;
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "registration.approve-cm"; }
    @Override public String displayName() { return "Duyệt HĐ/PL — bước 2 (CM)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "Tạo hợp đồng & PL (CreateContractCM) rồi Gửi yêu cầu triển khai (UpdateStatus=4) trên hệ thống CM — bước 2/2 sau khi đã duyệt ở BMS.";
    }
    @Override public String signature() {
        return "POST v1/Contract/CreateContractCM, PUT v1/Registration/UpdateStatus";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cmToken").label("CM bearer token").required()
                        .hint("lấy qua generator \"auth.get-cm-token\" — token này KHÁC token BMS thường dùng"),
                ParamSpec.text("baseUrl").label("CM API base URL").defaultValue(DEFAULT_BASE_URL),
                ParamSpec.text("regCode").label("Mã đăng ký CM").required()
                        .placeholder("CMR2614586"),
                ParamSpec.text("createBy").label("Người duyệt (createBy/updateBy)")
                        .hint("để trống sẽ tự lấy UserName từ cmToken")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cmToken = Params.requireString(p, "cmToken");
        String baseUrl = Params.optString(p, "baseUrl", DEFAULT_BASE_URL).replaceAll("/$", "");
        String regCode = Params.requireString(p, "regCode");
        String createBy = Params.optString(p, "createBy", usernameFromToken(cmToken));
        if (createBy == null || createBy.isBlank()) {
            throw new GeneratorException("Không tự lấy được username từ cmToken — điền tay tham số createBy");
        }

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        JsonNode row = lookupByRegCode(client, baseUrl, cmToken, regCode, log);
        if (row == null) {
            throw new GeneratorException("Không tìm thấy registration nào với regCode=" + regCode + " bên CM");
        }
        int currentStatus = row.path("status").asInt(-1);
        String transactionId = firstNonBlank(row.path("crTransactionId").asText(null), row.path("transactionId").asText(null));
        if (transactionId == null) {
            throw new GeneratorException("regCode=" + regCode + " không có crTransactionId/transactionId để duyệt: " + row);
        }

        if (currentStatus == DONE_STATUS) {
            trace(log, "regCode " + regCode + " already status=4 (Hoàn thành), skipping both steps");
            return result()
                    .put("regCode", regCode)
                    .put("skipped", true)
                    .put("contractNo", row.path("contractNo").asText(null))
                    .put("subContractNo", row.path("subContractNo").asText(null))
                    .log(log)
                    .durationMs(System.currentTimeMillis() - t0)
                    .build();
        }

        // The two mutation endpoints (unlike the plain Registration/Get lookup)
        // 403 "Please enter field InsideToken in your header request url"
        // without a header literally named "ticket" (lowercase) — misleading
        // error text, the field it actually reads is "ticket", NOT "InsideToken"
        // (confirmed live 2026-08-27: an "InsideToken" header/query-param both
        // still 403, only "ticket" cleared the permission check). Value is the
        // JWT's own "TICKET" claim (a CAS service ticket) echoed back, exactly
        // like the real UI's own `ticket:` header captured live. Missing this
        // is what silently made every prior test of this code path only ever
        // exercise the idempotent "already done" skip branch, never the real
        // mutation, until caught live on a genuinely fresh regCode.
        String insideToken = ticketFromToken(cmToken);
        if (insideToken == null) {
            throw new GeneratorException("Không tìm thấy claim TICKET trong cmToken — cần để gọi CreateContractCM/UpdateStatus");
        }

        boolean contractAlreadyCreated = row.path("contractNo").asText(null) != null && !row.path("contractNo").asText("").isBlank();
        if (!contractAlreadyCreated) {
            Map<String, Object> createPayload = new LinkedHashMap<>();
            createPayload.put("CMRTransactionId", transactionId);
            createPayload.put("createBy", createBy);
            String url = baseUrl + "/v1/Contract/CreateContractCM";
            trace(log, "POST " + url + " CMRTransactionId=" + transactionId);
            post(client, url, cmToken, insideToken, toJson(createPayload), log);
        } else {
            trace(log, "contract already created for " + regCode + " (contractNo=" + row.path("contractNo").asText() + "), skipping CreateContractCM");
        }

        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("updateby", createBy);
        updatePayload.put("status", DONE_STATUS);
        updatePayload.put("cmrTransactionId", transactionId);
        String updateUrl = baseUrl + "/v1/Registration/UpdateStatus";
        trace(log, "PUT " + updateUrl + " status=" + DONE_STATUS);
        putReq(client, updateUrl, cmToken, insideToken, toJson(updatePayload), log);

        JsonNode finalRow = lookupByRegCode(client, baseUrl, cmToken, regCode, log);

        return result()
                .put("regCode", regCode)
                .put("skipped", false)
                .put("contractNo", finalRow != null ? finalRow.path("contractNo").asText(null) : null)
                .put("subContractNo", finalRow != null ? finalRow.path("subContractNo").asText(null) : null)
                .put("statusName", finalRow != null ? finalRow.path("statusName").asText(null) : null)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode lookupByRegCode(HttpClient client, String baseUrl, String cmToken, String regCode, List<String> log) {
        String url = baseUrl + "/v1/Registration/Get?Page=1&PageSize=5&KeyWord=" + regCode;
        JsonNode response = get(client, url, cmToken, log);
        JsonNode rows = response != null ? response.path("data") : null;
        if (rows != null && rows.isArray()) {
            for (JsonNode r : rows) {
                if (regCode.equalsIgnoreCase(r.path("regCode").asText(""))) return r;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** cmToken carries the username under one of a few claim key spellings — no signature check, UX/plumbing only. */
    private String usernameFromToken(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode payload = MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
            for (String key : new String[]{"USERNAME", "UserName", "username"}) {
                String v = payload.path(key).asText(null);
                if (v != null && !v.isBlank()) return v;
            }
            var fields = payload.fieldNames();
            while (fields.hasNext()) {
                String k = fields.next();
                if (k.toLowerCase().endsWith("username")) {
                    String v = payload.path(k).asText(null);
                    if (v != null && !v.isBlank()) return v;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String padBase64(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "=".repeat(4 - mod);
    }

    /** The CAS service ticket the CM JWT itself carries under claim "TICKET" — required as the "InsideToken" header on mutation calls. */
    private String ticketFromToken(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode payload = MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
            String v = payload.path("TICKET").asText(null);
            return (v != null && !v.isBlank()) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode get(HttpClient client, String url, String token, List<String> log) {
        trace(log, "GET " + url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(client, request, log);
    }

    private JsonNode post(HttpClient client, String url, String token, String insideToken, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .header("ticket", insideToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(client, request, log);
    }

    private JsonNode putReq(HttpClient client, String url, String token, String insideToken, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .header("ticket", insideToken)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
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
                    "CM API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
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
