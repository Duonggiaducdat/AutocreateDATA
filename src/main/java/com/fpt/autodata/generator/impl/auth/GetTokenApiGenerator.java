package com.fpt.autodata.generator.impl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.selenium.LoginPage;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.openqa.selenium.Cookie;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lấy BMS Bearer Token qua pure HTTP — không cần Selenium.
 *
 * Flow (reverse-engineered 2026-09-12 qua Playwright network capture):
 *
 *   Fresh login (chưa có TGT):
 *     1. GET  /id/login?service=...  → parse execution="e1s1", server set TGT cookie
 *     2. POST /id/login?service=...  → 302 + ticket + TGT cookie trong Set-Cookie
 *     3. POST /api/bam/auth/sso      → accessToken
 *
 *   Token refresh (đã có TGT):
 *     1. GET  /id/login?service=...  → 302 + ticket ngay (~67ms)
 *     2. POST /api/bam/auth/sso      → accessToken mới
 *
 * TGT cookie được cache in-memory theo username để tái sử dụng.
 * Thay thế auth.get-token (Selenium ~12-15s) bằng pure HTTP (~1-2s).
 */
@Component
public class GetTokenApiGenerator implements Generator {

    private static final String BASE            = "https://ftms-stag.fpt.net";
    private static final String CAS_LOGIN       = BASE + "/id/login";
    // SSO endpoint của BMS service backend — confirmed live 2026-09-12 bằng network capture khi bmsv2 SPA bootstrap
    private static final String SSO_URL         = "https://ftisapi-stag.fpt.net/bms-service/api/auth/sso";
    // service URL phải có path /bmsv2/ — đây là service CAS của bmsv2 SPA, không phải root domain
    private static final String SERVICE_ENCODED = "https%3A%2F%2Fftms-stag.fpt.net%2Fbmsv2%2F";
    private static final String LOGIN_URL       = CAS_LOGIN + "?service=" + SERVICE_ENCODED;

    private static final Pattern EXECUTION_PAT = Pattern.compile("name=\"execution\"\\s+value=\"([^\"]+)\"");
    private static final Pattern TICKET_PAT    = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    // username → CookieManager (giữ TGT cookie giữa các lần gọi)
    

    private static final List<String> TOKEN_COOKIE_CANDIDATES = List.of("BMS_access_token", "access_token");
    private static final String BMSV2_URL  = "https://ftms-stag.fpt.net/bmsv2";
    private static final int    POLL_MAX   = 30;
    private static final long   POLL_MS    = 1_000;

    private final ObjectMapper    mapper;
    private final Duration        timeout;
    private final FtmsAutomation  automation;
    private final CasTgtCache     tgtCache;

    public GetTokenApiGenerator(AutodataProperties props, FtmsAutomation automation, CasTgtCache tgtCache) {
        this.timeout    = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.automation = automation;
        this.tgtCache   = tgtCache;
        this.mapper     = new ObjectMapper();
    }

    @Override public String type()        { return "auth.get-token-api"; }
    @Override public String displayName() { return "Lấy Token · Pure API (không Selenium)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login → POST credentials → POST /api/bam/auth/sso. " +
               "Nhanh ~10x so với Selenium. TGT cookie được cache để refresh không cần nhập lại credentials.";
    }
    @Override public String signature() { return "login(username, password) → accessToken (JWT ~1h)"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
            ParamSpec.text("username").required()
                .label("Username / Email")
                .placeholder("vd: ThiTLH@fpt.com"),
            ParamSpec.password("password").required()
                .label("Password / OTP")
                .defaultValue("ISC22QC")
                .hint("mã bypass OTP trên STG")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");
        long t0 = System.currentTimeMillis();

        // ── Lấy hoặc tạo CookieManager cho account này ──────────────────────
        CookieManager cookies = tgtCache.getOrCreate(username);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET — nếu có TGT sẽ trả 302 + ticket ngay ───────────────
        String ticket = null;
        String execution = "e1s1";
        boolean usedTgt = false;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(LOGIN_URL))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 302) {
                // TGT còn hợp lệ → có ticket ngay
                String loc = resp.headers().firstValue("location").orElse("");
                Matcher m = TICKET_PAT.matcher(loc);
                if (m.find()) {
                    ticket  = m.group(1);
                    usedTgt = true;
                }
            } else if (resp.statusCode() == 200) {
                // Cần full login — parse execution token
                Matcher m = EXECUTION_PAT.matcher(resp.body());
                if (m.find()) execution = m.group(1);
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 1 GET /id/login thất bại: " + e.getMessage(), e);
        }

        // ── Step 2: POST credentials — chỉ khi chưa có ticket từ TGT ────────
        if (ticket == null) {
            String formBody = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&execution=" + URLEncoder.encode(execution, StandardCharsets.UTF_8)
                + "&_eventId=submit"
                + "&language=vi"
                + "&countLogin=0"
                + "&hasError=0"
                + "&errorMsg=";

            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", LOGIN_URL)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 302) {
                    tgtCache.invalidate(username);
                    if (resp.statusCode() == 401) {
                        // Rate-limited → tự động fallback sang Selenium
                        return loginViaSelenium(username, password, t0);
                    }
                    throw new GeneratorException(
                        "Step 2 POST thất bại — server trả " + resp.statusCode()
                        + ". Kiểm tra lại username/password.");
                }

                String loc = resp.headers().firstValue("location").orElse("");
                Matcher m = TICKET_PAT.matcher(loc);
                if (!m.find()) {
                    tgtCache.invalidate(username);
                    throw new GeneratorException(
                        "Step 2: không parse được ticket từ Location: " + loc);
                }
                ticket = m.group(1);
                // CookieManager đã tự lưu TGT cookie từ Set-Cookie header
            } catch (GeneratorException e) {
                throw e;
            } catch (Exception e) {
                throw new GeneratorException("Step 2 POST /id/login thất bại: " + e.getMessage(), e);
            }
        }

        // ── Step 3: Exchange ticket → accessToken ────────────────────────────
        // service phải URL-encoded trong JSON body — confirmed live 2026-09-12 từ network capture bmsv2 SPA
        String ssoBody = String.format("{\"token\":\"%s\",\"service\":\"%s\"}", ticket, SERVICE_ENCODED);
        String accessToken, refreshToken, userName;

        try {
            HttpClient ssoClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SSO_URL))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Origin", BASE)
                .header("Referer", BASE + "/")
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();

            HttpResponse<String> resp = ssoClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());

            // bms-service/api/auth/sso trả {"data":{...},"success":true} — khác format cũ {"status":"OK","data":{...}}
            boolean ok = resp.statusCode() == 200 &&
                (root.path("success").asBoolean(false) || "OK".equals(root.path("status").asText()));
            if (!ok) {
                throw new GeneratorException("Step 3 auth/sso thất bại: " + resp.body());
            }

            JsonNode data = root.path("data");
            accessToken  = data.path("accessToken").asText();
            refreshToken = data.path("refreshToken").asText(null);
            // bms-service endpoint trả "username" thay vì "userName"
            userName     = data.path("userName").asText(data.path("username").asText());
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Step 3 POST /api/bam/auth/sso thất bại: " + e.getMessage(), e);
        }

        return GenerationResult.builder(type())
            .put("username", userName)
            .put("accessToken", accessToken)
            .put("refreshToken", refreshToken)
            .put("ticket", ticket)
            .put("usedTgtCache", usedTgt)
            .put("method", "api")
            .put("durationMs", System.currentTimeMillis() - t0)
            .build();
    }

    /** Fallback khi API bị rate-limit (401): dùng Selenium login để lấy token. */
    private GenerationResult loginViaSelenium(String username, String password, long t0) {
        String token = automation.computeWithDriver(driver -> {
            new LoginPage(driver).login(username, password);
            driver.get(BMSV2_URL);
            for (int i = 0; i < POLL_MAX; i++) {
                for (String name : TOKEN_COOKIE_CANDIDATES) {
                    Cookie c = driver.manage().getCookieNamed(name);
                    if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                        return c.getValue();
                    }
                }
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            throw new GeneratorException("Selenium fallback: không tìm được token cookie sau " + POLL_MAX + "s");
        });

        return GenerationResult.builder(type())
            .put("username", username)
            .put("accessToken", token)
            .put("usedTgtCache", false)
            .put("method", "selenium-fallback (api rate-limited)")
            .put("durationMs", System.currentTimeMillis() - t0)
            .build();
    }
}
