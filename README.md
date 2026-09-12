# AutocreateDATA — FTMS Test Data Generator

Web app tạo dữ liệu test tự động cho hệ thống FTMS (FPT Telecom Management System).  
Backend: **Spring Boot 3 + Selenium**. Frontend: **SPA tĩnh** phục vụ tại cùng port.

---

## Chạy nhanh

```bash
mvn spring-boot:run
# → http://localhost:8080
```

Yêu cầu: Java 17+, Maven 3.8+, Google Chrome (ChromeDriver tự tải qua WebDriverManager).

---

## Cấu trúc source code

```
src/main/java/com/fpt/autodata/
│
├── AutoDataApplication.java          # Entry point Spring Boot
│
├── controller/
│   ├── GenerationController.java     # REST API: /api/health, /api/generators,
│   │                                 #           /api/generate, /api/proxy, /api/services, /api/log
│   └── GlobalExceptionHandler.java   # Xử lý lỗi toàn app → JSON response chuẩn
│
├── generator/                        # Framework — KHÔNG sửa khi thêm script mới
│   ├── Generator.java                # Interface mọi script phải implement
│   ├── GenerationResult.java         # Object kết quả trả về
│   ├── GeneratorException.java       # Exception chuẩn của framework
│   ├── ParamSpec.java                # Định nghĩa 1 param (tên, kiểu, label, default)
│   ├── ParamType.java                # Enum: TEXT | PASSWORD | ENUM | BOOLEAN | NUMBER | ...
│   ├── Params.java                   # Helper đọc param từ Map (requireString, optInt...)
│   └── impl/
│       ├── AbstractGenerator.java    # Base class Selenium: quản lý HTTP client + log
│       │
│       ├── auth/                     # [SHARED] Selenium login — lấy token/session
│       ├── customer/                 # [SHARED] Quản lý khách hàng qua CM
│       ├── opportunity/              # [SHARED] Vòng đời cơ hội BMS
│       ├── registration/             # [SHARED] Duyệt Hợp đồng / Phụ lục
│       └── service/                  # [PER-SERVICE] Scripts riêng từng loại dịch vụ
│           ├── ivoice/               #   IVOICE — login: sm/v2
│           ├── sm/                   #   SM     — login: sm/v2
│           ├── mes/                  #   MES    — login: sm/v2
│           ├── idc/                  #   IDC    — login: /idc  (apis-stag.fpt.net)
│           └── ll/                   #   LL     — login: /ll   (session cookie)
│
├── service/
│   ├── GenerationService.java        # Điều phối thực thi: nhận request → gọi generator
│   ├── GeneratorRegistry.java        # Auto-discover @Component Generator beans
│   └── automation/
│       ├── FtmsAutomation.java       # Facade Selenium: cấp driver → chạy → đóng, chụp ảnh lỗi
│       └── WebDriverFactory.java     # Tạo ChromeDriver headless theo config
│
├── selenium/                         # Page Objects Selenium
│   ├── BasePage.java                 # Base class: click, sendKeys, wait, scroll...
│   └── LoginPage.java                # Màn hình đăng nhập FTMS
│
├── audit/
│   ├── AuditEntry.java               # Data class 1 dòng log
│   └── AuditService.java             # Ghi/đọc audit.jsonl → Activity Log trên UI
│
├── config/
│   ├── AutodataProperties.java       # Bind application.yml → Java object
│   ├── WebConfig.java                # CORS + forward /ivoice → index.html
│   └── WebDriverConfig.java          # Khởi tạo ChromeDriver manager lúc startup
│
├── model/
│   ├── GenerateRequest.java          # Request body: POST /api/generate
│   ├── GenerateResponse.java         # Response body: POST /api/generate
│   ├── GeneratorMetadata.java        # Metadata 1 generator → GET /api/generators
│   ├── ServiceGroup.java             # DTO parse Services.json
│   └── ApiError.java                 # Response shape khi lỗi HTTP
│
└── util/
    └── JsonUtil.java                 # Load file JSON từ classpath (Services.json...)
```

---

## Generators hiện có

### `impl/auth/` — Xác thực

**Login mapping theo domain:**

| App | Domain | SSO endpoint |
|---|---|---|
| BMS | `ftms-stag.fpt.net/bmsv2/` | `ftms-stag.fpt.net/api/bam/auth/sso` |
| SM/IVOICE/MES | `ftms-stag.fpt.net/sm/v2/` | `ftisapi-stag.fpt.net/sm/v2/api/auth/sso/login` |
| IDC | `ftms-stag.fpt.net/idc` | `apis-stag.fpt.net/idc/api/auth/sso` |
| CM | `ftms-stag.fpt.net/cm/` | `ftms-stag.fpt.net/gwcm/api/Auth/LoginSSO` |
| LL | `ftms-stag.fpt.net/ll` | server-side cookie (SignInCas pattern) |
| IVOICE old | `ftms-stag.fpt.net/ivoice/` | server-side cookie (IVOICE_USER_*) |
| BMS Legacy | `ftms-stag.fpt.net/bms/` | server-side cookie (FTIBMS-Auth) |

---

**BMS (bmsv2):**

| Class | API type | Cách login | Mô tả |
|---|---|---|---|
| `GetTokenApiGenerator` | `auth.get-token-api` | **Pure API** (~1-2s) | GET /id/login → POST form → POST /api/bam/auth/sso → JWT BMS. TGT cache cho refresh. |

**SM/v2 (IVOICE mới, SM, MES và các dịch vụ trên sm/v2):**

| Class | API type | Cách login | Mô tả |
|---|---|---|---|
| `GetSmTokenApiGenerator` | `auth.get-sm-token-api` | **Pure API** (~2-3s) | TGT → GET /id/login?service=/sm/v2/ → POST ftisapi/sm/v2/api/auth/sso/login → JWT SM. |

**IDC và LL:**

| Class | API type | Mô tả |
|---|---|---|
| `GetIdcTokenApiGenerator` | `auth.get-idc-token-api` | TGT → POST apis-stag/idc/api/auth/sso → JWT IDC |
| `GetLlSessionApiGenerator` | `auth.get-ll-session-api` | TGT → /ll/account/signincas → LL session cookies |

**Các app riêng (vẫn dùng Selenium khi chưa có TGT):**

| Class | API type | Cách login | Mô tả |
|---|---|---|---|
| `GetCmTokenApiGenerator` | `auth.get-cm-token-api` | API+fallback | TGT → /gwcm/api/Auth/LoginSSO → JWT CM. Fallback Selenium nếu chưa có TGT. |
| `GetIvoiceSessionApiGenerator` | `auth.get-ivoice-session-api` | API+fallback | TGT → GET /ivoice/?1=1&ticket → IVOICE cookies. Fallback Selenium. |
| `GetBmsLegacySessionApiGenerator` | `auth.get-bms-legacy-session-api` | API+fallback | TGT → /bms/Account/SignInCas → FTIBMS-Auth cookie. Fallback Selenium. |
| `GetCmTokenGenerator` | `auth.get-cm-token` | Selenium | Selenium fallback cho CM |
| `GetIvoiceSessionGenerator` | `auth.get-ivoice-session` | Selenium | Selenium fallback cho IVOICE |
| `GetBmsLegacySessionGenerator` | `auth.get-bms-legacy-session` | Selenium | Selenium fallback cho BMS Legacy |

### `impl/customer/` — Khách hàng

| Class | API type | Mô tả |
|---|---|---|
| `CreateRandomCustomerGenerator` | `customer.create-random` | Tạo khách hàng ngẫu nhiên qua CM API |

### `impl/opportunity/` — Vòng đời cơ hội

| Class | API type | Mô tả |
|---|---|---|
| `OpportunityApiGenerator` | `opportunity.api` | Tạo cơ hội qua REST API |
| `EvaluateOpportunityGenerator` | `evaluation.evaluate` | TPP đánh giá cơ hội |
| `ApproveOpportunityGenerator` | `evaluation.approve` | BGĐ phê duyệt cơ hội |
| `QuotationGenerator` | `opportunity.quotation` | Tạo báo giá đính kèm dummy.pdf |
| `StageAdvanceGenerator` | `opportunity.stage-advance` | Chuyển hết vòng đời cơ hội (tiến độ 100%) |
| `StageNoteGenerator` | `opportunity.stage-note` | Ghi chú vào tất cả giai đoạn |

### `impl/registration/` — Duyệt Hợp đồng / Phụ lục

| Class | API type | Mô tả |
|---|---|---|
| `LookupRegistrationGenerator` | `registration.lookup` | Tra Mã YC → tìm opportunityId + BGĐ phụ trách |
| `ApproveRegistrationGenerator` | `registration.approve-bms` | BGĐ duyệt HĐ/PL bên BMS |
| `ApproveContractCmGenerator` | `registration.approve-cm` | Duyệt HĐ/PL bên CM app |

### `impl/service/mes/` — MES

| Class | API type | Mô tả |
|---|---|---|
| *(chưa có scripts)* | — | Dùng `auth.get-sm-token-api` để login |

### `impl/service/ivoice/` — Đấu số thoại

| Class | API type | Mô tả |
|---|---|---|
| `LookupOpportunityForIvoiceGenerator` | `ivoice.lookup-opportunity` | Từ mã cơ hội → tìm RefCode, Mã KH, RegistrationId |
| `GetIvoiceWardGenerator` | `ivoice.get-ward` | Lấy danh sách Phường/Xã theo tỉnh thành |
| `GetIvoicePhoneBookedGenerator` | `ivoice.get-phone-booked` | Tìm số điện thoại đã book khả dụng |
| `InsertIvoiceRegistrationGenerator` | `ivoice.create-registration-detail` | Tạo phiếu triển khai chi tiết (IVOICE) |
| `CheckServiceIntegrateGenerator` | `ivoice.check-service-integrate` | Pre-check tích hợp dịch vụ trước khi Tạo |
| `InsertBmsRegistrationGenerator` | `ivoice.insert-registration` | Tạo HĐ/PL Bước 3 (BMS legacy — chỉ dùng cho IVOICE) |

---

## Thêm script mới

### Bước 1 — Xác định script thuộc nhóm nào

```
Dùng chung mọi dịch vụ?
  ├── Xác thực / login           →  impl/auth/
  ├── Quản lý khách hàng         →  impl/customer/
  ├── Tạo / xử lý cơ hội BMS    →  impl/opportunity/
  └── Duyệt HĐ/PL               →  impl/registration/

Riêng 1 loại dịch vụ?
  └── impl/service/<tên>/
        ivoice/    IVOICE — login: auth.get-sm-token-api
        sm/        SM     — login: auth.get-sm-token-api
        mes/       MES    — login: auth.get-sm-token-api
        idc/       IDC    — login: auth.get-idc-token-api
        ll/        LL     — login: auth.get-ll-session-api
```

### Bước 2 — Tạo class, implement interface `Generator`

```java
package com.fpt.autodata.generator.impl.service.ll; // đặt đúng package

import com.fpt.autodata.generator.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component  // bắt buộc — Spring tự đăng ký, không cần wiring thêm
public class MyLlGenerator implements Generator {

    @Override public String type()        { return "ll.my-action"; }
    @Override public String displayName() { return "Tên hiển thị trên UI"; }
    @Override public String category()    { return "LL"; }
    @Override public String description() { return "Mô tả ngắn."; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
            ParamSpec.password("bearerToken").label("Bearer Token").required(),
            ParamSpec.text("vmName").label("Tên VM").required(),
            ParamSpec.number("cpuCores").label("CPU (cores)").defaultValue("2")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String token = Params.requireString(p, "bearerToken");
        String param = Params.requireString(p, "vmName");

        // ... gọi API ...

        return GenerationResult.builder(type())
            .put("result", "ok")
            .build();
    }
}
```

**Xong.** Restart app → generator tự xuất hiện ở `GET /api/generators` và trên UI.

### Bước 3 — Nếu script cần Selenium

Extend `AbstractGenerator` thay vì implement `Generator` trực tiếp:

```java
import com.fpt.autodata.generator.impl.AbstractGenerator;
import com.fpt.autodata.selenium.LoginPage;
import com.fpt.autodata.service.automation.FtmsAutomation;

@Component
public class MySeleniumGenerator extends AbstractGenerator {

    public MySeleniumGenerator(FtmsAutomation automation) {
        super(automation);
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");
        List<String> log = newLog();

        automation.withDriver(driver -> {
            new LoginPage(driver).login(username, password);
            // ... thao tác Selenium ...
            trace(log, "done");
        });

        return result().log(log).build();
    }
}
```

---

## Kiểu param hỗ trợ

| `ParamType` | Builder helper | Hiển thị trên UI |
|---|---|---|
| `TEXT` | `ParamSpec.text("name")` | Input text |
| `PASSWORD` | `ParamSpec.password("token")` | Input password (ẩn) |
| `NUMBER` | `ParamSpec.number("count")` | Input number |
| `ENUM` | `ParamSpec.enumOf("type", List.of(...))` | Dropdown |
| `MULTI_ENUM` | `ParamSpec.multiEnum("services", List.of(...))` | Multi-select |
| `BOOLEAN` | `ParamSpec.bool("headless")` | Checkbox |

---

## REST API

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/health` | Liveness check |
| `GET` | `/api/generators` | Danh sách tất cả generators + params |
| `POST` | `/api/generate` | Chạy 1 generator: `{"type":"...", "params":{...}}` |
| `GET` | `/api/services` | Danh sách nhóm/loại dịch vụ từ Services.json |
| `GET` | `/api/proxy/**` | Proxy GET đến BMS API (tự đính kèm Authorization header) |
| `GET` | `/api/log?n=50` | Xem lịch sử chạy gần nhất |

---

## Cấu hình (`application.yml`)

```yaml
autodata:
  webdriver:
    headless: true              # false để xem Selenium chạy trực quan
    base-url: https://ftms-stag.fpt.net
    screenshot-on-error: true   # chụp ảnh màn hình khi Selenium lỗi
    screenshot-dir: screenshots

  api:
    base-url: https://ftisapi-stag.fpt.net/bms-service/api
    timeout-seconds: 15

  defaults:
    reviewer-username: chuyennt5@fpt.com
    reviewer-password: ISC22QC

  cors:
    allowed-origins:
      - http://localhost:5173
      - http://localhost:3000
```
