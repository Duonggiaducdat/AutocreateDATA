package com.fpt.autodata.generator.impl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.generator.impl.AbstractGenerator;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;
import com.fpt.autodata.selenium.LoginPage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logs into BMS via the same Selenium {@link LoginPage} flow as
 * {@link GetTokenGenerator}, then ALSO opens the CM app ({@code
 * ftms-stag.fpt.net/cm/}) in the same authenticated browser session so its
 * own CAS ticket-exchange ({@code gwcm/api/Auth/CheckLoginSSO}) completes,
 * and captures the CM-specific bearer token that mints.
 *
 * <p>Why a separate token: the CM app's JWT carries extra claims the
 * bms-service one doesn't ({@code TICKET}, {@code DVC}/{@code AC}/{@code
 * DPC} division codes, {@code role} as an array) and is required by every
 * {@code gwcm/contract/api/*} call (approve HĐ/PL step 2) — the plain
 * {@code access_token} cookie {@link GetTokenGenerator} reads is NOT
 * accepted there. See {@code memory/api-contracts.md} "Approve Hợp đồng/Phụ
 * lục" section.</p>
 *
 * <p>Found by probing live 2026-08-27: after visiting {@code /cm/} in an
 * already-CAS-authenticated Selenium session, the {@code access_token}
 * cookie gets OVERWRITTEN with a DIFFERENT-shaped JWT (issuer {@code B2B})
 * — decode every cookie that looks JWT-shaped and pick the one whose
 * payload actually has a {@code DVC} claim, rather than assuming a fixed
 * cookie name, since which cookie ends up holding it isn't guaranteed
 * stable across CM redeploys.</p>
 */
@Component
public class GetCmTokenGenerator extends AbstractGenerator {

    private static final String BMSV2_URL = "https://ftms-stag.fpt.net/bmsv2";
    private static final String CM_URL = "https://ftms-stag.fpt.net/cm/";
    private static final int POLL_ATTEMPTS = 30;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final Set<String> CM_CLAIM_MARKERS = Set.of("dvc", "ticket", "dpc");

    private final ObjectMapper mapper = new ObjectMapper();

    public GetCmTokenGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "auth.get-cm-token"; }
    @Override public String displayName() { return "Lấy Token CM (Selenium login)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "Đăng nhập BMS rồi mở thêm app CM (cùng phiên) để lấy bearer token riêng cho gwcm/contract/api/* (duyệt HĐ/PL bước 2).";
    }
    @Override public String signature()   { return "login(username, password) -> CM-specific access_token cookie"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").required().placeholder("vd: truongnv64@fpt.com"),
                ParamSpec.password("password").required()
                        .defaultValue("ISC22QC")
                        .hint("mã bypass OTP dùng chung trên STG — nếu account chưa từng đăng nhập CM trên trình duyệt Selenium này có thể vẫn bị chặn bởi OTP email, xem log")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "login(" + username + ") via Selenium");

        CmTokenResult found = automation.computeWithDriver(driver -> {
            new LoginPage(driver).login(username, password);
            // Warm up the same session against bmsv2 first (proven flow) —
            // establishes the base CAS session before asking CM to piggyback
            // its own ticket exchange on top of it.
            driver.get(BMSV2_URL);
            driver.get(CM_URL);
            for (int i = 0; i < POLL_ATTEMPTS; i++) {
                CmTokenResult candidate = scanCookiesForCmToken(driver);
                if (candidate != null) return candidate;
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            CmTokenResult fromStorage = scanWebStorageForCmToken(driver);
            if (fromStorage != null) return fromStorage;
            String cookieNames = driver.manage().getCookies().stream()
                    .map(Cookie::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
            String storageKeys = listWebStorageKeys(driver);
            throw new GeneratorException(
                    "Mở /cm/ xong nhưng sau " + (POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000)
                    + "s không thấy token nào có claim DVC/TICKET/DPC (đã quét cả cookie và local/sessionStorage). "
                    + "currentUrl=" + driver.getCurrentUrl()
                    + " · cookies present=[" + cookieNames + "] · storage keys=[" + storageKeys + "]");
        });

        if (found == null) {
            throw new GeneratorException("unreachable");
        }
        trace(log, "CM token found in cookie \"" + found.cookieName + "\", length=" + found.token.length());

        return result()
                .put("username", username)
                .put("cmToken", found.token)
                .put("cookieName", found.cookieName)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private record CmTokenResult(String cookieName, String token) {}

    private CmTokenResult scanCookiesForCmToken(WebDriver driver) {
        for (Cookie cookie : driver.manage().getCookies()) {
            String value = cookie.getValue();
            if (value == null || value.chars().filter(c -> c == '.').count() != 2) continue; // not JWT-shaped
            JsonNode payload = decodeJwtPayload(value);
            if (payload == null) continue;
            boolean looksLikeCmToken = false;
            var fields = payload.fieldNames();
            while (fields.hasNext()) {
                if (CM_CLAIM_MARKERS.contains(fields.next().toLowerCase())) { looksLikeCmToken = true; break; }
            }
            if (looksLikeCmToken) return new CmTokenResult(cookie.getName(), value);
        }
        return null;
    }

    /** CM might keep its token in local/sessionStorage instead of a cookie — scan both, all keys. */
    @SuppressWarnings("unchecked")
    private CmTokenResult scanWebStorageForCmToken(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        for (String storageObj : new String[]{"localStorage", "sessionStorage"}) {
            List<Object> entries = (List<Object>) js.executeScript(
                    "var s=" + storageObj + ",r=[];for(var i=0;i<s.length;i++){var k=s.key(i);r.push([k,s.getItem(k)]);}return r;");
            if (entries == null) continue;
            for (Object entryObj : entries) {
                List<Object> entry = (List<Object>) entryObj;
                String key = String.valueOf(entry.get(0));
                Object valueObj = entry.get(1);
                if (valueObj == null) continue;
                String value = valueObj.toString();
                String jwtCandidate = extractJwtLike(value);
                if (jwtCandidate == null) continue;
                JsonNode payload = decodeJwtPayload(jwtCandidate);
                if (payload == null) continue;
                var fields = payload.fieldNames();
                while (fields.hasNext()) {
                    if (CM_CLAIM_MARKERS.contains(fields.next().toLowerCase())) {
                        return new CmTokenResult(storageObj + "." + key, jwtCandidate);
                    }
                }
            }
        }
        return null;
    }

    /** The value might be the raw JWT itself, or JSON wrapping it under some key (e.g. {"token":"eyJ..."}). */
    private String extractJwtLike(String raw) {
        if (raw.chars().filter(c -> c == '.').count() == 2 && raw.startsWith("ey")) return raw;
        try {
            JsonNode node = mapper.readTree(raw);
            return findJwtInTree(node);
        } catch (Exception e) {
            return null;
        }
    }

    private String findJwtInTree(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) {
            String v = node.asText();
            if (v.startsWith("ey") && v.chars().filter(c -> c == '.').count() == 2) return v;
            return null;
        }
        var iter = node.elements();
        while (iter.hasNext()) {
            String found = findJwtInTree(iter.next());
            if (found != null) return found;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String listWebStorageKeys(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        StringBuilder sb = new StringBuilder();
        for (String storageObj : new String[]{"localStorage", "sessionStorage"}) {
            List<Object> keys = (List<Object>) js.executeScript(
                    "var s=" + storageObj + ",r=[];for(var i=0;i<s.length;i++){r.push(s.key(i));}return r;");
            if (keys != null) for (Object k : keys) sb.append(storageObj).append('.').append(k).append(", ");
        }
        return sb.length() == 0 ? "(none)" : sb.substring(0, sb.length() - 2);
    }

    private JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            return mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private String padBase64(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "=".repeat(4 - mod);
    }
}
