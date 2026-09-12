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
 * Lấy IDC Bearer Token qua pure HTTP.
 *
 * Flow (reverse-engineered 2026-09-12):
 *   1. GET /id/login?service=https://ftms-stag.fpt.net/idc  (TGT cache)
 *      → 302 + ticket ST-XXXXX
 *   2. POST https://apis-stag.fpt.net/idc/api/auth/sso
 *      body: {"token":"ST-XXXXX","service":"https%3A%2F%2Fftms-stag.fpt.net%2Fidc"}
 *      → data.accessToken, data.refreshToken
 *
 * Lưu ý: IDC dùng apis-stag.fpt.net (khác BMS/SM dùng ftisapi-stag.fpt.net).
 */
@Component
public class GetIdcTokenApiGenerator implements Generator {

    private static final String FTMS_BASE   = "https://ftms-stag.fpt.net";
    private static final String IDC_SERVICE = FTMS_BASE + "/idc";
    private static final String IDC_SERVICE_ENCODED = "https%3A%2F%2Fftms-stag.fpt.net%2Fidc";
    private static final String CAS_URL     = FTMS_BASE + "/id/login?service=" + IDC_SERVICE;
    private static final String IDC_SSO_URL = "https://apis-stag.fpt.net/idc/api/auth/sso";
    private static final Pattern TICKET_PAT = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    private final ObjectMapper mapper  = new ObjectMapper();
    private final Duration     timeout;
    private final CasTgtCache  tgtCache;

    public GetIdcTokenApiGenerator(AutodataProperties props, CasTgtCache tgtCache) {
        this.timeout  = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache = tgtCache;
    }

    @Override public String type()        { return "auth.get-idc-token-api"; }
    @Override public String displayName() { return "Lấy Token IDC · Pure API (ftms-stag.fpt.net/idc)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "GET /id/login?service=/idc (TGT cache) → POST apis-stag/idc/api/auth/sso " +
               "→ accessToken dùng cho IDC API. Yêu cầu TGT từ auth.get-token-api.";
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
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // ── Step 1: GET CAS ticket cho IDC service ─────────────────────────
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
            throw new GeneratorException("Step 1 GET CAS IDC thất bại: " + e.getMessage(), e);
        }

        if (ticket == null) {
            throw new GeneratorException(
                "Không lấy được CAS ticket cho IDC. Hãy gọi auth.get-token-api trước để cache TGT.");
        }

        // ── Step 2: POST SSO → IDC accessToken ─────────────────────────────
        String ssoBody = String.format(
            "{\"token\":\"%s\",\"service\":\"%s\"}", ticket, IDC_SERVICE_ENCODED);
        try {
            HttpClient plain = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(IDC_SSO_URL))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Origin", FTMS_BASE)
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();
            HttpResponse<String> resp = plain.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());

            if (resp.statusCode() != 200 || !"OK".equals(root.path("status").asText())) {
                throw new GeneratorException("IDC SSO thất bại: " + resp.body());
            }

            JsonNode data = root.path("data");
            return GenerationResult.builder(type())
                .put("username", data.path("userName").asText(username))
                .put("accessToken", data.path("accessToken").asText())
                .put("refreshToken", data.path("refreshToken").asText())
                .put("durationMs", System.currentTimeMillis() - t0)
                .build();
        } catch (GeneratorException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneratorException("IDC SSO lỗi: " + e.getMessage(), e);
        }
    }
}
