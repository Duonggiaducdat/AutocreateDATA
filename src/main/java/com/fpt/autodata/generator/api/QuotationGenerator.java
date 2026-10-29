package com.fpt.autodata.generator.api;

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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a Báo giá (quotation) for an Opportunity via one direct multipart
 * REST call — same result as the old Selenium {@code QuoteGenerator}/{@code
 * BaoGia} page object (attach {@code dummy.pdf}, price every service on the
 * opportunity, submit), but without opening a browser.
 *
 * <p>Endpoint + exact multipart field shape reverse-engineered 2026-08-26 by
 * reading the real frontend bundle's FormData builder directly (found via
 * {@code QuotationEndpoints.*.js} for the endpoint list, then the calling
 * component in the main {@code index.*.js} chunk for the actual field names —
 * a POST of {@code {}} 415'd until the content-type was switched to
 * multipart, then an empty multipart POST's validation errors named the
 * required top-level fields, then the bundle's own FormData-building code
 * gave the exact per-service field naming). See {@code memory/api-contracts.md}
 * "Quotation" section for the full contract and confirmed live round-trip.</p>
 */
@Component
public class QuotationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutodataProperties properties;

    public QuotationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "opportunity.quotation"; }
    @Override public String displayName() { return "Tạo báo giá (đính kèm dummy.pdf)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "GET danh sách dịch vụ của cơ hội rồi POST multipart /api/quotation — định giá mỗi dịch vụ, đính kèm dummy.pdf.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/quotation/services/{id} -> POST multipart {api.baseUrl}/quotation";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token").required(),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085305"),
                ParamSpec.text("description").label("Mô tả báo giá")
                        .defaultValue("Báo giá tự động qua tool"),
                ParamSpec.text("pricePerService").label("Đơn giá mỗi dịch vụ (VND)")
                        .defaultValue("1")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String description = Params.optString(p, "description", "Báo giá tự động qua tool");
        long pricePerService = optLong(p, "pricePerService", 1L);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        String servicesUrl = baseUrl + "/quotation/services/" + opportunityId;
        trace(log, "GET " + servicesUrl);
        JsonNode servicesResponse = send(client, "GET", servicesUrl, bearerToken, null, null, log);

        JsonNode services = servicesResponse.path("data").path("services");
        if (!services.isArray() || services.isEmpty()) {
            throw new GeneratorException(
                    "Không lấy được danh sách dịch vụ cho " + opportunityId + " — cơ hội có thể chưa tồn tại hoặc chưa có dịch vụ.");
        }

        byte[] pdfBytes = loadDummyPdf();
        long totalValue = pricePerService * services.size();

        String boundary = "----AutoDataBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, opportunityId, description, totalValue, services, pricePerService, pdfBytes);

        String url = baseUrl + "/quotation";
        trace(log, "POST " + url + " (" + services.size() + " dịch vụ, đơn giá " + pricePerService + ")");
        JsonNode result = sendMultipart(client, url, bearerToken, boundary, body, log);

        return result()
                .put("opportunityId", opportunityId)
                .put("servicesCount", services.size())
                .put("totalValue", totalValue)
                .put("responseBody", result)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private byte[] buildMultipartBody(String boundary, String opportunityId, String description,
                                       long totalValue, JsonNode services, long pricePerService, byte[] pdfBytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            appendField(out, boundary, "opportunityId", opportunityId);
            appendField(out, boundary, "description", description);
            appendField(out, boundary, "totalValue", String.valueOf(totalValue));

            for (int i = 0; i < services.size(); i++) {
                JsonNode svc = services.get(i);
                String prefix = "services[" + i + "]";
                appendField(out, boundary, prefix + ".serviceGroup", svc.path("serviceGroupId").asText(""));
                appendField(out, boundary, prefix + ".serviceType", svc.path("serviceTypeId").asText(""));
                appendField(out, boundary, prefix + ".servicePackCode",
                        svc.path("servicePackCode").isNull() ? "" : svc.path("servicePackCode").asText(""));
                appendField(out, boundary, prefix + ".servicePackId", "");
                appendField(out, boundary, prefix + ".value", String.valueOf(pricePerService));
            }

            appendFile(out, boundary, "quotationDocuments", "dummy.pdf", "application/pdf", pdfBytes);

            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new GeneratorException("Failed to build multipart body", e);
        }
    }

    private void appendField(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void appendFile(ByteArrayOutputStream out, String boundary, String name, String filename,
                             String contentType, byte[] bytes) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] loadDummyPdf() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("file/dummy.pdf")) {
            if (in == null) {
                throw new GeneratorException("Không tìm thấy file/dummy.pdf trong classpath");
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new GeneratorException("Failed to load dummy.pdf", e);
        }
    }

    private JsonNode sendMultipart(HttpClient client, String url, String bearerToken, String boundary, byte[] body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return sendAndParse(client, request, url, log);
    }

    private JsonNode send(HttpClient client, String method, String url, String bearerToken, String body, String contentType, List<String> log) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken);
        if (body != null) {
            builder.header("Content-Type", contentType != null ? contentType : "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return sendAndParse(client, builder.build(), url, log);
    }

    private JsonNode sendAndParse(HttpClient client, HttpRequest request, String url, List<String> log) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Could not reach " + url, HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());

        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus status = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "BMS API rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
                    status != null ? status : HttpStatus.BAD_GATEWAY, null);
        }
        if (parsed != null && parsed.path("success").isBoolean() && !parsed.path("success").asBoolean()) {
            trace(log, "response: " + response.body());
            throw new GeneratorException("BMS API returned success=false: " + response.body());
        }
        return parsed;
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
