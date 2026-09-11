package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;
import pages.LoginPage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Logs into BMS via Selenium, then visits the legacy {@code
 * bms/RegistrationMagt_v12/CreateRegist} page in the SAME session to mint
 * the {@code FTIBMS-Auth}/{@code FTIBMS}/{@code jwt_token_id} cookies that
 * {@code bms.insert-registration} (the real "Tạo" submit, see
 * {@link InsertBmsRegistrationGenerator}) needs.
 *
 * <p><b>Why this is a SEPARATE generator from {@link
 * GetIvoiceSessionGenerator}</b> (`auth.get-ivoice-session`), confirmed live
 * 2026-09-07: reusing that generator's cookie jar against {@code
 * InsertRegistration} got a real HTTP 401 — it never carries {@code
 * FTIBMS-Auth} at all. Worse, the two areas are mutually exclusive within
 * ONE browser session: visiting this legacy page AFTER bmsv2/ivoice wipes
 * {@code BMS_access_token}/{@code IVOICE_USER_*}, and visiting it BEFORE
 * them means bmsv2/ivoice then wipe {@code FTIBMS-Auth}/{@code FTIBMS} right
 * back out. Each area's protected route triggers its own fresh sign-in that
 * appears to sign the other auth scheme out. The user confirmed this isn't
 * a Selenium artifact (raw {@code driver.get()} vs a real UI click) — a real
 * browser clicking from BMS into IVOICE genuinely navigates to a separate
 * auth area the same way. So a flow needing both {@code
 * bms.insert-registration} AND any {@code ivoice.*} call must fetch TWO
 * SEPARATE cookie jars (two separate Selenium logins) — one from each
 * generator — never one shared jar. See memory/api-contracts.md.</p>
 *
 * <p>Requires an existing {@code refCode} (e.g. from {@code
 * ivoice.lookup-opportunity}) purely to have a valid URL to land on inside
 * the protected area — the resulting auth cookies are generic to the
 * legacy app, not scoped to that specific refCode.</p>
 */
@Component
public class GetBmsLegacySessionGenerator extends AbstractGenerator {

    private static final String LEGACY_CREATE_REGIST_URL = "https://ftms-stag.fpt.net/bms/RegistrationMagt_v12/CreateRegist?refCode=";
    private static final int POLL_ATTEMPTS = 15;
    private static final long POLL_INTERVAL_MS = 1000;
    // Presence of this cookie means the legacy app's own Forms/Cookie auth
    // middleware has run and minted its session — the "are we logged into
    // the legacy area yet" signal to poll for.
    private static final String LEGACY_COOKIE_NAME = "FTIBMS-Auth";

    public GetBmsLegacySessionGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "auth.get-bms-legacy-session"; }
    @Override public String displayName() { return "Lấy Session BMS legacy (Selenium login)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "Đăng nhập BMS rồi mở trang legacy bms/RegistrationMagt_v12/CreateRegist (cùng phiên) để lấy cookie jar (FTIBMS-Auth/FTIBMS) dùng cho bms.insert-registration. KHÔNG dùng chung được với auth.get-ivoice-session — 2 vùng auth tách biệt, xác nhận live 2026-09-07.";
    }
    @Override public String signature()   { return "login(username, password) -> visit CreateRegist?refCode=... -> full cookie header string"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").required().placeholder("vd: thitlh@fpt.com"),
                ParamSpec.password("password").required()
                        .defaultValue("ISC22QC")
                        .hint("mã bypass OTP dùng chung trên STG"),
                ParamSpec.text("refCode").required().placeholder("CH00085411_96363")
                        .hint("chỉ cần 1 RefCode hợp lệ để có URL vào được vùng bảo vệ — cookie trả về không gắn riêng với RefCode này")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");
        String refCode = Params.requireString(p, "refCode");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "login(" + username + ") via Selenium, then warm up BMS legacy session");

        String cookieHeader = automation.computeWithDriver(driver -> {
            new LoginPage(driver).login(username, password);
            driver.get(LEGACY_CREATE_REGIST_URL + refCode);
            for (int i = 0; i < POLL_ATTEMPTS; i++) {
                boolean hasLegacyCookie = driver.manage().getCookies().stream()
                        .anyMatch(c -> c.getName().equals(LEGACY_COOKIE_NAME));
                if (hasLegacyCookie) {
                    return joinCookies(driver);
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            String cookieNames = driver.manage().getCookies().stream()
                    .map(Cookie::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
            throw new GeneratorException(
                    "Mở CreateRegist?refCode=" + refCode + " xong nhưng sau " + (POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000)
                    + "s không thấy cookie \"" + LEGACY_COOKIE_NAME + "\" — currentUrl=" + driver.getCurrentUrl()
                    + " · cookies present=[" + cookieNames + "]");
        });

        trace(log, "BMS legacy session ready, cookie header length=" + cookieHeader.length());

        return result()
                .put("username", username)
                .put("cookieHeader", cookieHeader)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private String joinCookies(WebDriver driver) {
        Set<Cookie> cookies = driver.manage().getCookies();
        return cookies.stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));
    }
}
