package com.fpt.autodata.generator.impl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.config.AutodataProperties;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
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
 * Lấy SM Bearer Token qua pure HTTP — dùng cho sm/v2 (IVOICE, SM, MES, LL...).
 *
 * Flow (reverse-engineered 2026-09-12):
 *   1. GET /id/login?service=https://ftms-stag.fpt.net/sm/v2/  (TGT cache)
 *      → 302 + ticket ST-XXXXX
 *   2. POST https://ftisapi-stag.fpt.net/sm/v2/api/auth/sso/login
 *      body: {"ticket":"ST-XXXXX","serviceUrl":"https://ftms-stag.fpt.net/sm/v2/"}
 *      → data.accessToken (JWT dùng cho mọi SM API)
 *
 * Khác với BMS (POST /api/bam/auth/sso), SM dùng endpoint riêng tại ftisapi-stag.
 * Dùng TGT cache từ CasTgtCache — gọi auth.get-token-api trước để seed TGT.
 */
@Component
public class GetSmTokenApiGenerator implements Generator {

    private static final String FTMS_BASE   = "https://ftms-stag.fpt.net";
    private static final String SM_SERVICE  = FTMS_BASE + "/sm/v2/";
    private static final String CAS_URL     = FTMS_BASE + "/id/login?service=" + SM_SERVICE;
    private static final String SM_SSO_URL  = "https://ftisapi-stag.fpt.net/sm/v2/api/auth/sso/login";
    private static final Pattern TICKET_PAT = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    private final ObjectMapper mapper  = new ObjectMapper();
    private final Duration     timeout;
    private final CasTgtCache  tgtCache;

    public GetSmTokenApiGenerator(AutodataProperties props, CasTgtCache tgtCache) {
        this.timeout  = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache = tgtCache;
    }

    @Override public String type()        { return "auth.get-sm-token-api"; }
    @Override public String displayName() { return "Lấy Token SM · Pure API (sm/v2 — IVOICE, SM, MES, LL)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login?service=/sm/v2/ (TGT cache) → POST ftisapi/sm/v2/api/auth/sso/login " +
               "→ accessToken dùng cho mọi API thuộc sm/v2 (IVOICE, SM, MES, LL...).";
    }
    @Override public String signature() { return "login(username) → SM accessToken (JWT)"; }

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
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CAS ticket cho SM service ──────────────────────────
        String ticket = null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CAS_URL))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 302) {
                Matcher m = TICKET_PAT.matcher(resp.headers().firstValue("location").orElse(""));
                if (m.find()) ticket = m.group(1);
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 1 GET CAS SM thất bại: " + e.getMessage(), e);
        }

        if (ticket == null) {
            throw new GeneratorException(
                "Không lấy được CAS ticket cho SM/v2. " +
                "Hãy gọi auth.get-token-api trước để đăng nhập BMS và cache TGT. " +
                "TGT từ BMS login có thể dùng cho SM/v2.");
        }

        // ── Step 2: POST SSO login → SM accessToken ─────────────────────────
        String ssoBody = String.format(
            "{\"ticket\":\"%s\",\"serviceUrl\":\"%s\"}", ticket, SM_SERVICE);
        try {
            HttpClient plain = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SM_SSO_URL))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Origin", FTMS_BASE)
                .header("Referer", SM_SERVICE)
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();
            HttpResponse<String> resp = plain.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());

            if (resp.statusCode() != 200 || !"OK".equals(root.path("status").asText())) {
                throw new GeneratorException("SM SSO login thất bại: " + resp.body());
            }

            JsonNode data = root.path("data");
            String accessToken  = data.path("accessToken").asText();
            String refreshToken = data.path("refreshToken").asText();

            return GenerationResult.builder(type())
                .put("username", username)
                .put("accessToken", accessToken)
                .put("refreshToken", refreshToken)
                .put("ticket", ticket)
                .put("service", "sm/v2 (IVOICE, SM, MES, LL)")
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("SM SSO login lỗi: " + e.getMessage(), e);
        }
    }
}
