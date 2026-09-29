package com.fpt.autodata.generator.impl.service.ivoice.voice1800;

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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CM thực hiện + ghi nhận kết quả cho Yêu cầu Hợp đồng 1800 — new-cm API.
 *
 * <p>Base URL: {@code https://ftisapi-stag.fpt.net/api/cm} — captured live
 * 2026-09-15 từ CM v3.0.0 ({@code ftms-stag.fpt.net/new-cm/registration/CMR2614715}).
 * Khác hoàn toàn với old-CM ({@code gwcm/contract/api/v1/...}) đã dùng trong
 * {@link com.fpt.autodata.generator.impl.registration.ApproveContractCmGenerator}.</p>
 *
 * <p>Flow (confirmed live):
 * <ol>
 *   <li>GET /api/cm/registrations/detail?requestCode={regCode}
 *       → lấy {@code crTransactionId} (dùng cho mutations) + {@code transactionId}</li>
 *   <li>POST /api/cm/registrations/create-contract
 *       body: {@code {cmrTransactionId, noteOfApprover}}</li>
 *   <li>GET /api/cm/registrations/check-registration?transactionId={id}&newStatus=4
 *       → pre-check trước khi cập nhật status</li>
 *   <li>PUT /api/cm/registrations/update-status
 *       body: {@code {status:4, cmrTransactionId, statusName:"Hoàn thành"}}</li>
 * </ol>
 * Auth: Bearer token lấy qua {@code POST /api/cm/auth/sso} (CAS ticket với
 * service={@code https://ftms-stag.fpt.net/new-cm}). KHÔNG cần header "ticket"
 * như old-CM.</p>
 */
@Component
public class Cm1800ExecuteGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NEW_CM_BASE = "https://ftisapi-stag.fpt.net/api/cm";
    private static final int STATUS_HOAN_THANH = 4;
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "voice1800.cm-execute"; }
    @Override public String displayName() { return "CM thực hiện YCHD 1800 — new-cm v3"; }
    @Override public String category()    { return "Voice 1800"; }
    @Override public String description() {
        return "Lookup CMR → CreateContract → UpdateStatus=4 trên new-cm API (/api/cm/registrations/*). " +
               "Cần cmToken từ POST /api/cm/auth/sso (CAS ticket, service=new-cm).";
    }
    @Override public String signature() {
        return "GET /api/cm/registrations/detail, POST /api/cm/registrations/create-contract, " +
               "PUT /api/cm/registrations/update-status";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cmToken").label("CM Bearer token (new-cm)").required()
                        .hint("lấy qua: POST /api/cm/auth/sso với CAS ticket, service=https://ftms-stag.fpt.net/new-cm"),
                ParamSpec.text("cmBaseUrl").label("New-CM API base URL").defaultValue(NEW_CM_BASE),
                ParamSpec.text("regCode").label("Mã đăng ký CM (regCode)").required()
                        .placeholder("CMR2614715")
                        .hint("lấy từ kết quả voice1800.approve-registration → regCodes[0]"),
                ParamSpec.text("noteOfApprover").label("Ghi chú của CM")
                        .defaultValue("Tao HD tu dong qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cmToken       = Params.requireString(p, "cmToken");
        String baseUrl       = Params.optString(p, "cmBaseUrl", NEW_CM_BASE).replaceAll("/$", "");
        String regCode       = Params.requireString(p, "regCode");
        String noteOfApprover = Params.optString(p, "noteOfApprover", "Tao HD tu dong qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        // Step 1: lookup registration detail → crTransactionId + transactionId
        // Retry vì new-CM có thể chưa kịp sync từ BMS ngay sau khi BGĐ approve
        String detailUrl = baseUrl + "/registrations/detail?requestCode=" + regCode;
        JsonNode reg = null;
        int MAX_DETAIL_RETRY = 6;
        for (int attempt = 1; attempt <= MAX_DETAIL_RETRY; attempt++) {
            if (attempt > 1) {
                trace(log, "new-CM chưa có " + regCode + ", chờ 5s thử lại (" + attempt + "/" + MAX_DETAIL_RETRY + ")…");
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            trace(log, "GET " + detailUrl + " (attempt " + attempt + ")");
            JsonNode detailResp = getJson(client, detailUrl, cmToken, log);
            reg = detailResp != null ? detailResp.path("data").path("registration") : null;
            if (reg != null && !reg.isMissingNode() && !reg.path("crTransactionId").asText("").isBlank()) break;
            reg = null;
        }
        if (reg == null) {
            throw new GeneratorException("Không tìm thấy registration với regCode=" + regCode + " trong new-CM sau " + MAX_DETAIL_RETRY + " lần thử (mỗi lần cách 5s)");
        }

        int currentStatus    = reg.path("status").asInt(-1);
        String crTransactionId = reg.path("crTransactionId").asText(null);
        String transactionId   = reg.path("transactionId").asText(null);
        String contractNo      = reg.path("contractNo").asText(null);

        if (crTransactionId == null || crTransactionId.isBlank()) {
            throw new GeneratorException("regCode=" + regCode + " chưa có crTransactionId — chưa qua bước BMS approve?");
        }
        trace(log, "regCode=" + regCode + " crTransactionId=" + crTransactionId + " status=" + currentStatus);

        if (currentStatus == STATUS_HOAN_THANH) {
            trace(log, "status=4 (Hoàn thành) — bỏ qua, đã xong");
            return result()
                    .put("regCode", regCode)
                    .put("skipped", true)
                    .put("contractNo", contractNo)
                    .put("status", currentStatus)
                    .log(log)
                    .durationMs(System.currentTimeMillis() - t0)
                    .build();
        }

        // Step 2: create contract
        boolean contractExists = contractNo != null && !contractNo.isBlank();
        if (!contractExists) {
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("cmrTransactionId", crTransactionId);
            createBody.put("noteOfApprover", noteOfApprover);
            String createUrl = baseUrl + "/registrations/create-contract";
            trace(log, "POST " + createUrl + " cmrTransactionId=" + crTransactionId);
            postJson(client, createUrl, cmToken, toJson(createBody), log);
        } else {
            trace(log, "contractNo=" + contractNo + " đã tồn tại, bỏ qua create-contract");
        }

        // Step 3: check-registration pre-check
        if (transactionId != null && !transactionId.isBlank()) {
            String checkUrl = baseUrl + "/registrations/check-registration?transactionId=" + transactionId + "&newStatus=" + STATUS_HOAN_THANH;
            trace(log, "GET " + checkUrl);
            JsonNode checkResp = getJson(client, checkUrl, cmToken, log);
            if (checkResp != null && checkResp.path("data").path("isError").asBoolean(false)) {
                throw new GeneratorException("check-registration cảnh báo lỗi: " + checkResp.path("data").path("message").asText());
            }
        }

        // Step 4: update status = 4 (Hoàn thành)
        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("status", STATUS_HOAN_THANH);
        updateBody.put("cmrTransactionId", crTransactionId);
        updateBody.put("statusName", "Hoàn thành");
        String updateUrl = baseUrl + "/registrations/update-status";
        trace(log, "PUT " + updateUrl + " status=" + STATUS_HOAN_THANH);
        putJson(client, updateUrl, cmToken, toJson(updateBody), log);

        // Final: reload to get contract numbers
        JsonNode finalResp = getJson(client, detailUrl, cmToken, log);
        JsonNode finalReg  = finalResp != null ? finalResp.path("data").path("registration") : null;

        return result()
                .put("regCode", regCode)
                .put("skipped", false)
                .put("contractNo", finalReg != null ? finalReg.path("contractNo").asText(null) : contractNo)
                .put("status", STATUS_HOAN_THANH)
                .put("statusName", "Hoàn thành")
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode getJson(HttpClient client, String url, String token, List<String> log) {
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

    private JsonNode putJson(HttpClient client, String url, String token, String body, List<String> log) {
        return send(client, HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(body)).build(), log);
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
                    "new-cm API HTTP " + response.statusCode() + ": " + response.body(),
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
