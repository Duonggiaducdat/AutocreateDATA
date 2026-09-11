package com.fpt.autodata.generator.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.autodata.config.AutodataProperties;
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
 * The real "Tạo" submit behind Bước 2/3 of the "Yêu cầu tạo HĐ/PL" wizard —
 * saves contract terms + customer + members + billing/tech contacts +
 * deployment address against an ALREADY-EXISTING {@code RegistrationId}
 * (minted by the earlier "Xác nhận tạo yêu cầu hợp đồng" click, still not
 * itself reverse-engineered — see {@code memory/api-contracts.md}).
 *
 * <p>Endpoint/payload reverse-engineered 2026-09-07 from a real captured
 * {@code Copy as cURL} (same technique as every other Ivoice/BMS endpoint
 * in this project). Legacy ASP.NET MVC, cookie-jar authed — reuses the
 * SAME cookie header {@link GetIvoiceSessionGenerator} already produces
 * ({@code auth.get-ivoice-session}), since {@code LoginPage.login()}
 * itself visits a legacy {@code bms/OppsMagt/Create} page as part of its
 * own click-through, which is what almost certainly mints the
 * {@code FTIBMS-Auth}/{@code FTIBMS} cookies this endpoint's auth checks
 * for.</p>
 *
 * <p>{@code Customer} is auto-fetched (same {@code GET
 * bms-service/api/customners/{id}} call — note the real endpoint's typo —
 * {@link LookupOpportunityForIvoiceGenerator} already uses) and embedded
 * near-verbatim, mirroring how {@code ivoice.get-phone-booked}'s chosen
 * rows get passed straight through into the IVOICE save. The 4 nested
 * list fields ({@code ListMember}/{@code ListBillingContact}/{@code
 * TechRegistrations}/{@code ListDeployment}) are accepted as raw JSON
 * array strings — same passthrough style {@code
 * InsertIvoiceRegistrationGenerator}'s {@code phonesJson} uses — since
 * their nested shape doesn't map cleanly to flat form params and a caller
 * building them already has the exact row shapes documented in
 * {@code memory/api-contracts.md}.</p>
 */
@Component
public class InsertBmsRegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://ftms-stag.fpt.net/bms/RegistrationMagt_v12/InsertRegistration";
    private static final int TIMEOUT_SECONDS = 20;

    private final AutodataProperties properties;

    public InsertBmsRegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "bms.insert-registration"; }
    @Override public String displayName() { return "Tạo HĐ/PL — Bước 2/3 (legacy BMS)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST bms/RegistrationMagt_v12/InsertRegistration — lưu điều khoản HĐ + khách hàng + liên hệ + địa chỉ triển khai, đẩy sang \"Chờ BGĐ duyệt\". Cần RegistrationId đã tồn tại (từ bước \"Xác nhận tạo yêu cầu hợp đồng\" — vẫn thao tác tay).";
    }
    @Override public String signature() { return "POST " + URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cookieHeader").label("Cookie (BMS legacy)").required()
                        .hint("lấy qua generator \"auth.get-ivoice-session\" — cùng cookie jar dùng lại được"),
                ParamSpec.password("bearerToken").label("Bearer token (BMS, để tra khách hàng)").required()
                        .hint("lấy qua generator \"auth.get-token\""),
                ParamSpec.text("baseUrl").label("API base URL").defaultValue(properties.getApi().getBaseUrl()),

                ParamSpec.text("opportunityCode").label("Mã cơ hội").required().placeholder("CH00085412"),
                ParamSpec.number("registrationId").label("RegistrationId").required()
                        .hint("từ RefCode {oppCode}_{registrationId} — RegistrationId phải đã tồn tại"),
                ParamSpec.text("customerId").label("Mã khách hàng").required()
                        .hint("dùng để tự tra chi tiết KH qua GET .../customners/{id}"),

                ParamSpec.text("serviceGroup").label("Nhóm dịch vụ").defaultValue("1"),
                ParamSpec.text("serviceType").label("Loại dịch vụ").defaultValue("1"),

                ParamSpec.text("typeContract").label("Loại văn bản (TypeDocContract)").defaultValue("1"),
                ParamSpec.text("typeContractCategory").label("Phân loại hợp đồng (TypeContract)").defaultValue("1"),
                ParamSpec.enumOf("location", List.of("8", "4")).label("Vùng miền").defaultValue("8")
                        .hint("8 = Thành phố Hồ Chí Minh, 4 = Thành phố Hà Nội"),
                ParamSpec.text("paymentMethod").label("Hình thức thanh toán").defaultValue("2"),
                ParamSpec.text("limitCreditDay").label("Hạn mức công nợ (ngày)").defaultValue("30"),
                ParamSpec.number("feeContract").label("Cước trả một lần").defaultValue(0),
                ParamSpec.number("monthlyFeeContract").label("Cước tháng").defaultValue(10),
                ParamSpec.number("dateContractMonth").label("Kỳ hạn thanh toán (tháng)").defaultValue(1),
                ParamSpec.number("contractValue").label("Giá trị HĐ/PL").defaultValue(0),
                ParamSpec.text("notes").label("Ghi chú").defaultValue(""),
                ParamSpec.text("description").label("Mô tả").defaultValue(""),

                ParamSpec.text("listMemberJson").label("ListMember (JSON array)").required()
                        .hint("vd [{\"Name\":\"thitlh\",\"Position\":1,\"ContractRate\":100,\"RefibbmembersId\":\"1000772803\",\"OpportunityId\":\"CH00085412\"}]"),
                ParamSpec.text("listBillingContactJson").label("ListBillingContact (JSON array)").required(),
                ParamSpec.text("techRegistrationsJson").label("TechRegistrations (JSON array)").required(),
                ParamSpec.text("listDeploymentJson").label("ListDeployment (JSON array)").required()
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cookieHeader = Params.requireString(p, "cookieHeader");
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityCode = Params.requireString(p, "opportunityCode");
        int registrationId = (int) Params.optInt(p, "registrationId", 0);
        if (registrationId == 0) throw new GeneratorException("registrationId is required and must be non-zero");
        String customerId = Params.requireString(p, "customerId");
        String refCode = opportunityCode + "_" + registrationId;

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        String custUrl = baseUrl + "/customners/" + customerId; // sic — real endpoint is misspelled
        trace(log, "GET " + custUrl);
        JsonNode customer = get(client, custUrl, bearerToken, log).path("data");
        if (customer.isMissingNode() || customer.isNull()) {
            throw new GeneratorException("Không tra được khách hàng " + customerId);
        }

        ObjectNode registration = MAPPER.createObjectNode();
        registration.put("TypeContract", Params.optString(p, "typeContractCategory", "1"));
        registration.put("TypeDocContract", Params.optString(p, "typeContract", "1"));
        registration.put("Location", Params.optString(p, "location", "8"));
        registration.put("StartContractDate", "");
        registration.put("EndContractDate", "");
        registration.put("AgencyContractFlag", 0);
        registration.put("OnSiteWarranty", 0);
        registration.put("AutoRenewContract", 0);
        registration.put("ContractValue", Params.optInt(p, "contractValue", 0));
        registration.put("ContractValueCurrency", "VND");
        registration.put("PaymentMethod", Params.optString(p, "paymentMethod", "2"));
        registration.put("DateContractMonth", Params.optInt(p, "dateContractMonth", 1));
        registration.put("FeeContract", Params.optInt(p, "feeContract", 0));
        registration.put("MonthlyFeeContract", Params.optInt(p, "monthlyFeeContract", 10));
        registration.put("Notes", Params.optString(p, "notes", ""));
        registration.put("Description", Params.optString(p, "description", ""));
        registration.put("LimitCreditDay", Params.optString(p, "limitCreditDay", "30"));
        registration.put("EContractFlag", 0);
        registration.put("IsDebt", 0);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("OpportunityId", opportunityCode);
        root.putNull("OrderID");
        root.put("RegistrationId", registrationId);
        root.put("RefCode", refCode);
        root.set("Customer", customer);
        root.set("ListMember", parseJsonArray(p, "listMemberJson"));
        root.put("ServiceGroup", Params.optString(p, "serviceGroup", "1"));
        root.put("ServiceType", Params.optString(p, "serviceType", "1"));
        root.put("RegCode", "");
        root.put("ContractNo", "");
        root.set("ListBillingContact", parseJsonArray(p, "listBillingContactJson"));
        root.set("TechRegistrations", parseJsonArray(p, "techRegistrationsJson"));
        root.set("ListDeployment", parseJsonArray(p, "listDeploymentJson"));
        root.putArray("ListEContractDocument");
        root.set("Registration", registration);
        root.put("IsUpdateTemp", false);
        root.put("AppCodeInsert", "FTIBMS");

        trace(log, "POST " + URL + " refCode=" + refCode);
        JsonNode response = post(client, cookieHeader, toJson(root), log);

        return GenerationResult.builder(type())
                .put("refCode", refCode)
                .put("response", response)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode parseJsonArray(Map<String, Object> p, String key) {
        String raw = Params.requireString(p, key);
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) throw new GeneratorException(key + " phải là JSON array: " + raw);
            return node;
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException(key + " không phải JSON hợp lệ: " + e.getMessage(), e);
        }
    }

    private JsonNode get(HttpClient client, String url, String bearerToken, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
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

    private JsonNode post(HttpClient client, String cookieHeader, String jsonData, List<String> log) {
        String formBody = "jsonData=" + URLEncoder.encode(jsonData, StandardCharsets.UTF_8);
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

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
