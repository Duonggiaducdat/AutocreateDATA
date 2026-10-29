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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates a throwaway customer on the CM system (foreign-org shape, the
 * minimal validation-required field set) — used as a fallback so
 * {@code opportunity.api} can be run without hand-picking a real
 * {@code customerId} first.
 *
 * <p>Endpoint/payload reverse-engineered 2026-08-27 by using the CM UI's own
 * "Random fill (test)" button on {@code /new-cm/customer/create} and
 * patching {@code XMLHttpRequest} client-side to capture the real submit
 * call (Axios uses XHR, not {@code fetch}, in this app). Empty-body and
 * partial-body probes then narrowed the full random-filled payload down to
 * the actual hard-required set: {@code customerName, email, phone,
 * groupCode, address, customerType, profileType}. {@code customerType: 2} +
 * {@code profileType: 4} = "Tổ chức nước ngoài" (foreign org) — the simplest
 * variant, since it skips the province/ward cascading pickers a domestic
 * org needs and just takes a free-text address.</p>
 *
 * <p>Confirmed live: {@code FTI260726}/{@code FTI260727} created this way,
 * both immediately usable as a real {@code customerId} for
 * {@code opportunity.api}.</p>
 */
@Component
public class CreateRandomCustomerGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE_URL = "https://ftisapi-stag.fpt.net/api/cm/customer/create";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "customer.create-random"; }
    @Override public String displayName() { return "Tạo khách hàng ngẫu nhiên (CM)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST api/cm/customer/create — tạo nhanh 1 khách hàng test (tổ chức nước ngoài) để lấy customerId dùng cho opportunity.api khi chưa có sẵn.";
    }
    @Override public String signature() { return "POST " + CREATE_URL; }

    private static final String KIND_ORG = "to-chuc-nuoc-ngoai";
    private static final String KIND_INDIVIDUAL = "ca-nhan-nuoc-ngoai";

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cmToken").label("CM bearer token").required()
                        .hint("lấy qua generator \"auth.get-cm-token\""),
                ParamSpec.enumOf("customerKind", List.of(KIND_ORG, KIND_INDIVIDUAL))
                        .label("Loại khách hàng").defaultValue(KIND_ORG)
                        .hint("Tổ chức nước ngoài / Cá nhân nước ngoài — cả 2 đều bỏ qua Tỉnh/Phường (đơn giản nhất)"),
                ParamSpec.text("namePrefix").label("Tiền tố tên KH").defaultValue("QA Auto Test")
        );
    }

    // Chỉ letters — customerType:1 (cá nhân) áp 1 pattern tên người thật, số trong
    // tên bị 400 "Tên khách hàng không hợp lệ" (xác nhận sống 2026-08-27); tổ chức
    // (customerType:2) thì không kiểm tra chặt nên vẫn cho số cho unique dễ.
    private static final String[] SURNAMES = {"Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu", "Dang", "Bui", "Do", "Ngo", "Duong", "Ly"};
    private static final String[] GIVEN_NAMES = {"An", "Binh", "Chi", "Dung", "Giang", "Hoa", "Khang", "Linh", "Minh", "Nam", "Phuc", "Quyen", "Thao", "Tuan", "Yen"};

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cmToken = Params.requireString(p, "cmToken");
        String namePrefix = Params.optString(p, "namePrefix", "QA Auto Test");
        String kind = Params.optString(p, "customerKind", KIND_ORG);
        boolean individual = KIND_INDIVIDUAL.equals(kind);

        int suffix = ThreadLocalRandom.current().nextInt(100000, 999999);
        String phone = "09" + ThreadLocalRandom.current().nextInt(10000000, 99999999);

        Map<String, Object> payload = new LinkedHashMap<>();
        String customerName;
        String slug;
        if (individual) {
            // person-name pattern rejects digits — vary via a random given name instead.
            String surname = SURNAMES[ThreadLocalRandom.current().nextInt(SURNAMES.length)];
            String given = GIVEN_NAMES[ThreadLocalRandom.current().nextInt(GIVEN_NAMES.length)];
            String given2 = GIVEN_NAMES[ThreadLocalRandom.current().nextInt(GIVEN_NAMES.length)];
            customerName = surname + " Van " + given + " " + given2; // 4-word name, ~12x12x15x15 combos
            slug = (customerName + suffix).toLowerCase().replaceAll("[^a-z0-9]+", "");
            payload.put("customerName", customerName);
            payload.put("email", slug + "@example-test.vn");
            payload.put("phone", phone);
            payload.put("groupCode", "Anonymous");
            payload.put("address", "1 Duong Test So " + suffix + ", Test City");
            payload.put("customerType", 1);   // 1 = Cá nhân
            payload.put("profileType", 2);    // 2 = Nước ngoài (individual variant)
            payload.put("titleId", 1);        // Ông
            payload.put("identifyId", "P" + ThreadLocalRandom.current().nextInt(10000000, 99999999));
            payload.put("passportDate", "2024-01-01");
            payload.put("issuedBy", "Cuc Quan ly xuat nhap canh");
            payload.put("countryCode", "VNM");
        } else {
            customerName = namePrefix + " " + suffix;
            slug = customerName.toLowerCase().replaceAll("[^a-z0-9]+", "");
            payload.put("customerName", customerName);
            payload.put("email", slug + "@example-test.vn");
            payload.put("phone", phone);
            payload.put("groupCode", "Anonymous");
            payload.put("address", "1 Duong Test So " + suffix + ", Test City");
            payload.put("customerType", 2);   // 2 = Tổ chức nước ngoài — skips province/ward pickers
            payload.put("profileType", 4);
        }

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        trace(log, "POST " + CREATE_URL + " customerName=" + customerName);
        JsonNode response = post(client, CREATE_URL, cmToken, toJson(payload), log);

        String customerCode = response != null ? response.path("data").path("customerCode").asText(null) : null;
        if (customerCode == null) {
            throw new GeneratorException("Tạo khách hàng thành công nhưng không đọc được customerCode từ response: " + response);
        }

        return result()
                .put("customerCode", customerCode)
                .put("customerName", customerName)
                .put("taxNo", response.path("data").path("taxNo").asText(null))
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode post(HttpClient client, String url, String token, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + token)
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
        if (response.statusCode() / 100 != 2 || (parsed != null && "NOTOK".equalsIgnoreCase(parsed.path("status").asText("")))) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "CM API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
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
