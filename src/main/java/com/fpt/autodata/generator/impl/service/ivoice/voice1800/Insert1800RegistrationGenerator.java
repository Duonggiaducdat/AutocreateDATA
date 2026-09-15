package com.fpt.autodata.generator.impl.service.ivoice.voice1800;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tạo Yêu cầu Hợp đồng (YCHD) cho dịch vụ Đầu số thoại - 1800
 * (serviceGroup=1, serviceType=2) qua BMS v2 + SM v2 APIs (không dùng legacy
 * cookie endpoint).
 *
 * <p>Flow:
 * <ol>
 *   <li>Lookup số 1800 available: POST {smBase}/ivoice/api/registration/get-phone-booked-number</li>
 *   <li>Submit IVOICE registration: POST {smBase}/ivoice/api/registration/insert-registration-iframe</li>
 *   <li>Submit BMS registration (final): POST {bmsBase}/registration/{registrationId}</li>
 * </ol>
 * Captured live 2026-09-15 từ CH00085565 / registrationId=96735.
 * </p>
 *
 * <p>Auth: BMS Bearer token (auth.get-token-api) + SM Bearer token (auth.get-sm-token-api).
 * Hai token dùng cho hai hệ thống khác nhau.</p>
 */
@Component
public class Insert1800RegistrationGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SM_BASE = "https://ftisapi-stag.fpt.net/sm/v2";
    private static final int TIMEOUT_SECONDS = 20;
    private static final int SERVICE_GROUP = 1;
    private static final int SERVICE_TYPE = 2; // 1800

    private final AutodataProperties properties;

    public Insert1800RegistrationGenerator(AutodataProperties properties) {
        this.properties = properties;
    }

    @Override public String type()        { return "voice1800.insert-registration"; }
    @Override public String displayName() { return "Tạo YCHD — Đầu số thoại 1800 (BMS v2 + SM v2)"; }
    @Override public String category()    { return "Voice 1800"; }
    @Override public String description() {
        return "Lookup số 1800 → submit IVOICE (SM v2) → submit BMS v2. Status sau khi chạy: \"Chờ BGĐ duyệt\".";
    }
    @Override public String signature() {
        return "POST {smBase}/ivoice/api/registration/get-phone-booked-number, " +
               "POST {smBase}/ivoice/api/registration/insert-registration-iframe, " +
               "POST {bmsBase}/registration/{registrationId}";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                // Auth
                ParamSpec.password("bmsToken").label("BMS Bearer token").required()
                        .hint("lấy qua generator auth.get-token-api"),
                ParamSpec.password("smToken").label("SM Bearer token").required()
                        .hint("lấy qua generator auth.get-sm-token-api — token khác BMS"),

                // Opportunity
                ParamSpec.text("opportunityId").label("Mã cơ hội").required().placeholder("CH00085565"),
                ParamSpec.number("registrationId").label("Registration ID").required()
                        .hint("số sau dấu _ trong RefCode, vd CH00085565_96735 → 96735"),
                ParamSpec.text("taxId").label("Mã số thuế KH").required().placeholder("6544859655"),
                ParamSpec.number("serviceId").label("Service ID (BMS)").required()
                        .hint("vd 100902 — lấy từ GET /registration/{id}.data.serviceId"),
                ParamSpec.text("saleName").label("Sale username (BMS)").required()
                        .placeholder("thitlh"),

                // Địa điểm triển khai (dùng chung cho IVOICE và BMS listDeployAddress)
                ParamSpec.enumOf("locationId", List.of("8", "4"))
                        .label("Tỉnh/Thành (locationId)").defaultValue("8")
                        .hint("8 = HCM, 4 = HN"),
                ParamSpec.text("locationName").label("Tên tỉnh thành").defaultValue("Thành phố Hồ Chí Minh"),
                ParamSpec.number("wardId").label("Mã Phường/Xã (wardId)").required()
                        .hint("vd 1000009697 (Phường An Đông, HCM) — lấy từ GET /bam-district-selections"),
                ParamSpec.text("wardName").label("Tên Phường/Xã").required().placeholder("Phường An Đông"),
                ParamSpec.number("districtId").label("Mã Quận/Huyện (districtId)").required()
                        .hint("vd 1000009799 — dùng trong listBillingContact"),
                ParamSpec.text("locationNumber").label("Địa chỉ (số nhà, đường)").defaultValue("22"),
                ParamSpec.text("deployDate").label("Ngày hẹn triển khai (yyyy-MM-dd)")
                        .defaultValue(LocalDate.now().toString()),

                // Liên hệ triển khai
                ParamSpec.text("contactName").label("Người liên hệ triển khai").defaultValue("qc"),
                ParamSpec.text("contactPhone").label("SĐT liên hệ triển khai").defaultValue("0345677875"),
                ParamSpec.text("contactEmail").label("Email liên hệ triển khai").defaultValue("qc@gmail.com"),

                // Billing / Tech contact (BMS)
                ParamSpec.text("billingName").label("Tên liên hệ thanh toán").defaultValue("qc"),
                ParamSpec.text("billingEmail").label("Email thanh toán").required().placeholder("vanmtt3@fpt.com"),
                ParamSpec.text("billingPhone").label("SĐT thanh toán").defaultValue("0345844589"),
                ParamSpec.text("techName").label("Tên liên hệ kỹ thuật").defaultValue("qc"),
                ParamSpec.text("techEmail").label("Email kỹ thuật").required().placeholder("vanmtt3@fpt.com"),
                ParamSpec.text("techPhone").label("SĐT kỹ thuật").defaultValue("0345855458"),

                // Tài chính HĐ (BMS)
                ParamSpec.number("feeContract").label("Cước trả một lần (VNĐ)").defaultValue(400000),
                ParamSpec.number("monthlyFeeContract").label("Cước tháng (VNĐ)").defaultValue(700000),
                ParamSpec.number("dateContractMonth").label("Kỳ hạn thanh toán (tháng)").defaultValue(1),
                ParamSpec.number("limitCreditDay").label("Hạn mức công nợ (ngày)").defaultValue(30),
                ParamSpec.text("effectiveDate").label("Ngày hiệu lực HĐ (yyyy-MM-dd)")
                        .defaultValue(LocalDate.now().minusDays(1).toString()),

                // Cấu hình đường truyền 1800 (IVOICE configInfo)
                ParamSpec.text("ipMain").label("IP kết nối (SIP)").defaultValue("1.2.2.3"),
                ParamSpec.text("portMain").label("Port SIP").defaultValue("5060"),
                ParamSpec.enumOf("ipMainType", List.of("1", "2")).label("Loại đường truyền").defaultValue("1"),
                ParamSpec.text("voiceDirectionOpen").label("Hướng cuộc gọi (mã, phân cách phẩy)")
                        .defaultValue("1,2").hint("1=Nội hạt, 2=Liên tỉnh, 3=Di động, 4=Quốc tế..."),
                ParamSpec.number("maxCall").label("Số cuộc gọi tối đa").defaultValue(4),
                ParamSpec.number("destinationNumber").label("Số đích (destinationNumber)").defaultValue(4),
                ParamSpec.number("maxValue").label("Hạn mức sử dụng (VNĐ)").defaultValue(2000000),
                ParamSpec.text("ipbK1").label("IP Backup 1 (ipbK1)").defaultValue(""),
                ParamSpec.text("portBK1").label("Port Backup 1").defaultValue(""),
                ParamSpec.text("ipbK2").label("IP Backup 2 (ipbK2)").defaultValue(""),
                ParamSpec.text("portBK2").label("Port Backup 2").defaultValue(""),

                // Số đã chọn từ UI (nếu có → bỏ qua bước auto-lookup)
                ParamSpec.text("selectedPhoneNumber").label("Số 1800 đã chọn")
                        .hint("điền sẵn từ UI sau khi tải danh sách — để trống sẽ tự chọn số đầu tiên"),
                ParamSpec.number("selectedPhoneCharge").label("Cước số đã chọn (VNĐ)").defaultValue(0)
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String bmsToken = Params.requireString(p, "bmsToken");
        String smToken  = Params.requireString(p, "smToken");
        String bmsBase  = properties.getApi().getBaseUrl().replaceAll("/$", "");
        String opportunityId   = Params.requireString(p, "opportunityId");
        int    registrationId  = (int) Params.optInt(p, "registrationId", 0);
        if (registrationId == 0) throw new GeneratorException("registrationId là bắt buộc");
        String taxId    = Params.requireString(p, "taxId");
        int    serviceId = (int) Params.optInt(p, "serviceId", 0);
        if (serviceId == 0) throw new GeneratorException("serviceId là bắt buộc");
        String saleName = Params.requireString(p, "saleName");
        String refCode  = opportunityId + "_" + registrationId;

        int    locationId   = (int) Params.optInt(p, "locationId", 8);
        String locationName = Params.optString(p, "locationName", "Thành phố Hồ Chí Minh");
        long   wardId       = Params.optInt(p, "wardId", 0);
        if (wardId == 0) throw new GeneratorException("wardId là bắt buộc");
        String wardName     = Params.requireString(p, "wardName");
        int    districtId   = (int) Params.optInt(p, "districtId", 0);
        if (districtId == 0) throw new GeneratorException("districtId là bắt buộc");
        String locationNumber = Params.optString(p, "locationNumber", "22");
        String deployDate   = Params.optString(p, "deployDate", LocalDate.now().toString());
        String contactName  = Params.optString(p, "contactName", "qc");
        String contactPhone = Params.optString(p, "contactPhone", "0345677875");
        String contactEmail = Params.optString(p, "contactEmail", "qc@gmail.com");

        String billingName  = Params.optString(p, "billingName", "qc");
        String billingEmail = Params.requireString(p, "billingEmail");
        String billingPhone = Params.optString(p, "billingPhone", "0345844589");
        String techName     = Params.optString(p, "techName", "qc");
        String techEmail    = Params.requireString(p, "techEmail");
        String techPhone    = Params.optString(p, "techPhone", "0345855458");

        long   feeContract       = Params.optInt(p, "feeContract", 400000);
        long   monthlyFee        = Params.optInt(p, "monthlyFeeContract", 700000);
        int    dateContractMonth = (int) Params.optInt(p, "dateContractMonth", 1);
        int    limitCreditDay    = (int) Params.optInt(p, "limitCreditDay", 30);
        String effectiveDate     = Params.optString(p, "effectiveDate", LocalDate.now().minusDays(1).toString());

        String ipMain      = Params.optString(p, "ipMain", "1.2.2.3");
        String portMain    = Params.optString(p, "portMain", "5060");
        int    ipMainType  = (int) Params.optInt(p, "ipMainType", 1);
        String voiceDir    = Params.optString(p, "voiceDirectionOpen", "1,2");
        int    maxCall     = (int) Params.optInt(p, "maxCall", 4);
        int    destNumber  = (int) Params.optInt(p, "destinationNumber", 4);
        long   maxValue    = Params.optInt(p, "maxValue", 2000000);
        String ipbK1       = Params.optString(p, "ipbK1", "");
        String portBK1     = Params.optString(p, "portBK1", "");
        String ipbK2       = Params.optString(p, "ipbK2", "");
        String portBK2     = Params.optString(p, "portBK2", "");

        String selectedPhoneNumber = Params.optString(p, "selectedPhoneNumber", null);
        long   selectedPhoneCharge = Params.optInt(p, "selectedPhoneCharge", 0);

        long t0 = System.currentTimeMillis();
        List<String> log = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();

        // Step 1: Dùng số đã chọn từ UI, hoặc auto-lookup nếu chưa chọn
        String phoneNumber;
        long   subcriberCharge;
        if (selectedPhoneNumber != null && !selectedPhoneNumber.isBlank()) {
            phoneNumber = selectedPhoneNumber;
            subcriberCharge = selectedPhoneCharge > 0 ? selectedPhoneCharge : 400000;
            trace(log, "Dùng số 1800 đã chọn từ UI: " + phoneNumber + " (charge=" + subcriberCharge + ")");
        } else {
            String phoneLookupUrl = SM_BASE + "/ivoice/api/registration/get-phone-booked-number";
            trace(log, "POST " + phoneLookupUrl + " (auto-lookup số 1800, taxId=" + taxId + ")");
            ObjectNode phoneLookupBody = MAPPER.createObjectNode();
            phoneLookupBody.put("serviceType", SERVICE_TYPE);
            phoneLookupBody.put("locationId", -1);
            phoneLookupBody.put("taxID", taxId);
            phoneLookupBody.put("regDetailId", -1);
            JsonNode phoneResponse = postJson(client, phoneLookupUrl, smToken, toJson(phoneLookupBody), log);
            JsonNode phoneList = phoneResponse != null ? phoneResponse.path("data") : null;
            if (phoneList == null || !phoneList.isArray() || phoneList.isEmpty()) {
                throw new GeneratorException("Không tìm được số 1800 available cho taxId=" + taxId);
            }
            JsonNode chosenPhone = phoneList.get(0);
            phoneNumber    = chosenPhone.path("phoneNumber").asText();
            subcriberCharge = chosenPhone.path("subcriberCharge").asLong(400000);
            trace(log, "Auto-chọn số 1800 đầu tiên: " + phoneNumber + " (charge=" + subcriberCharge + ")");
        }

        // Step 2: Submit IVOICE (SM v2) registration
        String ivoiceDeployDate = deployDate + "T00:00:00.000Z";
        ObjectNode configInfo = MAPPER.createObjectNode();
        configInfo.put("id", 0);
        configInfo.put("connectType", 1);
        configInfo.put("ipMain", ipMain);
        configInfo.put("portMain", portMain);
        configInfo.put("ipMainType", ipMainType);
        configInfo.put("voiceDirectionOpen", voiceDir);
        configInfo.put("ipbK1", ipbK1);
        configInfo.put("portBK1", portBK1.isEmpty() ? "8080" : portBK1);
        configInfo.put("ipbK1Type", 1);
        configInfo.put("ipbK2", ipbK2);
        configInfo.put("portBK2", portBK2.isEmpty() ? "8080" : portBK2);
        configInfo.put("ipbK2Type", 1);
        configInfo.put("portMedia", "20");
        configInfo.put("ipMediaType", 1);
        configInfo.put("forwardType", "1");
        configInfo.put("destinationNumber", destNumber);
        configInfo.put("delayTime", 0);
        configInfo.put("maxCall", maxCall);
        configInfo.put("allowHighCost", "No");
        configInfo.put("maxValue", maxValue);
        configInfo.put("packageProfile", 1);

        ObjectNode phoneRow = MAPPER.createObjectNode();
        phoneRow.put("id", 0);
        phoneRow.put("regDetailId", 1);
        phoneRow.put("phoneNumber", phoneNumber);
        phoneRow.put("subcriberCharge", subcriberCharge);
        phoneRow.putNull("vipPhoneCharge");
        phoneRow.putNull("bookingRequestId");
        phoneRow.putNull("callDirectionId");
        phoneRow.putNull("objID");

        ObjectNode regDetail = MAPPER.createObjectNode();
        regDetail.put("id", 1);
        regDetail.put("isNew", 1);
        regDetail.put("serviceGroup", SERVICE_GROUP);
        regDetail.put("serviceType", SERVICE_TYPE);
        regDetail.put("locationId", locationId);
        regDetail.put("locationName", locationName);
        regDetail.put("wardId", wardId);
        regDetail.put("wardName", wardName);
        regDetail.put("locationNumber", locationNumber);
        regDetail.put("deployDate", ivoiceDeployDate);
        regDetail.put("autoCall", 0);
        regDetail.put("contactName", contactName);
        regDetail.put("contactEmail", contactEmail);
        regDetail.put("contactPhone", contactPhone);
        regDetail.set("configInfo", configInfo);
        regDetail.set("phones", MAPPER.createArrayNode().add(phoneRow));
        regDetail.set("cpPercents", MAPPER.createArrayNode());

        ObjectNode ivoiceBody = MAPPER.createObjectNode();
        ivoiceBody.put("id", 0);
        ivoiceBody.put("opportunityCode", opportunityId);
        ivoiceBody.put("refCode", refCode);
        ivoiceBody.put("contractBMS", "");
        ivoiceBody.put("serviceGroup", SERVICE_GROUP);
        ivoiceBody.put("serviceType", SERVICE_TYPE);
        ivoiceBody.put("taxId", taxId);
        ivoiceBody.put("promotionJson", "[]");
        ivoiceBody.put("deviceJson", "[]");
        ivoiceBody.put("switchboardJson", "[]");
        ivoiceBody.put("cp1900Json", "[]");
        ivoiceBody.put("priceJson", "[]");
        ivoiceBody.set("registrationDetails", MAPPER.createArrayNode().add(regDetail));

        String ivoiceInsertUrl = SM_BASE + "/ivoice/api/registration/insert-registration-iframe";
        trace(log, "POST " + ivoiceInsertUrl + " refCode=" + refCode + " phone=" + phoneNumber);
        JsonNode ivoiceResult = postJson(client, ivoiceInsertUrl, smToken, toJson(ivoiceBody), log);

        // Step 3: Submit BMS v2 registration (final, isUpdateTemp=false)
        String effectiveDatetime = effectiveDate + "T00:00:00";
        long contractValue = feeContract + monthlyFee;

        ObjectNode registration = MAPPER.createObjectNode();
        registration.put("typeDocContract", 1);
        registration.put("paymentMethod", 2);
        registration.put("location", locationId);
        registration.put("typeContract", 1);
        registration.putNull("eContractFlag");
        registration.putNull("agencyContractFlag");
        registration.putNull("onSiteWarranty");
        registration.putNull("autoRenewContract");
        registration.putNull("isDebt");
        registration.put("feeContract", feeContract);
        registration.put("monthlyFeeContract", monthlyFee);
        registration.put("contractValue", contractValue);
        registration.put("dateContractMonth", dateContractMonth);
        registration.put("paymentTerm", 1);
        registration.put("effectiveContractDate", effectiveDatetime);
        registration.put("limitCreditDay", limitCreditDay);
        registration.putNull("notes");
        registration.putNull("description");
        registration.putNull("debtLimitDayCount");
        registration.putNull("debtApprovalEmail");
        registration.putNull("debtUpdateDate");
        registration.putNull("debtDateLimit");
        registration.putNull("startContractDate");
        registration.putNull("endContractDate");
        registration.put("contractValueCurrency", "VND");
        registration.putNull("status");
        registration.put("saleName", saleName);
        registration.putNull("contactName");
        registration.putNull("contactEmail");
        registration.putNull("contactPhone");
        registration.putNull("billingAddress");
        registration.putNull("contactDeployName");
        registration.putNull("contactDeployEmail");
        registration.putNull("contactDeployPhone");
        registration.putNull("preSigner");
        registration.putNull("ftiRepresentative");
        registration.putNull("subContractTemp");
        registration.putNull("emailCusToReceiveContract");
        registration.putNull("ftiLegal");
        registration.putNull("techContact");
        registration.putNull("techEmail");
        registration.putNull("techNumber");
        registration.putNull("subContractNo");
        registration.put("serviceId", serviceId);
        registration.put("contractNo", "");
        registration.set("packServiceIds", MAPPER.createArrayNode());

        ObjectNode billingContact = MAPPER.createObjectNode();
        billingContact.put("contactName", billingName);
        billingContact.put("contactEmail", billingEmail);
        billingContact.put("contactPhone", billingPhone);
        billingContact.put("location", locationId);
        billingContact.putNull("locationName");
        billingContact.put("district", districtId);
        billingContact.putNull("districtName");
        billingContact.putNull("ward");
        billingContact.putNull("wardName");
        billingContact.put("address", locationNumber);
        billingContact.put("country", "vn");

        ObjectNode techContact = MAPPER.createObjectNode();
        techContact.put("name", techName);
        techContact.put("email", techEmail);
        techContact.put("phone", techPhone);

        ObjectNode deployAddress = MAPPER.createObjectNode();
        deployAddress.put("address", locationNumber);
        String fullAddr = locationNumber + ", " + wardName + ", " + locationName;
        deployAddress.put("fullAddress", fullAddr);
        deployAddress.put("location", locationId);
        deployAddress.put("district", wardId); // wardId used as district in deploy address
        deployAddress.put("contactName", contactName);
        deployAddress.put("contactEmail", contactEmail);
        deployAddress.put("contactPhone", contactPhone);
        deployAddress.putNull("refCodeIntegrated");
        deployAddress.put("isAddressOfIntegrated", true);
        deployAddress.put("isSelected", true);

        ObjectNode memberRow = MAPPER.createObjectNode();
        memberRow.put("name", saleName);
        memberRow.put("position", 1);
        memberRow.put("contractRate", 100);

        ObjectNode bmsBody = MAPPER.createObjectNode();
        bmsBody.set("registration", registration);
        bmsBody.set("listBillingContact", MAPPER.createArrayNode().add(billingContact));
        bmsBody.set("listTechContact", MAPPER.createArrayNode().add(techContact));
        bmsBody.set("listDeployAddress", MAPPER.createArrayNode().add(deployAddress));
        bmsBody.put("isUpdateTemp", false);
        bmsBody.put("appCodeInsert", "FTIBMS");
        bmsBody.put("opportunityId", opportunityId);
        bmsBody.set("listEContractDocument", MAPPER.createArrayNode());
        bmsBody.set("listCooperationAgreementDocument", MAPPER.createArrayNode());
        bmsBody.set("listMember", MAPPER.createArrayNode().add(memberRow));
        bmsBody.set("listServiceTypeOfPack", MAPPER.createArrayNode());

        String bmsUrl = bmsBase + "/registration/" + registrationId;
        trace(log, "POST " + bmsUrl + " opportunityId=" + opportunityId + " isUpdateTemp=false");
        JsonNode bmsResult = postJson(client, bmsUrl, bmsToken, toJson(bmsBody), log);

        return result()
                .put("refCode", refCode)
                .put("phoneNumber", phoneNumber)
                .put("subcriberCharge", subcriberCharge)
                .put("registrationId", registrationId)
                .put("opportunityId", opportunityId)
                .put("status", "Chờ BGĐ duyệt")
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private JsonNode postJson(HttpClient client, String url, String bearerToken, String body, List<String> log) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(client, request, log);
    }

    private JsonNode send(HttpClient client, HttpRequest request, List<String> log) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            trace(log, "network error: " + e.getMessage());
            throw new GeneratorException("Không kết nối được " + request.uri(), HttpStatus.BAD_GATEWAY, e);
        }
        trace(log, "-> HTTP " + response.statusCode());
        JsonNode parsed = parseQuietly(response.body());
        if (response.statusCode() / 100 != 2) {
            trace(log, "response: " + response.body());
            HttpStatus st = HttpStatus.resolve(response.statusCode());
            throw new GeneratorException(
                    "API trả HTTP " + response.statusCode() + ": " + response.body(),
                    st != null ? st : HttpStatus.BAD_GATEWAY, null);
        }
        return parsed;
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new GeneratorException("Serialize JSON lỗi", e); }
    }

    private JsonNode parseQuietly(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); }
        catch (Exception e) { return null; }
    }

    private GenerationResult.Builder result() { return GenerationResult.builder(type()); }

    private void trace(List<String> log, String line) {
        log.add("[" + LocalDateTime.now().format(TS) + "] " + line);
    }
}
