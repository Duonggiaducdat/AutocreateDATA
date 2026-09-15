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
    private static final Pattern TICKET_PAT = Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");

    private final ObjectMapper mapper  = new ObjectMapper();
    private final Duration     timeout;
    private final CasTgtCache  tgtCache;

    public GetNewCmTokenApiGenerator(AutodataProperties props, CasTgtCache tgtCache) {
        this.timeout  = Duration.ofSeconds(props.getApi().getTimeoutSeconds());
        this.tgtCache = tgtCache;
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
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CAS_URL))
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = clientWithCookies.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 302) {
                Matcher m = TICKET_PAT.matcher(resp.headers().firstValue("location").orElse(""));
                if (m.find()) ticket = m.group(1);
            }
        } catch (Exception e) {
            throw new GeneratorException("Step 1 GET CAS new-CM thất bại: " + e.getMessage(), e);
        }

        if (ticket == null) {
            throw new GeneratorException(
                "Không lấy được CAS ticket cho new-CM. " +
                "Hãy gọi auth.get-token-api trước để đăng nhập BMS và cache TGT. " +
                "TGT từ BMS login được dùng lại cho new-CM service.");
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
            if (!"OK".equals(root.path("status").asText())) {
                throw new GeneratorException("new-CM SSO thất bại: " + resp.body());
            }
            JsonNode data    = root.path("data");
            String cmToken   = data.path("token").asText(null);
            if (cmToken == null || cmToken.isBlank()) {
                throw new GeneratorException("new-CM SSO không trả về token: " + resp.body());
            }
            String userName  = data.path("user").path("userName").asText(username);

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
}
