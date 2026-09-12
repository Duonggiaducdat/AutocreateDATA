package com.fpt.autodata.generator.impl.auth;

import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import org.springframework.context.annotation.Lazy;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
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
    private static final String LEGACY_COOKIE  = "FTIBMS-Auth";

    private final Duration                    timeout;
    private final CasTgtCache                 tgtCache;
    private final GetBmsLegacySessionGenerator seleniumFallback;

    public GetBmsLegacySessionApiGenerator(AutodataProperties props, CasTgtCache tgtCache,
                                            @Lazy GetBmsLegacySessionGenerator seleniumFallback) {
        this.timeout          = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache         = tgtCache;
        this.seleniumFallback = seleniumFallback;
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

        // ── Step 2: GET CAS với TGT → ticket ───────────────────────────────
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
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 2 GET CAS ticket lỗi: " + e.getMessage(), e);
        }

        if (ticket == null) {
            return seleniumFallback.generate(p);
        }

        // ── Step 3: GET SignInCas?state=...&ticket=... → FTIBMS-Auth cookie ─
        String signInUrl = casServiceUrl + "&ticket=" + ticket;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(signInUrl))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = bmsClient.send(req, HttpResponse.BodyHandlers.ofString());

            // Collect cookies từ Set-Cookie hoặc CookieManager
            List<String> setCookies = resp.headers().allValues("set-cookie");
            String cookieHeader;
            if (!setCookies.isEmpty()) {
                cookieHeader = setCookies.stream()
                    .map(h -> h.split(";")[0].trim())
                    .collect(Collectors.joining("; "));
            } else {
                cookieHeader = bmsCookies.getCookieStore().getCookies().stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
            }

            if (!cookieHeader.contains(LEGACY_COOKIE)) {
                throw new GeneratorException(
                    "FTIBMS-Auth cookie không xuất hiện sau SignInCas. " +
                    "HTTP " + resp.statusCode() + ". Cookies: " + cookieHeader);
            }

            return GenerationResult.builder(type())
                .put("username", username)
                .put("refCode", refCode)
                .put("cookieHeader", cookieHeader)
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Step 3 SignInCas lỗi: " + e.getMessage(), e);
        }
    }

}
