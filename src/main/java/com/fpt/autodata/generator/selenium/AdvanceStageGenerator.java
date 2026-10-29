package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.OpportunityStageV2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BMS v2 — cập nhật "VÒNG ĐỜI CƠ HỘI" (5 giai đoạn Báo giá: Khảo sát/Tiếp cận → Demo/Tư vấn
 * → Chào giá → Đàm phán → Kết thúc/Yêu cầu tạo hợp đồng) cho 1 cơ hội đã ở giai đoạn
 * "Khai thác cơ hội", qua modal "Chuyển giai đoạn". Tự chứa (login + tìm cơ hội + chuyển
 * lần lượt các giai đoạn) trong 1 driver session — generator khác không giữ được session
 * giữa các lần gọi API riêng biệt (xem {@link FtmsAutomation#withDriver}).
 */
@Component
public class AdvanceStageGenerator extends AbstractGenerator {

    public AdvanceStageGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "opportunity.advance-stage"; }
    @Override public String displayName() { return "Update Quote Stages (v2)"; }
    @Override public String category()    { return "Quote"; }
    @Override public String description() { return "Cap nhat 5 giai doan Bao gia (VONG DOI CO HOI) tren BMS v2 qua modal Chuyen giai doan."; }
    @Override public String signature()   { return "advanceStages(username, otp, chCode, stages[], daysAhead): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").required().placeholder("VD: sale02@fpt.com"),
                ParamSpec.password("otp").required().defaultValue("REDACTED_OTP_CODE").hint("OTP tinh tren staging"),
                ParamSpec.text("chCode").required().placeholder("VD: CH00084632"),
                ParamSpec.text("stages").required()
                        .placeholder("Demo/Tư vấn,Chào giá,Đàm phán")
                        .hint("csv, moi phan tu la 1 giai doan dich; co the them ghi chu rieng bang 'GiaiDoan|Ghi chu'"),
                ParamSpec.number("daysAhead").defaultValue(15).range(0, 365)
                        .hint("Ngay du kien ket thuc moi giai doan = hom nay + N ngay")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String otp      = Params.requireString(p, "otp");
        String chCode   = Params.requireString(p, "chCode");
        String stagesRaw = Params.requireString(p, "stages");
        int daysAhead   = Params.optInt(p, "daysAhead", 15);

        List<String[]> stageNotePairs = parseStages(stagesRaw);
        String planEndDate = OpportunityStageV2.isoDatePlusDays(daysAhead);

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        Map<String, String> results = new LinkedHashMap<>();
        List<String> lastAvailableOptions = new ArrayList<>();

        trace(log, "login(" + username + ")");
        trace(log, "gotoOpportunity(" + chCode + ")");

        automation.withDriver(driver -> {
            OpportunityStageV2 page = new OpportunityStageV2(driver);
            page.login(username, otp);
            page.gotoOpportunity(chCode);

            for (String[] pair : stageNotePairs) {
                String stage = pair[0];
                String note = pair[1];
                try {
                    List<String> available = page.advanceStage(stage, planEndDate, note);
                    lastAvailableOptions.clear();
                    lastAvailableOptions.addAll(available);
                    results.put(stage, "OK");
                    trace(log, "ok advanceStage(\"" + stage + "\")");
                } catch (RuntimeException e) {
                    results.put(stage, "FAIL: " + e.getMessage());
                    trace(log, "FAIL advanceStage(\"" + stage + "\"): " + e.getMessage());
                    // Không throw cứng — dừng loop nhưng vẫn trả về kết quả các giai đoạn đã
                    // chuyển thành công trước đó, tránh mất tiến độ do 1 giai đoạn không hợp lệ.
                    break;
                }
            }
        });

        boolean anyFail = results.values().stream().anyMatch(v -> v.startsWith("FAIL"));
        if (anyFail && results.values().stream().allMatch(v -> v.startsWith("FAIL"))) {
            // Toàn bộ đều fail ngay từ giai đoạn đầu tiên — coi là lỗi cứng để FE hiển thị rõ.
            throw new GeneratorException("Không chuyển được giai đoạn nào: " + results);
        }

        return result()
                .put("chCode", chCode)
                .put("results", results)
                .put("planEndDate", planEndDate)
                .put("lastAvailableOptions", lastAvailableOptions)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    /** "Demo/Tư vấn,Chào giá|ghi chu rieng,Đàm phán" -> [["Demo/Tư vấn",""],["Chào giá","ghi chu rieng"],["Đàm phán",""]] */
    private List<String[]> parseStages(String raw) {
        List<String[]> out = new ArrayList<>();
        for (String segment : raw.split(",")) {
            String s = segment.trim();
            if (s.isEmpty()) continue;
            int idx = s.indexOf('|');
            if (idx >= 0) {
                out.add(new String[]{s.substring(0, idx).trim(), s.substring(idx + 1).trim()});
            } else {
                out.add(new String[]{s, ""});
            }
        }
        if (out.isEmpty()) throw new GeneratorException("Param 'stages' khong hop le: " + raw);
        return out;
    }
}
