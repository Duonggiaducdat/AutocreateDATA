package com.fpt.autodata.generator.impl.auth;

import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
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
 * Lấy LL Session (cookie jar) qua pure HTTP.
 *
 * Flow (reverse-engineered 2026-09-12) — giống BMS Legacy pattern:
 *   1. GET https://ftms-stag.fpt.net/ll
 *      → 302 → /ll/account/login
 *      → 302 → /id/login?service=/ll/account/signincas?state=<TOKEN>
 *   2. GET CAS URL với TGT → ticket ST-XXXXX
 *   3. GET /ll/account/signincas?state=<TOKEN>&ticket=ST-XXXXX
 *      → Set-Cookie: session cookies cho LL APIs
 *
 * Cookie jar dùng cho các LL API call như:
 *   /ll/Dashboard/*, /ll/MasterData/*, /ll/Notification/*
 */
@Component
public class GetLlSessionApiGenerator implements Generator {

    private static final String BASE         = "https://ftms-stag.fpt.net";
    private static final String LL_URL       = BASE + "/ll";
    private static final Pattern CAS_PAT     = Pattern.compile(
        "service=(https?://[^\"&\\s]+signincas[^\"&\\s]*)");
    private static final Pattern TICKET_PAT  = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    private final Duration    timeout;
    private final CasTgtCache tgtCache;

    public GetLlSessionApiGenerator(AutodataProperties props, CasTgtCache tgtCache) {
        this.timeout  = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache = tgtCache;
    }

    @Override public String type()        { return "auth.get-ll-session-api"; }
    @Override public String displayName() { return "Lấy Session LL · Pure API (ftms-stag.fpt.net/ll)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /ll → parse state → GET CAS (TGT) → GET /ll/account/signincas " +
               "→ session cookie jar dùng cho LL APIs (/ll/Dashboard, /ll/MasterData...).";
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

        CookieManager llCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient llClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(llCookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET /ll → theo redirect để parse CAS URL với state ──────
        String casServiceUrl = null;
        String currentUrl = LL_URL;
        for (int hop = 0; hop < 5; hop++) {
            try {
                // Normalize http → https
                currentUrl = currentUrl.replace("http://ftms-stag", "https://ftms-stag");
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
                HttpResponse<String> resp = llClient.send(req, HttpResponse.BodyHandlers.ofString());
                String loc = resp.headers().firstValue("location").orElse("");
                loc = loc.replace("http://ftms-stag", "https://ftms-stag");

                if (loc.contains("signincas")) {
                    Matcher m = CAS_PAT.matcher(loc);
                    casServiceUrl = m.find()
                        ? java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8)
                        : loc.substring(loc.indexOf("service=") + 8);
                    break;
                }
                if (loc.isBlank() || resp.statusCode() == 200) break;
                currentUrl = loc;
            } catch (Exception e) {
                throw new GeneratorException("LL redirect parse lỗi: " + e.getMessage(), e);
            }
        }

        if (casServiceUrl == null) {
            throw new GeneratorException("Không parse được CAS URL từ /ll/account/login.");
        }

        // ── Step 2: GET CAS với TGT → ticket ───────────────────────────────
        CookieManager tgtCookies = tgtCache.getOrCreate(username);
        HttpClient tgtClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(tgtCookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        String encodedService = java.net.URLEncoder.encode(
            casServiceUrl, java.nio.charset.StandardCharsets.UTF_8);
        String fullCasUrl = BASE + "/id/login?service=" + encodedService;
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
            throw new GeneratorException("Step 2 GET CAS ticket LL lỗi: " + e.getMessage(), e);
        }

        if (ticket == null) {
            throw new GeneratorException(
                "Không lấy được CAS ticket cho LL. Hãy gọi auth.get-token-api trước để cache TGT.");
        }

        // ── Step 3: GET signincas?state=...&ticket=... → session cookies ───
        String signInUrl = casServiceUrl + "&ticket=" + ticket;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(signInUrl))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = llClient.send(req, HttpResponse.BodyHandlers.ofString());

            List<String> setCookies = resp.headers().allValues("set-cookie");
            String cookieHeader;
            if (!setCookies.isEmpty()) {
                cookieHeader = setCookies.stream()
                    .map(h -> h.split(";")[0].trim())
                    .collect(Collectors.joining("; "));
            } else {
                cookieHeader = llCookies.getCookieStore().getCookies().stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
            }

            if (cookieHeader.isBlank()) {
                throw new GeneratorException(
                    "LL signincas không set cookie nào — HTTP " + resp.statusCode());
            }

            return GenerationResult.builder(type())
                .put("username", username)
                .put("cookieHeader", cookieHeader)
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("Step 3 LL signincas lỗi: " + e.getMessage(), e);
        }
    }
}
