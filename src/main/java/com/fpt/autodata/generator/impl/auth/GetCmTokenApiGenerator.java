package com.fpt.autodata.generator.impl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lấy CM Bearer Token qua pure HTTP — không cần Selenium.
 *
 * Flow (reverse-engineered 2026-09-12):
 *   1. GET /id/login?service=https://ftms-stag.fpt.net/cm/  (dùng TGT cache)
 *      → 302 + ticket ST-XXXXX
 *   2. POST /gwcm/api/Auth/LoginSSO
 *      body: {"ticket":"ST-XXXXX","Service":"https://ftms-stag.fpt.net/cm/"}
 *      → data.token (CM JWT)
 *
 * Dùng TGT cache từ CasTgtCache (shared với GetTokenApiGenerator).
 * Nếu chưa có TGT, cần gọi auth.get-token-api trước.
 */
@Component
public class GetCmTokenApiGenerator implements Generator {

    private static final String BASE         = "https://ftms-stag.fpt.net";
    private static final String CM_SERVICE   = BASE + "/cm/";
    private static final String CAS_URL      = BASE + "/id/login?service=" + CM_SERVICE;
    private static final String CM_SSO_URL   = BASE + "/gwcm/api/Auth/LoginSSO";
    private static final Pattern TICKET_PAT  = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    private final ObjectMapper        mapper   = new ObjectMapper();
    private final Duration            timeout;
    private final CasTgtCache         tgtCache;
    private final GetCmTokenGenerator seleniumFallback;

    public GetCmTokenApiGenerator(AutodataProperties props, CasTgtCache tgtCache,
                                   @Lazy GetCmTokenGenerator seleniumFallback) {
        this.timeout          = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache         = tgtCache;
        this.seleniumFallback = seleniumFallback;
    }

    @Override public String type()        { return "auth.get-cm-token-api"; }
    @Override public String displayName() { return "Lấy Token CM · Pure API (không Selenium)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login?service=/cm/ (TGT cache) → POST /gwcm/api/Auth/LoginSSO → CM JWT. " +
               "Yêu cầu đã login BMS trước (auth.get-token-api) để có TGT.";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
            ParamSpec.text("username").required()
                .label("Username / Email")
                .placeholder("vd: truongnv64@fpt.com"),
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

        CookieManager cookies = tgtCache.getOrCreate(username);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CAS ticket cho CM service ──────────────────────────
        String ticket = getCasTicket(client, CAS_URL);
        if (ticket == null) {
            // TGT chưa có → fallback Selenium generator cũ
            return seleniumFallback.generate(p);
        }

        // ── Step 2: POST LoginSSO → CM JWT ─────────────────────────────────
        String ssoBody = String.format(
            "{\"ticket\":\"%s\",\"Service\":\"%s\"}", ticket, CM_SERVICE);
        try {
            HttpClient plain = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CM_SSO_URL))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Origin", BASE)
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();
            HttpResponse<String> resp = plain.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (!"OK".equals(root.path("status").asText())) {
                throw new GeneratorException("CM LoginSSO thất bại: " + resp.body());
            }
            JsonNode data = root.path("data");
            String cmToken  = data.path("token").asText();
            String userName = data.path("user").path("userName").asText();

            return GenerationResult.builder(type())
                .put("username", userName)
                .put("cmToken", cmToken)
                .put("ticket", ticket)
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("CM LoginSSO lỗi: " + e.getMessage(), e);
        }
    }

    private String getCasTicket(HttpClient client, String casUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(casUrl))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 302) {
                String loc = resp.headers().firstValue("location").orElse("");
                Matcher m = TICKET_PAT.matcher(loc);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

}
