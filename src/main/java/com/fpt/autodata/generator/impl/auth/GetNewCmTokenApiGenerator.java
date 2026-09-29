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
import org.openqa.selenium.JavascriptExecutor;
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
 * Lấy new-CM Bearer Token qua pure HTTP — dùng cho new-cm v3 API
 * ({@code ftms-stag.fpt.net/new-cm}, backend {@code ftisapi-stag.fpt.net/api/cm}).
 *
 * <p>Flow (captured live 2026-09-15 từ tab new-cm/registration/CMR2614715):
 * <ol>
 *   <li>GET /id/login?service=https://ftms-stag.fpt.net/new-cm (TGT cache)
 *       → 302 + ST ticket</li>
 *   <li>POST https://ftisapi-stag.fpt.net/api/cm/auth/sso
 *       body: {@code {"token":"ST-XXXXX","service":"https%3A%2F%2Fftms-stag.fpt.net%2Fnew-cm"}}
 *       → data.token (new-CM JWT)</li>
 * </ol>
 *
 * <p>Khác biệt so với old-CM ({@link GetCmTokenApiGenerator}):
 * <ul>
 *   <li>SSO URL: {@code /api/cm/auth/sso} thay vì {@code /gwcm/api/Auth/LoginSSO}</li>
 *   <li>Body field: {@code token} (không phải {@code ticket}) và {@code service} URL-encoded</li>
 *   <li>Service URL: {@code /new-cm} (không có trailing slash, không phải {@code /cm/})</li>
 *   <li>KHÔNG cần header "ticket" cho mutation calls — standard Bearer token là đủ</li>
 * </ul>
 *
 * <p>Dùng TGT cache từ {@link CasTgtCache} — gọi {@code auth.get-token-api} trước
 * để seed TGT, sau đó generator này tái dùng TGT đó cho new-cm service.</p>
 */
@Component
public class GetNewCmTokenApiGenerator implements Generator {

    private static final String FTMS_BASE              = "https://ftms-stag.fpt.net";
    private static final String NEW_CM_SERVICE         = FTMS_BASE + "/new-cm";
    // SSO body cần URL-encoded form (captured live — server từ chối plain URL)
    private static final String NEW_CM_SERVICE_ENCODED = "https%3A%2F%2Fftms-stag.fpt.net%2Fnew-cm";
    private static final String CAS_URL  = FTMS_BASE + "/id/login?service=" + NEW_CM_SERVICE;
    private static final String SSO_URL  = "https://ftisapi-stag.fpt.net/api/cm/auth/sso";
    private static final Pattern TICKET_PAT    = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");
    private static final Pattern EXECUTION_PAT = Pattern.compile("name=\"execution\"\\s+value=\"([^\"]+)\"");

    private final ObjectMapper   mapper     = new ObjectMapper();
    private final Duration       timeout;
    private final CasTgtCache    tgtCache;
    private final FtmsAutomation automation;

    public GetNewCmTokenApiGenerator(AutodataProperties props, CasTgtCache tgtCache, FtmsAutomation automation) {
        this.timeout    = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache   = tgtCache;
        this.automation = automation;
    }

    @Override public String type()        { return "auth.get-new-cm-token"; }
    @Override public String displayName() { return "Lấy Token new-CM v3 · Pure API"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login?service=/new-cm (TGT cache) → POST /api/cm/auth/sso → new-CM JWT. " +
               "Dùng cho voice1800.cm-execute và mọi API tại ftisapi-stag.fpt.net/api/cm/*.";
    }
    @Override public String signature() {
        return "GET /id/login?service=new-cm → POST ftisapi/api/cm/auth/sso → cmToken (JWT)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
            ParamSpec.text("username").required()
                .label("Username / Email")
                .placeholder("vd: ThiTLH@fpt.com"),
            ParamSpec.password("password").required()
                .label("Password / OTP")
                .defaultValue("ISC22QC")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        long t0 = System.currentTimeMillis();

        CookieManager cookies = tgtCache.getOrCreate(username);
        HttpClient clientWithCookies = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CAS ticket cho new-cm service ──────────────────────
        String ticket = null;
        String executionFromStep1 = null;
        int step1Status = -1;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CAS_URL))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET().build();
            HttpResponse<String> resp = clientWithCookies.send(req, HttpResponse.BodyHandlers.ofString());
            step1Status = resp.statusCode();
            if (resp.statusCode() == 302) {
                Matcher m = TICKET_PAT.matcher(resp.headers().firstValue("location").orElse(""));
                if (m.find()) ticket = m.group(1);
            } else if (resp.statusCode() == 200) {
                // No TGT — parse execution token now so Step 1b GET is skipped
                Matcher m = EXECUTION_PAT.matcher(resp.body());
                if (m.find()) executionFromStep1 = m.group(1);
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 1 GET CAS new-CM thất bại: " + e.getMessage(), e);
        }

        // TGT chưa cache (hoặc auth.get-token-api dùng Selenium fallback không seed cache)
        // → tự login CAS với password để lấy ticket và đồng thời seed TGT.
        if (ticket == null) {
            String password = Params.optString(p, "password", "ISC22QC");
            // Step 1b: chỉ GET lại nếu Step 1 chưa parse được execution token
            // (e.g. Step 1 trả 200 nhưng body bị truncate hoặc không có execution field)
            String execution = executionFromStep1 != null ? executionFromStep1 : "e1s1";
            int step1bStatus = step1Status;
            if (executionFromStep1 == null) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(CAS_URL)).timeout(timeout)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET().build();
                    HttpResponse<String> r = clientWithCookies.send(req, HttpResponse.BodyHandlers.ofString());
                    step1bStatus = r.statusCode();
                    if (r.statusCode() == 302) {
                        // TGT vừa xuất hiện (race) — thử lấy ticket từ redirect
                        Matcher m = TICKET_PAT.matcher(r.headers().firstValue("location").orElse(""));
                        if (m.find()) ticket = m.group(1);
                    } else if (r.statusCode() == 200) {
                        Matcher m = EXECUTION_PAT.matcher(r.body());
                        if (m.find()) execution = m.group(1);
                    }
                } catch (Exception ignored) { /* dùng default e1s1 */ }
            }

            if (ticket == null) {
                // Step 2b: POST credentials — CookieManager tự lưu TGT Set-Cookie
                String formBody = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                    + "&execution=" + URLEncoder.encode(execution, StandardCharsets.UTF_8)
                    + "&_eventId=submit&language=vi&countLogin=0&hasError=0&errorMsg=";
                int step2bStatus = -1;
                String step2bLocation = "";
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(CAS_URL)).timeout(timeout)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "Mozilla/5.0").header("Referer", CAS_URL)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
                    HttpResponse<String> r = clientWithCookies.send(req, HttpResponse.BodyHandlers.ofString());
                    step2bStatus = r.statusCode();
                    step2bLocation = r.headers().firstValue("location").orElse("");
                    if (r.statusCode() == 302) {
                        Matcher m = TICKET_PAT.matcher(step2bLocation);
                        if (m.find()) ticket = m.group(1);
                    }
                } catch (Exception e) {
                    throw new GeneratorException("CAS POST cho new-CM thất bại: " + e.getMessage(), e);
                }
                if (ticket == null) {
                    tgtCache.invalidate(username);
                    if (step2bStatus == 401) {
                        // Rate-limited → Selenium fallback (browser không bị rate-limit như HTTP client)
                        return loginViaSeleniumForNewCm(username, password, t0);
                    }
                    throw new GeneratorException(
                        "Không lấy được CAS ticket sau khi thử full login cho new-CM. " +
                        "Step1 HTTP=" + step1Status + " Step1b HTTP=" + step1bStatus + " execution=" + execution +
                        " | Step2b HTTP=" + step2bStatus + " location=[" + step2bLocation + "]" +
                        " | username=" + username);
                }
            }
        }

        // ── Step 2: POST /api/cm/auth/sso → new-CM JWT ────────────────────
        // Body: {"token":"ST-...","service":"https%3A%2F%2F..."} (service URL-encoded — confirmed live)
        String ssoBody = String.format(
            "{\"token\":\"%s\",\"service\":\"%s\"}", ticket, NEW_CM_SERVICE_ENCODED);

        try {
            HttpClient plain = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SSO_URL))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Origin", FTMS_BASE)
                .header("Referer", NEW_CM_SERVICE + "/")
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();
            HttpResponse<String> resp = plain.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new GeneratorException("new-CM SSO HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            // Two response formats observed:
            // v1 (2026-09-15): {"status":"OK","data":{"token":"...","user":{"userName":"..."}}}
            // v2 (2026-09-27): {"accessToken":"...","userName":"..."} — direct root-level fields
            String cmToken;
            String userName;
            if ("OK".equals(root.path("status").asText())) {
                JsonNode data = root.path("data");
                cmToken  = data.path("token").asText(null);
                userName = data.path("user").path("userName").asText(username);
            } else if (!root.path("accessToken").isMissingNode()) {
                cmToken  = root.path("accessToken").asText(null);
                userName = root.path("userName").asText(username);
            } else {
                throw new GeneratorException("new-CM SSO thất bại: " + resp.body());
            }
            if (cmToken == null || cmToken.isBlank()) {
                throw new GeneratorException("new-CM SSO không trả về token: " + resp.body());
            }

            return GenerationResult.builder(type())
                .put("username", userName)
                .put("cmToken", cmToken)
                .put("ticket", ticket)
                .put("service", "new-cm v3 (/api/cm)")
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();

        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("new-CM SSO lỗi: " + e.getMessage(), e);
        }
    }

    // Selenium fallback khi CAS rate-limit pure HTTP login. Browser navigate đến new-CM,
    // CAS tự redirect với ticket (nếu đã có TGT) hoặc hiện form login → LoginPage xử lý.
    // JWT được lấy từ localStorage của page sau khi new-CM SPA khởi động xong.
    // Intercept JS dùng để bắt JWT từ XHR/fetch header sau khi SPA đã login
    private static final String INTERCEPT_JS =
        "window._cmToken = null;" +
        "var origXhrSet = XMLHttpRequest.prototype.setRequestHeader;" +
        "XMLHttpRequest.prototype.setRequestHeader = function(h, v) {" +
        "  if (h && h.toLowerCase() === 'authorization' && v && v.length > 100) {" +
        "    window._cmToken = v.startsWith('Bearer ') ? v.substring(7) : v;" +
        "  }" +
        "  origXhrSet.apply(this, arguments);" +
        "};" +
        "var origFetch = window.fetch;" +
        "window.fetch = function(url, opts) {" +
        "  try {" +
        "    var h = opts && opts.headers;" +
        "    if (h) {" +
        "      var auth = typeof h.get === 'function' ? h.get('Authorization') :" +
        "                 (h['Authorization'] || h['authorization']);" +
        "      if (auth && auth.length > 100) {" +
        "        window._cmToken = auth.startsWith('Bearer ') ? auth.substring(7) : auth;" +
        "      }" +
        "    }" +
        "  } catch(e) {}" +
        "  return origFetch.apply(this, arguments);" +
        "};";

    private GenerationResult loginViaSeleniumForNewCm(String username, String password, long t0) {
        String cmToken = automation.computeWithDriver(driver -> {
            // Inject interceptor via CDP Page.addScriptToEvaluateOnNewDocument BEFORE navigation.
            // executeScript() runs after DOMContentLoaded — too late because the SPA calls its
            // auth API during initialization before the injected code is in place.
            // CDP guarantees the script executes before any page-level JS on every navigation.
            if (driver instanceof org.openqa.selenium.chromium.HasCdp cdpDriver) {
                cdpDriver.executeCdpCommand(
                    "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", INTERCEPT_JS));
            }

            // Login — interceptor now live for all navigations (CAS redirects → new-cm SPA load)
            new LoginPage(driver).loginToUrl(NEW_CM_SERVICE + "/", username, password);

            // Poll for JWT captured by interceptor during SPA initialization
            for (int i = 0; i < 60; i++) {
                String tok = (String) ((JavascriptExecutor) driver).executeScript(
                    "return window._cmToken;");
                if (tok != null && !tok.isBlank() && tok.startsWith("eyJ")) return tok;
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            throw new GeneratorException(
                "Không capture được JWT từ new-CM sau 30s. " +
                "CDP addScriptToEvaluateOnNewDocument injected nhưng SPA không gọi API nào có Authorization header.");
        });
        return GenerationResult.builder(type())
            .put("username", username)
            .put("cmToken", cmToken)
            .put("method", "selenium-fallback (CDP-interceptor)")
            .put("durationMs", System.currentTimeMillis() - t0)
            .build();
    }
}
