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
 * Actually ADVANCES an Opportunity through its remaining "Vòng đời cơ hội"
 * stages (Khảo sát/Tiếp cận → Demo/Tư vấn → Chào giá → Đàm phán → Kết thúc),
 * one real "Chuyển giai đoạn" transition at a time, each carrying the same
 * note — unlike {@link StageNoteGenerator} (which only edits the note/date
 * on stage rows in place, never touching progress).
 *
 * <p>Endpoint reverse-engineered 2026-08-25 from the real frontend bundle
 * (same RTK Query API slice as {@link StageNoteGenerator}'s
 * {@code index.0lrqjQK8.js}) — see {@code memory/api-contracts.md}
 * "Stage note" / "Chuyển giai đoạn" section:</p>
 * <ul>
 *   <li>{@code GET  /opportunities/{opportunityId}/stages} — same list call as StageNoteGenerator.</li>
 *   <li>{@code POST /opportunities/{opportunityId}/stages/{targetId}/change} — body
 *       {@code {opportunityId, id: targetId, planEndDate, note}} (the {@code id} here is the
 *       TARGET stage row's own id, not the current stage's). This is the exact call the
 *       "Chuyển giai đoạn" modal makes — direct-API equivalent of clicking it once.</li>
 * </ul>
 *
 * <p>This generator calls that endpoint once per remaining stage, in order,
 * starting right after whichever stage is currently active (found via
 * {@code isActive == 2} in the GET response) through to the last stage in
 * the list — i.e. a full "walk to the end" of the pipeline, exactly what N
 * manual "Chuyển giai đoạn" clicks would do.</p>
 */
@Component
public class StageAdvanceGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ACTIVE = 2;

    private final AutodataProperties properties;

    public StageAdvanceGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "opportunity.stage-advance"; }
    @Override public String displayName() { return "Chuyển hết Vòng đời cơ hội (thật, tiến độ 100%)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "GET danh sách giai đoạn rồi POST .../stages/{id}/change tuần tự cho từng giai đoạn còn lại — chuyển tiến độ thật, không chỉ sửa ghi chú.";
    }
    @Override public String signature() {
        return "GET {api.baseUrl}/opportunities/{id}/stages -> POST .../stages/{targetId}/change (x N)";
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
                ParamSpec.text("note").label("Nội dung ghi chú (áp cho mỗi lần chuyển giai đoạn)")
                        .defaultValue("Chuyển giai đoạn tự động qua tool")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bearerToken = Params.requireString(p, "bearerToken");
        String baseUrl = Params.optString(p, "baseUrl", properties.getApi().getBaseUrl()).replaceAll("/$", "");
        String opportunityId = Params.requireString(p, "opportunityId");
        String note = Params.optString(p, "note", "Chuyển giai đoạn tự động qua tool");

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

        int currentIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path("isActive").asInt() == ACTIVE) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex == -1) {
            throw new GeneratorException(
                    "Không xác định được giai đoạn hiện tại (isActive=2) của " + opportunityId);
        }
        if (currentIndex == items.size() - 1) {
            return result()
                    .put("opportunityId", opportunityId)
                    .put("stagesAdvanced", 0)
                    .put("note", "Cơ hội đã ở giai đoạn cuối, không còn giai đoạn nào để chuyển tới.")
                    .log(log)
                    .durationMs(System.currentTimeMillis() - t0)
                    .build();
        }

        List<Map<String, Object>> advanced = new ArrayList<>();
        for (int i = currentIndex + 1; i < items.size(); i++) {
            JsonNode target = items.get(i);
            long targetId = target.path("id").asLong();
            String stageName = target.path("stageName").asText(null);
            String planEndDate = target.path("planEndDate").asText(null);
            if (planEndDate != null && planEndDate.length() >= 10) {
                planEndDate = planEndDate.substring(0, 10);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("opportunityId", opportunityId);
            payload.put("id", targetId);
            payload.put("planEndDate", planEndDate);
            payload.put("note", note);

            String changeUrl = baseUrl + "/opportunities/" + opportunityId + "/stages/" + targetId + "/change";
            trace(log, "POST " + changeUrl + " (-> " + stageName + ")");
            send(client, "POST", changeUrl, bearerToken, toJson(payload), log);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stageId", target.path("stageId").asInt());
            row.put("stageName", stageName);
            row.put("rowId", targetId);
            advanced.add(row);
        }

        return result()
                .put("opportunityId", opportunityId)
                .put("stagesAdvanced", advanced.size())
                .put("stages", advanced)
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
