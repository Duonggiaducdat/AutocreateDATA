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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pre-check call the real BMS wizard fires just BEFORE the final "Tạo"
 * submit ({@link InsertBmsRegistrationGenerator}) — discovered 2026-09-07
 * from a real user-captured "Copy as cURL", a call our automated flow had
 * NEVER made before (it went straight from the login step to
 * {@code InsertRegistration}). Strong hypothesis, not yet 100% proven: this
 * is the missing "authorize this session to submit this registration" step
 * — every direct {@code InsertRegistration} call this session had ever made
 * (skipping this check) got rejected with {@code "Bạn không có quyền thao
 * tác hoặc yêu cầu đã hoàn thành không thể cập nhật."}, and this is the one
 * real step known to sit between login and that submit which was never
 * replicated.
 *
 * <p><b>Endpoint note:</b> {@code bms/RegistrationMagt/CheckServiceIntergrate}
 * — a DIFFERENT legacy controller path than {@code
 * RegistrationMagt_v12} (no {@code _v12} suffix, and "Intergrate" is the
 * real endpoint's own spelling, not a typo introduced here). Same cookie
 * jar as {@link InsertBmsRegistrationGenerator} (from {@code
 * auth.get-bms-legacy-session}) — same domain/auth realm.</p>
 *
 * <p>Real captured body (plain form, not JSON):
 * {@code refCode=CH00085443_96442&serviceGroup=1&serviceType=1&totalAmt=2&
 * paymentTypeCode=2&refKeys[]=null&refKeys[]=null&refKeys[]=null&
 * refKeys[]=null&refKeys[]=null} — {@code totalAmt} matched the real
 * submit's {@code Registration.ContractValue} exactly, {@code
 * paymentTypeCode} matched {@code Registration.PaymentMethod} exactly, and
 * the 5 {@code refKeys[]=null} entries matched the real submit's 5 {@code
 * ListDeployment} rows 1:1 — each is that row's own {@code
 * refCodeOfIntegrated} field (the literal string {@code "null"} in every
 * row observed so far, not JSON null).</p>
 */
@Component
public class CheckServiceIntegrateGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://ftms-stag.fpt.net/bms/RegistrationMagt/CheckServiceIntergrate";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "bms.check-service-integrate"; }
    @Override public String displayName() { return "Kiểm tra tích hợp dịch vụ trước khi Tạo (BMS legacy)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST bms/RegistrationMagt/CheckServiceIntergrate — pre-check thật gọi ngay trước nút \"Tạo\" (InsertRegistration), chưa rõ có bắt buộc hay không, đang thử nghiệm.";
    }
    @Override public String signature() { return "POST " + URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cookieHeader").label("Cookie (BMS legacy)").required()
                        .hint("lấy qua generator \"auth.get-bms-legacy-session\" — cùng cookie jar dùng cho bms.insert-registration"),
                ParamSpec.text("refCode").label("RefCode").required().placeholder("CH00085443_96442"),
                ParamSpec.text("serviceGroup").label("Nhóm dịch vụ").defaultValue("1"),
                ParamSpec.text("serviceType").label("Loại dịch vụ").defaultValue("1"),
                ParamSpec.text("totalAmt").label("Tổng giá trị (= Registration.ContractValue)").required(),
                ParamSpec.text("paymentTypeCode").label("Hình thức thanh toán (= Registration.PaymentMethod)").required(),
                ParamSpec.text("refKeysJson").label("refKeys (JSON array)").required()
                        .hint("1 phần tử / dòng ListDeployment, lấy đúng refCodeOfIntegrated của dòng đó — vd [\"null\",\"null\"] (chuỗi \"null\" theo capture thật, không phải JSON null)")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cookieHeader = Params.requireString(p, "cookieHeader");
        String refCode = Params.requireString(p, "refCode");
        String serviceGroup = Params.optString(p, "serviceGroup", "1");
        String serviceType = Params.optString(p, "serviceType", "1");
        String totalAmt = Params.requireString(p, "totalAmt");
        String paymentTypeCode = Params.requireString(p, "paymentTypeCode");
        String refKeysJson = Params.requireString(p, "refKeysJson");

        JsonNode refKeys;
        try {
            refKeys = MAPPER.readTree(refKeysJson);
            if (!refKeys.isArray()) throw new GeneratorException("refKeysJson phải là JSON array: " + refKeysJson);
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("refKeysJson không phải JSON hợp lệ: " + e.getMessage(), e);
        }

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        StringBuilder body = new StringBuilder();
        body.append("refCode=").append(URLEncoder.encode(refCode, StandardCharsets.UTF_8));
        body.append("&serviceGroup=").append(URLEncoder.encode(serviceGroup, StandardCharsets.UTF_8));
        body.append("&serviceType=").append(URLEncoder.encode(serviceType, StandardCharsets.UTF_8));
        body.append("&totalAmt=").append(URLEncoder.encode(totalAmt, StandardCharsets.UTF_8));
        body.append("&paymentTypeCode=").append(URLEncoder.encode(paymentTypeCode, StandardCharsets.UTF_8));
        for (JsonNode key : refKeys) {
            body.append("&refKeys%5B%5D=").append(URLEncoder.encode(key.asText("null"), StandardCharsets.UTF_8));
        }

        trace(log, "POST " + URL + " refCode=" + refCode + " refKeys.size=" + refKeys.size());
        JsonNode response = post(client, cookieHeader, body.toString(), log);

        return GenerationResult.builder(type())
                .put("refCode", refCode)
                .put("response", response)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode post(HttpClient client, String cookieHeader, String formBody, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Cookie", cookieHeader)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://ftms-stag.fpt.net")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + URL, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS legacy rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        return parsed != null ? parsed : MAPPER.getNodeFactory().textNode(response.body());
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
