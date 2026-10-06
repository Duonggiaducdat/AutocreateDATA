package com.fpt.autodata.generator.impl.auth;

import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.selenium.LoginPage;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lấy BMS Legacy Session (FTIBMS-Auth cookie) qua pure HTTP — không cần Selenium.
 *
 * Flow (reverse-engineered 2026-09-12):
 *   1. GET /bms/RegistrationMagt_v12/CreateRegist?refCode=...
 *      → 302 → /bms/Account/Login?ReturnUrl=...
 *      → 302 → CAS URL: /id/login?service=/bms/Account/SignInCas?state=<TOKEN>
 *      (state token là CSRF token của ASP.NET, thay đổi mỗi lần)
 *   2. GET CAS URL với TGT cookie → 302 + ticket ST-XXXXX
 *   3. GET /bms/Account/SignInCas?state=<TOKEN>&ticket=ST-XXXXX
 *      → Set-Cookie: FTIBMS-Auth, FTIBMS, jwt_token_id
 *
 * Dùng TGT cache từ CasTgtCache (shared với GetTokenApiGenerator).
 */
@Component
public class GetBmsLegacySessionApiGenerator implements Generator {

    private static final String BASE           = "https://ftms-stag.fpt.net";
    private static final String CREATE_REGIST  = BASE + "/bms/RegistrationMagt_v12/CreateRegist?refCode=";
    private static final Pattern CAS_PAT       = Pattern.compile(
        "service=(https?://[^\"&\\s]+SignInCas[^\"&\\s]*)");
    private static final Pattern TICKET_PAT    = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");
    private static final Pattern EXECUTION_PAT = Pattern.compile("name=\"execution\"\\s+value=\"([^\"]+)\"");
    private static final String LEGACY_COOKIE  = "FTIBMS-Auth";

    private final Duration       timeout;
    private final CasTgtCache    tgtCache;
    private final FtmsAutomation automation;

    public GetBmsLegacySessionApiGenerator(AutodataProperties props, CasTgtCache tgtCache, FtmsAutomation automation) {
        this.timeout    = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache   = tgtCache;
        this.automation = automation;
    }

    @Override public String type()        { return "auth.get-bms-legacy-session-api"; }
    @Override public String displayName() { return "Lấy Session BMS Legacy · Pure API (không Selenium)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /bms/Account/Login (parse state token) → GET CAS (TGT) → GET SignInCas " +
               "→ FTIBMS-Auth cookie. Yêu cầu TGT từ auth.get-token-api.";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
            ParamSpec.text("username").required()
                .label("Username / Email")
                .placeholder("vd: ThiTLH@fpt.com"),
            ParamSpec.password("password").required()
                .label("Password / OTP")
                .defaultValue("ISC22QC"),
            ParamSpec.text("refCode").required()
                .label("RefCode")
                .placeholder("CH00085411_96363")
                .hint("cần refCode hợp lệ để vào được vùng bảo vệ")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");
        String refCode  = Params.requireString(p, "refCode");
        long t0 = System.currentTimeMillis();

        CookieManager bmsCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient bmsClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(bmsCookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CreateRegist → theo redirect → parse CAS URL với state ─
        String casServiceUrl = null;
        String currentUrl = CREATE_REGIST + refCode;
        for (int hop = 0; hop < 5; hop++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
                HttpResponse<String> resp = bmsClient.send(req, HttpResponse.BodyHandlers.ofString());
                String loc = resp.headers().firstValue("location").orElse("");

                // Normalize http → https
                loc = loc.replace("http://ftms-stag", "https://ftms-stag");

                if (loc.contains("SignInCas")) {
                    // Tìm service param trong URL CAS
                    Matcher m = CAS_PAT.matcher(loc);
                    if (m.find()) {
                        casServiceUrl = java.net.URLDecoder.decode(m.group(1),
                            java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        casServiceUrl = loc.contains("service=")
                            ? java.net.URLDecoder.decode(loc.substring(loc.indexOf("service=") + 8),
                                java.nio.charset.StandardCharsets.UTF_8)
                            : null;
                    }
                    break;
                }
                if (loc.isBlank() || resp.statusCode() == 200) break;
                currentUrl = loc;
            } catch (Exception e) {
                throw new GeneratorException("Bước parse CAS URL lỗi: " + e.getMessage(), e);
            }
        }

        if (casServiceUrl == null) {
            throw new GeneratorException(
                "Không tìm được CAS service URL từ /bms/Account/Login. " +
                "Kiểm tra refCode có hợp lệ không.");
        }

        // ── Step 2: GET CAS với TGT → ticket (nếu TGT còn hạn) ──────────────
        //           Nếu TGT hết hạn → POST credentials để lấy ticket mới (pure API, không Selenium)
        //           Note: Selenium fallback đã broken vì /bms/OppsMagt/Create redirect sang bmsv2 (confirmed 2026-09-12)
        CookieManager tgtCookies = tgtCache.getOrCreate(username);
        HttpClient tgtClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(tgtCookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        String fullCasUrl = BASE + "/id/login?service=" +
            java.net.URLEncoder.encode(casServiceUrl, java.nio.charset.StandardCharsets.UTF_8);
        String ticket = null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullCasUrl))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = tgtClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 302) {
                Matcher m = TICKET_PAT.matcher(resp.headers().firstValue("location").orElse(""));
                if (m.find()) ticket = m.group(1);
            } else if (resp.statusCode() == 200 && ticket == null) {
                // TGT hết hạn — parse execution token và POST credentials để lấy ticket mới
                Matcher execM = EXECUTION_PAT.matcher(resp.body());
                String execution = execM.find() ? execM.group(1) : "e1s1";
                String formBody = "username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                    + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                    + "&execution=" + java.net.URLEncoder.encode(execution, java.nio.charset.StandardCharsets.UTF_8)
                    + "&_eventId=submit&language=vi&countLogin=0&hasError=0&errorMsg=";
                HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(fullCasUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", fullCasUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
                HttpResponse<String> postResp = tgtClient.send(postReq, HttpResponse.BodyHandlers.ofString());
                if (postResp.statusCode() == 302) {
                    Matcher m = TICKET_PAT.matcher(postResp.headers().firstValue("location").orElse(""));
                    if (m.find()) ticket = m.group(1);
                } else if (postResp.statusCode() == 401) {
                    // Rate-limited → Selenium fallback
                    tgtCache.invalidate(username);
                    return loginViaSeleniumForBmsLegacy(username, password, refCode, t0);
                }
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 2 GET/POST CAS ticket lỗi: " + e.getMessage(), e);
        }

        if (ticket == null) {
            throw new GeneratorException(
                "Không lấy được CAS ticket cho BMS legacy (TGT hết hạn và POST credentials cũng thất bại). " +
                "Kiểm tra username/password.");
        }

        // ── Step 3: SignInCas → follow redirect → CreateRegist (cùng 1 luồng) ──
        // Browser thật: SignInCas set FTIBMS-Auth → 302 → CreateRegist.
        // Sau đó cần visit lại CreateRegist lần 2 để server set đủ FTIBMS + jwt_token_id
        // (observed: lần đầu chỉ có FTIBMS-Auth, lần 2 mới có đủ 3 cookies cần thiết).
        String signInUrl = casServiceUrl + "&ticket=" + ticket;
        try {
            HttpClient authClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .cookieHandler(bmsCookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            // Lần 1: SignInCas → CreateRegist (server set FTIBMS-Auth, thiếu FTIBMS + jwt_token_id)
            authClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(signInUrl))
                    .timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());

            // Lần 2: Visit lại CreateRegist với FTIBMS-Auth → server set FTIBMS + jwt_token_id
            authClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(CREATE_REGIST + refCode))
                    .timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", BASE + "/bms/OppsMagt/List")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());

            // Sau khi follow redirect chain, bmsCookies có đầy đủ cookies từ cả SignInCas và CreateRegist
            List<java.net.HttpCookie> allCookies = bmsCookies.getCookieStore().getCookies();
            // Log cookie names để debug (không log values vì sensitive)
            String cookieNames = allCookies.stream().map(java.net.HttpCookie::getName)
                .collect(Collectors.joining(", "));
            String cookieHeader = allCookies.stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));

            if (!cookieHeader.contains(LEGACY_COOKIE)) {
                throw new GeneratorException(
                    "FTIBMS-Auth cookie không xuất hiện sau SignInCas → CreateRegist. " +
                    "Cookies hiện có: [" + cookieNames + "]");
            }

            return GenerationResult.builder(type())
                .put("username", username)
                .put("refCode", refCode)
                .put("cookieHeader", cookieHeader)
                .put("cookieNames", cookieNames) // debug: xem có đủ FTIBMS + jwt_token_id không
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Step 3 SignInCas/CreateRegist lỗi: " + e.getMessage(), e);
        }
    }

    // Fallback Selenium khi CAS pure HTTP bị rate-limit (401).
    // Navigate đến CreateRegist → CAS login (OTP) → redirect về BMS legacy → extract cookies.
    private GenerationResult loginViaSeleniumForBmsLegacy(String username, String password, String refCode, long t0) {
        String cookieHeader = automation.computeWithDriver(driver -> {
            String targetUrl = CREATE_REGIST + refCode;
            // Login via OTP vào BMS legacy URL
            new LoginPage(driver).loginToUrl(targetUrl, username, password);
            // Visit lần 2 để server set đủ FTIBMS + jwt_token_id (giống pure-API flow)
            driver.get(targetUrl);
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            // Collect tất cả cookies
            StringBuilder sb = new StringBuilder();
            for (org.openqa.selenium.Cookie c : driver.manage().getCookies()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(c.getName()).append("=").append(c.getValue());
            }
            String header = sb.toString();
            if (!header.contains(LEGACY_COOKIE)) {
                throw new GeneratorException("Selenium BMS legacy: không tìm được " + LEGACY_COOKIE + " cookie sau login");
            }
            return header;
        });
        return GenerationResult.builder(type())
            .put("username", username)
            .put("refCode", refCode)
            .put("cookieHeader", cookieHeader)
            .put("method", "selenium-fallback (cas rate-limited)")
            .put("durationMs", System.currentTimeMillis() - t0)
            .build();
    }
}
