package com.fpt.autodata.generator.impl.auth;

import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.context.annotation.Lazy;
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
 * Lấy IVOICE Session (cookie jar) qua pure HTTP — không cần Selenium.
 *
 * Flow (reverse-engineered 2026-09-12):
 *   1. GET /id/login?service=https://ftms-stag.fpt.net/ivoice/?1=1 (TGT cache)
 *      → 302 + ticket ST-XXXXX
 *   2. GET /ivoice/?1=1&ticket=ST-XXXXX
 *      → 200 + Set-Cookie: IVOICE_USER_COOKIE, IVOICE_USER_SESSION (HttpOnly)
 *
 * Cookie jar được trả về dưới dạng "name=value; name=value" để dùng
 * làm Cookie header cho các IVOICE API call tiếp theo.
 */
@Component
public class GetIvoiceSessionApiGenerator implements Generator {

    private static final String BASE            = "https://ftms-stag.fpt.net";
    private static final String IVOICE_SERVICE  = BASE + "/ivoice/?1=1";
    private static final String CAS_URL         = BASE + "/id/login?service=" + IVOICE_SERVICE;
    private static final Pattern TICKET_PAT     = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");
    private static final String IVOICE_PREFIX   = "IVOICE_USER_";

    private final Duration                timeout;
    private final CasTgtCache             tgtCache;
    private final GetIvoiceSessionGenerator seleniumFallback;

    public GetIvoiceSessionApiGenerator(AutodataProperties props, CasTgtCache tgtCache,
                                         @Lazy GetIvoiceSessionGenerator seleniumFallback) {
        this.timeout          = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache         = tgtCache;
        this.seleniumFallback = seleniumFallback;
    }

    @Override public String type()        { return "auth.get-ivoice-session-api"; }
    @Override public String displayName() { return "Lấy Session IVOICE · Pure API (không Selenium)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login?service=/ivoice/?1=1 (TGT cache) → GET /ivoice/?1=1&ticket=... " +
               "→ IVOICE_USER_COOKIE + IVOICE_USER_SESSION. Yêu cầu TGT từ auth.get-token-api.";
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
        String password = Params.requireString(p, "password");
        long t0 = System.currentTimeMillis();

        CookieManager tgtCookies = tgtCache.getOrCreate(username);
        HttpClient tgtClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(tgtCookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CAS ticket cho IVOICE service ──────────────────────
        String ticket = null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CAS_URL))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = tgtClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 302) {
                Matcher m = TICKET_PAT.matcher(resp.headers().firstValue("location").orElse(""));
                if (m.find()) ticket = m.group(1);
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 1 GET CAS IVOICE thất bại: " + e.getMessage(), e);
        }

        if (ticket == null) {
            return seleniumFallback.generate(p);
        }

        // ── Step 2: GET /ivoice/?1=1&ticket=... → IVOICE session cookies ───
        String cookieHeader;
        try {
            // Dùng CookieManager riêng cho IVOICE (tách khỏi TGT để tránh conflict)
            CookieManager ivoiceCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient ivoiceClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .cookieHandler(ivoiceCookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

            String ivoiceUrl = BASE + "/ivoice/?1=1&ticket=" + ticket;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ivoiceUrl))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = ivoiceClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new GeneratorException(
                    "IVOICE ticket validation thất bại — HTTP " + resp.statusCode());
            }

            // Collect tất cả cookies từ Set-Cookie headers
            List<String> setCookies = resp.headers().allValues("set-cookie");
            if (setCookies.isEmpty()) {
                // Thử lấy từ CookieManager
                var stored = ivoiceCookies.getCookieStore().getCookies();
                cookieHeader = stored.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
            } else {
                cookieHeader = setCookies.stream()
                    .map(h -> h.split(";")[0].trim())
                    .collect(Collectors.joining("; "));
            }

            if (cookieHeader.isBlank()) {
                throw new GeneratorException("IVOICE không set cookie nào — ticket có thể đã hết hạn");
            }

            long hasIvoiceCookie = java.util.Arrays.stream(cookieHeader.split("; "))
                .filter(s -> s.startsWith(IVOICE_PREFIX)).count();

            return GenerationResult.builder(type())
                .put("username", username)
                .put("cookieHeader", cookieHeader)
                .put("ivoiceCookieCount", hasIvoiceCookie)
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("IVOICE session lỗi: " + e.getMessage(), e);
        }
    }

}
