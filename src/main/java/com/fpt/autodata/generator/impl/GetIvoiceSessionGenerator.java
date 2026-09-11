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
 * Logs into BMS via Selenium, then visits the legacy FTI-IVOICE app
 * ({@code ftms-stag.fpt.net/ivoice/}) in the SAME session so its own
 * cookie-based auth (piggybacking on the live CAS session, same pattern
 * already proven for the CM app in {@link GetCmTokenGenerator}) sets
 * {@code IVOICE_USER_COOKIE}/{@code IVOICE_USER_SESSION} — both ASP.NET Core
 * Data-Protection–encrypted blobs, opaque, not JWTs, so unlike every other
 * "get token" generator here there is no single bearer value to extract.
 *
 * <p>IVOICE mutation endpoints (see {@code registration.create-detail-ivoice},
 * {@code ivoice.get-ward}, {@code ivoice.get-phone-booked}) are cookie-jar
 * authed, not bearer-token authed — confirmed 2026-09-03 from a real captured
 * {@code POST /ivoice/Registration/InsertRegistrationIframe} request that
 * carried a full cookie header and no {@code Authorization} header at all.
 * This generator's whole job is producing that cookie header for them to
 * reuse, since a fresh {@code HttpClient} call has no browser session of its
 * own.</p>
 *
 * <p><b>Does NOT cover the legacy {@code bms/RegistrationMagt_v12/*} area</b>
 * (InsertRegistration) — confirmed live 2026-09-07 that this jar gets HTTP
 * 401 there, and visiting that legacy area's protected route in the SAME
 * browser session actually WIPES these IVOICE cookies (the legacy area
 * triggers its own fresh sign-in that signs the other auth scheme out) —
 * confirmed by the user this matches real browser behavior too (clicking
 * into IVOICE from BMS is a real navigation to a separate auth area, not an
 * SPA-internal transition). Use {@link GetBmsLegacySessionGenerator}
 * (`auth.get-bms-legacy-session`) for that area instead — the two cookie
 * jars are NOT interchangeable and calling code must fetch both separately
 * when a flow needs both areas (see memory/api-contracts.md).</p>
 *
 * <p>Returns the ENTIRE cookie jar (semicolon-joined {@code name=value}
 * pairs) rather than cherry-picking known cookie names — the real captured
 * request itself sent every cookie the browser had (analytics, todo_remind,
 * etc. included), and IVOICE's own auth middleware reads more than just the
 * two IVOICE-prefixed ones (it re-validates {@code access_token}/{@code
 * BMS_access_token} too). Safer to replicate the whole jar than guess which
 * subset matters.</p>
 */
@Component
public class GetIvoiceSessionGenerator extends AbstractGenerator {

    private static final String BMSV2_URL = "https://ftms-stag.fpt.net/bmsv2";
    private static final String IVOICE_URL = "https://ftms-stag.fpt.net/ivoice/";
    private static final int POLL_ATTEMPTS = 30;
    private static final long POLL_INTERVAL_MS = 1000;
    // Any cookie name starting with this prefix means the IVOICE app's own
    // auth middleware has run and minted its session — that's the "are we
    // logged into ivoice yet" signal to poll for.
    private static final String IVOICE_COOKIE_PREFIX = "IVOICE_USER_";

    public GetIvoiceSessionGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "auth.get-ivoice-session"; }
    @Override public String displayName() { return "Lấy Session IVOICE (Selenium login)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "Đăng nhập BMS rồi mở thêm FTI-IVOICE (cùng phiên) để lấy cookie jar dùng cho các API tạo/tra cứu phiếu triển khai (InsertRegistrationIframe, GetWard, GetPhoneBookedNumber). KHÔNG dùng được cho legacy bms/RegistrationMagt_v12/* (dùng auth.get-bms-legacy-session).";
    }
    @Override public String signature()   { return "login(username, password) -> full cookie header string"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").required().placeholder("vd: thitlh@fpt.com"),
                ParamSpec.password("password").required()
                        .defaultValue("ISC22QC")
                        .hint("mã bypass OTP dùng chung trên STG")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "login(" + username + ") via Selenium, then warm up IVOICE session");

        String cookieHeader = automation.computeWithDriver(driver -> {
            new LoginPage(driver).login(username, password);
            driver.get(BMSV2_URL);
            driver.get(IVOICE_URL);
            for (int i = 0; i < POLL_ATTEMPTS; i++) {
                boolean hasIvoiceCookie = driver.manage().getCookies().stream()
                        .anyMatch(c -> c.getName().startsWith(IVOICE_COOKIE_PREFIX));
                if (hasIvoiceCookie) {
                    return joinCookies(driver);
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            String cookieNames = driver.manage().getCookies().stream()
                    .map(Cookie::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
            throw new GeneratorException(
                    "Mở /ivoice/ xong nhưng sau " + (POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000)
                    + "s không thấy cookie nào tên bắt đầu \"" + IVOICE_COOKIE_PREFIX + "\" — currentUrl=" + driver.getCurrentUrl()
                    + " · cookies present=[" + cookieNames + "]");
        });

        trace(log, "IVOICE session ready, cookie header length=" + cookieHeader.length());

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
