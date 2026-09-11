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
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Lists Phường/Xã for a chosen Tỉnh thành — feeds the "Phường/Xã" dropdown
 * on the "Tạo phiếu triển khai" (IVOICE) form, replacing the 2 manual-entry
 * fields (mã Phường + tên Phường) the UI needed before this: picking one
 * row here gives both {@code wardId} (its {@code id}) and {@code wardName}
 * (its {@code name}) at once.
 *
 * <p>Endpoint/body/response reverse-engineered 2026-09-03 from a real
 * captured {@code Copy as cURL} (same technique that solved
 * {@code InsertRegistrationIframe} and {@code GetPhoneBookedNumber}).
 * Unlike those two, the response here is a **bare JSON array**, not wrapped
 * in a {@code data}/{@code Data} object — confirmed live by running the
 * captured curl directly rather than guessing.</p>
 */
@Component
public class GetIvoiceWardGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://ftms-stag.fpt.net/ivoice/MasterData/GetWard";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "ivoice.get-ward"; }
    @Override public String displayName() { return "Danh sách Phường/Xã (IVOICE)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST ivoice/MasterData/GetWard — liệt kê Phường/Xã theo Tỉnh thành, để chọn wardId+wardName cho ivoice.create-registration-detail.";
    }
    @Override public String signature() { return "POST " + URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cookieHeader").label("Cookie IVOICE").required()
                        .hint("lấy qua generator \"auth.get-ivoice-session\""),
                ParamSpec.enumOf("locationId", List.of("8", "4"))
                        .label("Tỉnh thành").defaultValue("8")
                        .hint("8 = Thành phố Hồ Chí Minh, 4 = Thành phố Hà Nội")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cookieHeader = Params.requireString(p, "cookieHeader");
        String locationId = Params.optString(p, "locationId", "8");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        // Real captured field name is "LocationID" (capital ID) — distinct
        // from GetPhoneBookedNumber's "LocationId" (lowercase d). Two
        // different legacy controllers, two different casing conventions;
        // don't assume they're interchangeable.
        String formBody = "LocationID=" + URLEncoder.encode(locationId, StandardCharsets.UTF_8);

        trace(log, "POST " + URL + " LocationID=" + locationId);
        JsonNode response = post(client, cookieHeader, formBody, log);
        List<JsonNode> wards = StreamSupport.stream(
                (response.isArray() ? response : response.path("data")).spliterator(), false)
                .collect(Collectors.toList());
        trace(log, "found " + wards.size() + " ward(s)");

        return GenerationResult.builder(type())
                .put("wards", wards)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode post(HttpClient client, String cookieHeader, String formBody, List<String> log) {
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
