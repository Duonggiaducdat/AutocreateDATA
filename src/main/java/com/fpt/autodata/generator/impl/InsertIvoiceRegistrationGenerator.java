package com.fpt.autodata.generator.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Creates the "Tạo phiếu triển khai" (deployment detail) record for a
 * registration — the step that used to require the whole legacy
 * FTI-IVOICE UI (Bước "Thêm chi tiết") to fill in by hand.
 *
 * <p><b>Endpoint reverse-engineered 2026-09-03</b> from a real
 * {@code Copy as cURL} the user captured live in Chrome DevTools while
 * clicking "Lưu" on the deployment form — after every in-session
 * interception attempt (XHR/fetch monkey-patch, form-submit patch, CDP
 * network log, Performance Resource Timing API) came back completely empty
 * across many tries. See {@code memory/api-contracts.md} "Investigation:
 * Creating the Yêu cầu tạo HĐ/PL" for the full captured payload and field
 * mapping this class implements.</p>
 *
 * <p><b>Currently scoped to Đấu số thoại / Điện thoại cố định</b>
 * ({@code serviceGroup=1, serviceType=1}) — the only service type walked
 * end-to-end so far. Other service types (Data Center/Rack, etc.) very
 * likely need a different {@code configInfo} shape and are NOT handled by
 * this generator yet.</p>
 *
 * <p>Auth is a full cookie jar (see {@link GetIvoiceSessionGenerator}), not
 * a bearer token — this endpoint is legacy ASP.NET MVC, cookie-session
 * authed, confirmed live: the captured real request carried no
 * {@code Authorization} header at all.</p>
 */
@Component
public class InsertIvoiceRegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://ftms-stag.fpt.net/ivoice/Registration/InsertRegistrationIframe";
    private static final int TIMEOUT_SECONDS = 20;

    @Override public String type()        { return "ivoice.create-registration-detail"; }
    @Override public String displayName() { return "Tạo phiếu triển khai (IVOICE)"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() {
        return "POST ivoice/Registration/InsertRegistrationIframe — điền Địa chỉ triển khai + chọn số điện thoại cho 1 registration (hiện chỉ hỗ trợ Đấu số thoại/Điện thoại cố định).";
    }
    @Override public String signature() { return "POST " + URL; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.password("cookieHeader").label("Cookie IVOICE").required()
                        .hint("lấy qua generator \"auth.get-ivoice-session\""),
                ParamSpec.text("refCode").label("RefCode (Mã CH_RegistrationId)").required()
                        .placeholder("CH00085410_96362"),
                ParamSpec.text("opportunityCode").label("Mã cơ hội").required()
                        .placeholder("CH00085410"),
                ParamSpec.text("taxId").label("Mã số thuế KH").required(),

                ParamSpec.enumOf("locationId", List.of("8", "4"))
                        .label("Tỉnh thành")
                        .defaultValue("8")
                        .hint("8 = Thành phố Hồ Chí Minh, 4 = Thành phố Hà Nội — 2 giá trị duy nhất IVOICE chấp nhận (đã xác nhận sống)"),
                ParamSpec.text("locationName").label("Tên tỉnh thành (hiển thị)").defaultValue("Thành phố Hồ Chí Minh"),
                ParamSpec.text("wardId").label("Mã Phường/Xã").required()
                        .hint("lấy qua generator \"ivoice.get-ward\" — mã số dài, không tự bịa được"),
                ParamSpec.text("wardName").label("Tên Phường/Xã (hiển thị)").required(),
                ParamSpec.text("locationNumber").label("Số nhà, tên đường").required(),

                ParamSpec.text("contactName").label("Người liên hệ").required(),
                ParamSpec.text("contactPhone").label("Điện thoại liên hệ").required(),
                ParamSpec.text("contactEmail").label("Email liên hệ").required(),
                ParamSpec.text("deployDate").label("Ngày hẹn triển khai (MM/dd/yyyy)").required()
                        .placeholder("09/03/2026"),

                ParamSpec.enumOf("autoCall", List.of("1", "0")).label("Auto Call").defaultValue("0")
                        .hint("1 = Có, 0 = Không"),
                ParamSpec.text("ipMain").label("IP kết nối").required(),
                ParamSpec.enumOf("ipMainType", List.of("1", "2")).label("Loại đường truyền")
                        .defaultValue("2").hint("theo dropdown Loại đường truyền — 1/2 quan sát được, chưa xác nhận nhãn"),
                ParamSpec.text("portMain").label("Port").defaultValue("5060"),
                ParamSpec.enumOf("platform", List.of("1", "2")).label("Nền tảng")
                        .defaultValue("1").hint("1 = Thường, 2 = Voice Brandname"),
                ParamSpec.text("voiceDirectionOpen").label("Mã hướng cuộc gọi (nối dấu phẩy)")
                        .defaultValue("1")
                        .hint("vd \"2,1\" — mã số từng checkbox Nội hạt/Liên tỉnh/Di động/Quốc tế/Giải trí chưa map đầy đủ, tạm để tự nhập"),
                ParamSpec.text("packageProfile").label("Gói Profile (CC/SPS)").defaultValue("1"),
                ParamSpec.text("maxValue").label("Hạn mức sử dụng").defaultValue("2000000"),

                ParamSpec.text("phonesJson").label("Số điện thoại đã chọn (JSON)").required()
                        .hint("mảng object lấy nguyên từ response \"ivoice.get-phone-booked\" — vd [{\"id\":592982,\"phoneNumber\":\"02547300148\",\"subcriberCharge\":20000,\"platformFixPhone\":\"1\"}]")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String cookieHeader = Params.requireString(p, "cookieHeader");
        String refCode = Params.requireString(p, "refCode");
        String opportunityCode = Params.requireString(p, "opportunityCode");
        String taxId = Params.requireString(p, "taxId");
        String locationId = Params.optString(p, "locationId", "8");
        String locationName = Params.optString(p, "locationName", "Thành phố Hồ Chí Minh");
        String wardId = Params.requireString(p, "wardId");
        String wardName = Params.requireString(p, "wardName");
        String locationNumber = Params.requireString(p, "locationNumber");
        String contactName = Params.requireString(p, "contactName");
        String contactPhone = Params.requireString(p, "contactPhone");
        String contactEmail = Params.requireString(p, "contactEmail");
        String deployDate = Params.requireString(p, "deployDate");
        String autoCall = Params.optString(p, "autoCall", "0");
        String ipMain = Params.requireString(p, "ipMain");
        String ipMainType = Params.optString(p, "ipMainType", "2");
        String portMain = Params.optString(p, "portMain", "5060");
        String platform = Params.optString(p, "platform", "1");
        String voiceDirectionOpen = Params.optString(p, "voiceDirectionOpen", "1");
        String packageProfile = Params.optString(p, "packageProfile", "1");
        String maxValue = Params.optString(p, "maxValue", "2000000");
        String phonesJson = Params.requireString(p, "phonesJson");

        JsonNode phones = parsePhonesJson(phonesJson);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        ObjectNode dataJson = buildDataJson(refCode, opportunityCode, taxId, locationId, locationName,
                wardId, wardName, locationNumber, contactName, contactPhone, contactEmail, deployDate,
                autoCall, ipMain, ipMainType, portMain, platform, voiceDirectionOpen, packageProfile,
                maxValue, phones);

        trace(log, "POST " + URL + " refCode=" + refCode + " wardId=" + wardId + " phones=" + phones.size());
        JsonNode response = post(client, cookieHeader, toJson(dataJson), log);

        return GenerationResult.builder(type())
                .put("refCode", refCode)
                .put("response", response)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode parsePhonesJson(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray() || node.isEmpty()) {
                throw new GeneratorException("phonesJson phải là mảng có ít nhất 1 số điện thoại: " + raw);
            }
            return node;
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("phonesJson không phải JSON hợp lệ: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildDataJson(String refCode, String opportunityCode, String taxId,
            String locationId, String locationName, String wardId, String wardName, String locationNumber,
            String contactName, String contactPhone, String contactEmail, String deployDate,
            String autoCall, String ipMain, String ipMainType, String portMain, String platform,
            String voiceDirectionOpen, String packageProfile, String maxValue, JsonNode phones) {

        ObjectNode configInfo = MAPPER.createObjectNode();
        configInfo.put("connectType", "1");
        configInfo.put("ipMain", ipMain);
        configInfo.put("ipMainType", ipMainType);
        configInfo.put("portMain", portMain);
        configInfo.put("portBK1", "");
        configInfo.put("portBK2", "");
        configInfo.put("ipbK1Type", "");
        configInfo.put("ipbK2", "");
        configInfo.put("ipbK2Type", "");
        configInfo.put("ipbK1", "");
        configInfo.put("lineNo", "");
        configInfo.put("voiceDirectionOpen", voiceDirectionOpen);
        configInfo.put("maxValue", maxValue);
        configInfo.put("packageProfile", packageProfile);
        configInfo.put("platform", platform);
        configInfo.put("prefix", "Yes");
        configInfo.put("ipMedia", "");
        configInfo.put("ipMediaType", "");
        configInfo.put("portMedia", "");
        configInfo.put("forwardType", "");
        configInfo.put("intermediateNumber", "");
        configInfo.put("destinationNumber", "0");
        configInfo.put("MaxCall", "0");
        configInfo.put("ivR1900", "No");
        configInfo.put("ivrDebtReminder", "No");
        configInfo.put("allowHighCost", "No");
        configInfo.put("highCostCode", "");
        configInfo.put("voiceTypeSystem", "");
        configInfo.put("voiceFirstEndEq", "");
        configInfo.put("voiceModerm", "");
        configInfo.put("voiceConnectLayout", "");
        configInfo.put("voiceCodec", "");

        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("isNew", 1);
        detail.put("RegDetailId", 1);
        detail.put("ID", 1);
        detail.put("serviceGroup", "1");
        detail.put("serviceType", "1");
        detail.put("connectType", "1");
        detail.put("internetContract", "");
        detail.put("locationId", locationId);
        detail.put("streetId", 0);
        detail.put("wardId", wardId);
        detail.put("locationNumber", locationNumber);
        detail.put("contactName", contactName);
        detail.put("contactPhone", contactPhone);
        detail.put("contactEmail", contactEmail);
        detail.put("description", "");
        detail.put("locationName", locationName);
        detail.put("districtName", "");
        detail.put("wardName", wardName);
        detail.put("streetName", "");
        detail.putNull("fromObjId");
        detail.put("buildingAddress", "");
        detail.put("autoCall", autoCall);
        detail.put("deployDate", deployDate);
        detail.set("configInfo", configInfo);
        ArrayNode phonesArray = detail.putArray("lRegistrationPhoneModel");
        // ❌ 2026-09-07: used to be `phones.forEach(phonesArray::add)` — raw
        // forwarding of ivoice.get-phone-booked's own row shape, which is
        // NOT the same shape this array needs (confirmed live: caused a
        // real rejection). That endpoint's rows lack `platformFixPhone`
        // entirely and carry `regDetailCode` (always null) instead of the
        // `regDetailId` this array actually needs — see
        // GetIvoicePhoneBookedGenerator's javadoc for the full diff. Map
        // each row explicitly now instead of blindly copying it.
        for (JsonNode phone : phones) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("phoneNumber", phone.path("phoneNumber").asText(""));
            copyOrNull(row, "subcriberCharge", phone, "subcriberCharge");
            copyOrNull(row, "id", phone, "id");
            copyOrNull(row, "vipPhoneCharge", phone, "vipPhoneCharge");
            copyOrNull(row, "fromObjID", phone, "fromObjID");
            row.put("regDetailId", 1); // matches lRegistrationDetailModel[0].RegDetailId above — every real capture used 1
            copyOrNull(row, "chargeCommitment", phone, "chargeCommitment");
            copyOrNull(row, "methodBillingType", phone, "methodBillingType");
            row.put("platformFixPhone", "1"); // MISSING from ivoice.get-phone-booked's raw response — every real capture has "1", hardcode it
            copyOrNull(row, "callDirectionId", phone, "callDirectionId");
            copyOrNull(row, "callDirectionName", phone, "callDirectionName");
            copyOrNull(row, "brandNameId", phone, "brandNameId");
            copyOrNull(row, "voiceBrandName", phone, "voiceBrandName");
            copyOrNull(row, "bookingRequestId", phone, "bookingRequestId");
            phonesArray.add(row);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("linkServiceType", "");
        root.put("initServiceCharge", "");
        root.put("contractServiceID", "");
        root.put("isManyPrices", 0);
        root.put("PromotionJson", "[]");
        root.put("DeviceJson", "[]");
        root.put("SwitchboardJson", "[]");
        root.put("Cp1900Json", "[]");
        ArrayNode details = root.putArray("lRegistrationDetailModel");
        details.add(detail);
        root.put("opportunityCode", opportunityCode);
        root.put("taxId", taxId);
        root.put("serviceType", "1");
        root.put("serviceGroup", "1");
        root.put("refCode", refCode);
        root.put("autoCall", autoCall);
        root.put("PriceJson", "[]");
        root.put("ContractBMS", "");
        return root;
    }

    /** Copies {@code source[sourceKey]} into {@code target[key]}, or writes explicit JSON null if absent/null. */
    private void copyOrNull(ObjectNode target, String key, JsonNode source, String sourceKey) {
        JsonNode val = source.path(sourceKey);
        if (val.isMissingNode() || val.isNull()) {
            target.putNull(key);
        } else {
            target.set(key, val);
        }
    }

    private JsonNode post(HttpClient client, String cookieHeader, String dataJson, List<String> log) {
        String formBody = "dataJson=" + URLEncoder.encode(dataJson, StandardCharsets.UTF_8);
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
                    "IVOICE rejected the request (HTTP " + response.statusCode() + "): " + response.body(),
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
