package com.fpt.autodata.generator.impl;

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
 * Fills the same note text into every stage of an Opportunity's "Vòng đời
 * cơ hội" (lifecycle) widget — the 5-stage ladder (Khảo sát/Tiếp cận,
 * Demo/Tư vấn, Chào giá, Đàm phán, Kết thúc) shown on the detail page.
 *
 * <p>Endpoint reverse-engineered 2026-08-25 from the real frontend bundle
 * (RTK Query API slice in {@code index.0lrqjQK8.js}, found by fetching every
 * loaded chunk and grepping for the modal's own strings) — see
 * {@code memory/api-contracts.md} "Stage note" section:</p>
 * <ul>
 *   <li>{@code GET  /opportunities/{opportunityId}/stages} — lists all 5 stage rows,
 *       each with its own row {@code id} (NOT the same as the 1-6 {@code stageId} enum)
 *       and current {@code planEndDate}/{@code note}.</li>
 *   <li>{@code PUT  /opportunities/{opportunityId}/stages/{id}} — body
 *       {@code {opportunityId, id, planEndDate, note}}. Confirmed live: updates the
 *       note on a stage in-place WITHOUT touching pipeline progression, unlike the
 *       separate "change stage" (advance) / "complete stage" mutations found in the
 *       same bundle at {@code .../stages/{id}/change} and {@code .../stages/{id}/complete}.</li>
 * </ul>
 */
@Component
public class StageNoteGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutodataProperties properties;

    public StageNoteGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "opportunity.stage-note"; }
    @Override public String displayName() { return "Ghi chú Vòng đời cơ hội (tất cả giai đoạn)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "GET danh sách 5 giai đoạn rồi PUT cùng 1 nội dung ghi chú vào từng giai đoạn — không đổi tiến trình xử lý.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/opportunities/{id}/stages -> PUT .../stages/{stageRowId}";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("bearerToken").label("Bearer token").required(),
                ParamSpec.text("baseUrl").label("API base URL")
                        .defaultValue(properties.getApi().getBaseUrl())
                        .hint("override only for a different environment"),
                ParamSpec.text("opportunityId").label("Mã cơ hội").required()
                        .placeholder("CH00085289"),
                ParamSpec.text("note").label("Nội dung ghi chú (áp cho tất cả giai đoạn)")
                        .defaultValue("Ghi chú tự động qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String note = Params.optString(p, "note", "Ghi chú tự động qua tool");

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .build();

        String stagesUrl = baseUrl + "/opportunities/" + opportunityId + "/stages";
        trace(log, "GET " + stagesUrl);
        JsonNode stagesResponse = send(client, "GET", stagesUrl, bearerToken, null, log);

        JsonNode items = stagesResponse.path("data").path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new GeneratorException(
                    "Không lấy được danh sách giai đoạn cho " + opportunityId + " — cơ hội có thể chưa tồn tại.");
        }

        List<Map<String, Object>> updated = new ArrayList<>();
        for (JsonNode stage : items) {
            long rowId = stage.path("id").asLong();
            String stageName = stage.path("stageName").asText(null);
            // Keep the stage's own planEndDate unchanged — only the note changes.
            // The GET returns a full ISO datetime ("2026-11-25T00:00:00"); the PUT
            // command wants date-only ("2026-11-25"), same format the modal itself sends.
            String planEndDate = stage.path("planEndDate").asText(null);
            if (planEndDate != null && planEndDate.length() >= 10) {
                planEndDate = planEndDate.substring(0, 10);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("opportunityId", opportunityId);
            payload.put("id", rowId);
            payload.put("planEndDate", planEndDate);
            payload.put("note", note);

            String updateUrl = baseUrl + "/opportunities/" + opportunityId + "/stages/" + rowId;
            trace(log, "PUT " + updateUrl + " (" + stageName + ")");
            send(client, "PUT", updateUrl, bearerToken, toJson(payload), log);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stageId", stage.path("stageId").asInt());
            row.put("stageName", stageName);
            row.put("rowId", rowId);
            updated.add(row);
        }

        return result()
                .put("opportunityId", opportunityId)
                .put("stagesUpdated", updated.size())
                .put("stages", updated)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode send(HttpClient client, String method, String url, String bearerToken, String body, List<String> log) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
