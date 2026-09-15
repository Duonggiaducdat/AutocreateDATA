package com.fpt.autodata.generator.impl.ivoice;

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
import java.util.List;
import java.util.Map;

/**
 * Given just a Mã cơ hội, auto-derives everything else the "Ivoice" card
 * otherwise made the user type by hand (user request 2026-09-03: "Mã số
 * thuế KH khi tôi nhập mã cơ hội bạn phải tự lấy được chứ" / "RefCode chỗ
 * này cũng vậy ... bạn phải tự biết chứ").
 *
 * <p>Chain (2 plain bms-service REST calls, real bearer token — same one
 * {@code auth.get-token} already produces, NOT the IVOICE cookie jar):</p>
 * <ol>
 *   <li>{@code GET bms-service/api/opportunities/{opportunityCode}} — its
 *       {@code data.customerId} and {@code data.services[]} (each carrying
 *       its own {@code serviceGroup}/{@code serviceType}/{@code
 *       registrationId}) are both undocumented until this generator;
 *       {@code registrationId} is exactly the suffix RefCode needs
 *       ({@code opportunityCode + "_" + registrationId}).</li>
 *   <li>{@code GET bms-service/api/customners/{customerId}} — note the
 *       real endpoint's typo, "customners" not "customers", found live
 *       2026-09-03 via Performance Resource Timing after every guessed
 *       (correctly-spelled) URL 404'd. Its {@code taxOrHouseholdCode} is
 *       the tax code for an organization customer; for an individual/
 *       foreign customer that field is null and the same value instead
 *       lives in {@code identity} (confirmed live: a customer with
 *       {@code identity:"P37590260"} was the exact {@code taxId} value a
 *       real captured {@code InsertRegistrationIframe}/{@code
 *       GetPhoneBookedNumber} request used) — try both.</li>
 * </ol>
 */
@Component
public class LookupOpportunityForIvoiceGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutodataProperties properties;

    public LookupOpportunityForIvoiceGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "ivoice.lookup-opportunity"; }
    @Override public String displayName() { return "Tra cứu cơ hội cho Ivoice"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "GET opportunities/{code} + GET customners/{customerId} — tự lấy RefCode/taxId từ Mã cơ hội, khỏi gõ tay.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/opportunities/{code} -> GET .../customners/{customerId}";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token (BMS)").required()
                        .hint("lấy qua generator \"auth.get-token\""),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl()),
                ParamSpec.text("opportunityCode").label("Mã cơ hội").required()
                        .placeholder("CH00085410"),
                ParamSpec.text("serviceType").label("Loại dịch vụ (lọc services[])").defaultValue("1")
                        .hint("1 = Điện thoại cố định — nếu cơ hội có nhiều dịch vụ, lấy đúng registrationId của loại này")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityCode = Params.requireString(p, "opportunityCode");
        String serviceTypeFilter = Params.optString(p, "serviceType", "1");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        String oppUrl = baseUrl + "/opportunities/" + opportunityCode;
        trace(log, "GET " + oppUrl);
        JsonNode opp = get(client, oppUrl, bearerToken, log).path("data");
        String customerId = opp.path("customerId").asText(null);
        if (customerId == null) {
            throw new GeneratorException("Không tìm thấy customerId cho cơ hội " + opportunityCode);
        }

        Integer registrationId = null;
        for (JsonNode svc : opp.path("services")) {
            if (serviceTypeFilter.equals(svc.path("serviceType").asText(null))
                    || serviceTypeFilter.equals(String.valueOf(svc.path("serviceType").asInt(-1)))) {
                int rid = svc.path("registrationId").asInt(0);
                if (rid != 0) { registrationId = rid; break; }
            }
        }
        if (registrationId == null) {
            // fall back to the first service with a registrationId, in case
            // the caller's serviceType filter doesn't match what's on this
            // opportunity yet (still useful: caller can see raw services).
            for (JsonNode svc : opp.path("services")) {
                int rid = svc.path("registrationId").asInt(0);
                if (rid != 0) { registrationId = rid; break; }
            }
        }
        if (registrationId == null) {
            throw new GeneratorException("Cơ hội " + opportunityCode + " chưa có registrationId nào (chưa qua bước Yêu cầu tạo HĐ) — services=" + opp.path("services"));
        }
        String refCode = opportunityCode + "_" + registrationId;

        String custUrl = baseUrl + "/customners/" + customerId; // sic — real endpoint is misspelled, confirmed live 2026-09-03
        trace(log, "GET " + custUrl);
        JsonNode cust = get(client, custUrl, bearerToken, log).path("data");
        String taxOrHousehold = cust.path("taxOrHouseholdCode").asText(null);
        String identity = cust.path("identity").asText(null);
        String taxId = (taxOrHousehold != null && !taxOrHousehold.isBlank()) ? taxOrHousehold : identity;
        if (taxId == null || taxId.isBlank()) {
            throw new GeneratorException("Khách hàng " + customerId + " không có taxOrHouseholdCode lẫn identity — không suy ra được Mã số thuế");
        }

        // saleName từ approval.saleName (AM của cơ hội) — giúp UI auto-fill
        // "Tài khoản Sale" khỏi nhập tay, thay vì chỉ dùng account xác thực.
        String saleName = opp.path("approval").path("saleName").asText(null);

        return GenerationResult.builder(type())
                .put("opportunityCode", opportunityCode)
                .put("customerId", customerId)
                .put("registrationId", registrationId)
                .put("refCode", refCode)
                .put("taxId", taxId)
                .put("saleName", saleName)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode get(HttpClient client, String url, String bearerToken, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new GeneratorException("Không parse được response JSON từ " + url, e);
        }
    }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
