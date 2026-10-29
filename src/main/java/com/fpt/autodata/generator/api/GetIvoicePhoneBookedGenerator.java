package com.fpt.autodata.generator.api;

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
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Lists the phone numbers currently bookable for a Tỉnh thành / Nền tảng —
 * feeds the "Danh sách số điện thoại đã book" picker on the "Tạo phiếu
 * triển khai" (IVOICE) form. The chosen row(s) get MAPPED (not just
 * forwarded — see correction below) into
 * {@link InsertIvoiceRegistrationGenerator}'s {@code lRegistrationPhoneModel}.
 *
 * <p><b>❌ Correction 2026-09-07:</b> this javadoc previously claimed this
 * response's row shape IS {@code lRegistrationPhoneModel}'s shape directly
 * — WRONG, confirmed live by a real failure: forwarding these rows
 * verbatim into {@code InsertRegistrationIframe} got a real rejection
 * ({@code {"code":400,"data":"Tạo PĐK không thành công"}}, no detail). A
 * field-by-field diff against a user-captured real "Copy as cURL" showed
 * this response is missing {@code platformFixPhone} ENTIRELY (every real
 * submit example has it = {@code "1"}) and carries {@code regDetailCode}
 * (always {@code null} here) where the real submit field is named {@code
 * regDetailId}. {@link InsertIvoiceRegistrationGenerator} now maps each row
 * explicitly instead of raw-forwarding it — see its own javadoc.</p>
 *
 * <p>Endpoint/body reverse-engineered 2026-09-03 from a real captured
 * {@code Copy as cURL} (same technique that solved
 * {@code InsertRegistrationIframe}) — unlike that endpoint, this one takes
 * plain individual form fields, not a single JSON blob.</p>
 */
@Component
public class GetIvoicePhoneBookedGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://ftms-stag.fpt.net/ivoice/Registration/GetPhoneBookedNumber";
    private static final int TIMEOUT_SECONDS = 20;
    // Response wrapper key isn't confirmed yet (only the request was
    // captured) — try the common shapes in order, fall back to "root is
    // already the array" if none match.
    private static final List<String> ARRAY_WRAPPER_KEYS = List.of("data", "Data", "d", "result", "Result");

    @Override public String type()        { return "ivoice.get-phone-booked"; }
    @Override public String displayName() { return "Danh sách số điện thoại đã book (IVOICE)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST ivoice/Registration/GetPhoneBookedNumber — liệt kê số điện thoại khả dụng theo Tỉnh thành + Mã số thuế KH, để chọn đưa vào ivoice.create-registration-detail.";
    }
    @Override public String signature() { return "POST " + URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cookieHeader").label("Cookie IVOICE").required()
                        .hint("lấy qua generator \"auth.get-ivoice-session\""),
                ParamSpec.enumOf("locationId", List.of("8", "4"))
                        .label("Tỉnh thành").defaultValue("8")
                        .hint("8 = Thành phố Hồ Chí Minh, 4 = Thành phố Hà Nội"),
                ParamSpec.text("taxId").label("Mã số thuế KH").required(),
                ParamSpec.text("serviceType").label("Loại dịch vụ").defaultValue("1")
                        .hint("1 = Điện thoại cố định"),
                ParamSpec.enumOf("voiceBrandName", List.of("1", "2")).label("Nền tảng")
                        .defaultValue("1").hint("1 = Thường, 2 = Voice Brandname"),
                ParamSpec.text("regDetailId").label("RegDetailId").defaultValue("-1")
                        .hint("-1 = đăng ký mới (mặc định quan sát được từ request thật)"),
                ParamSpec.text("regExcludeId").label("RegExcludeId").defaultValue("-1"),
                ParamSpec.text("contractCode").label("ContractCode").defaultValue(""),
                ParamSpec.bool("isCPN").label("isCPN").defaultValue(false),
                ParamSpec.text("partnerId").label("PartnerId").defaultValue("")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cookieHeader = Params.requireString(p, "cookieHeader");
        String locationId = Params.optString(p, "locationId", "8");
        String taxId = Params.requireString(p, "taxId");
        String serviceType = Params.optString(p, "serviceType", "1");
        String voiceBrandName = Params.optString(p, "voiceBrandName", "1");
        String regDetailId = Params.optString(p, "regDetailId", "-1");
        String regExcludeId = Params.optString(p, "regExcludeId", "-1");
        String contractCode = Params.optString(p, "contractCode", "");
        boolean isCPN = Boolean.parseBoolean(Params.optString(p, "isCPN", "false"));
        String partnerId = Params.optString(p, "partnerId", "");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        Map<String, String> form = Map.of(
                "LocationId", locationId,
                "TaxID", taxId,
                "ServiceType", serviceType,
                "RegDetailId", regDetailId,
                "ContractCode", contractCode,
                "RegExcludeId", regExcludeId,
                "isCPN", String.valueOf(isCPN),
                "PartnerId", partnerId,
                "VoiceBrandName", voiceBrandName
        );

        trace(log, "POST " + URL + " locationId=" + locationId + " taxId=" + taxId);
        JsonNode response = post(client, cookieHeader, form, log);
        List<JsonNode> phones = extractArray(response);
        trace(log, "found " + phones.size() + " phone(s)");

        return GenerationResult.builder(type())
                .put("phones", phones)
                .put("raw", response)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    /** Try known wrapper keys first; fall back to "root is the array itself". */
    private List<JsonNode> extractArray(JsonNode response) {
        if (response == null) return List.of();
        if (response.isArray()) {
            return StreamSupport.stream(response.spliterator(), false).collect(Collectors.toList());
        }
        for (String key : ARRAY_WRAPPER_KEYS) {
            JsonNode node = response.path(key);
            if (node.isArray()) {
                return StreamSupport.stream(node.spliterator(), false).collect(Collectors.toList());
            }
        }
        return List.of();
    }

    private JsonNode post(HttpClient client, String cookieHeader, Map<String, String> form, List<String> log) {
        String formBody = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
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
                    "IVOICE rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        if (parsed == null) {
            throw new GeneratorException("IVOICE returned non-JSON body: " + response.body());
        }
        return parsed;
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
