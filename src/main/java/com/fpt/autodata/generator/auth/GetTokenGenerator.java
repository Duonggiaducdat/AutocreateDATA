package com.fpt.autodata.generator.auth;

import com.fpt.autodata.generator.selenium.AbstractGenerator;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.openqa.selenium.Cookie;
import org.springframework.stereotype.Component;
import pages.LoginPage;

import java.util.List;
import java.util.Map;

/**
 * Logs into BMS via the real Selenium {@link LoginPage} (same flow already
 * proven elsewhere in this project), then reads the real business bearer
 * token out of the {@code access_token} cookie the bmsv2 SPA sets once it
 * finishes its own login bootstrap.
 *
 * <p>Confirmed live 2026-08-25 — see {@code memory/api-contracts.md} "Login
 * flow" section for the full story of what this ruled out first:</p>
 * <ul>
 *   <li>{@code localStorage}/{@code sessionStorage} — never held the token.</li>
 *   <li>{@code FTIBMS-Auth} cookie — an ASP.NET Core Data Protection–encrypted
 *       blob ({@code CfDJ...} prefix), not a usable bearer token.</li>
 *   <li>{@code access_token_sso} cookie / the {@code POST /api/bam/auth/sso}
 *       ticket-exchange {@code accessToken} — a real JWT, but only valid
 *       against {@code ftms-stag.fpt.net/api/bam/*} (identity/BAM layer),
 *       401s against the real business API.</li>
 *   <li><b>{@code access_token} cookie</b> — set only after navigating an
 *       authenticated session to plain {@code /bmsv2} and letting its SPA
 *       finish its own bootstrap; contained the full role/division claims
 *       and was accepted by {@code ftisapi-stag.fpt.net/bms-service/api/*}
 *       — verified against a real endpoint before wiring this in.</li>
 * </ul>
 *
 * <p><b>2026-09-03: the site was redeployed and renamed this cookie.</b>
 * {@code access_token} now holds only generic identity claims (name,
 * nameidentifier, sid — no {@code DivisionCode}/{@code UserName}/role), the
 * same shape {@code access_token_sso} always had. The full business token
 * moved to a NEW cookie, <b>{@code BMS_access_token}</b> — confirmed live by
 * decoding both cookies' JWT payloads side by side in a real logged-in
 * session (`BMS_access_token` has {@code DepartmentCode}, {@code
 * DivisionCode}, {@code UserId}, {@code UserName}, the role claim; plain
 * {@code access_token} does not). Try both, newest name first, so this
 * survives if the site renames it again or a build still serves the old
 * name.</p>
 *
 * <p>Frontend wires this to a "Lấy Token" button: pick a username (role),
 * click, the returned token auto-fills the Bearer Token field on the
 * opportunity.api card.</p>
 */
@Component
public class GetTokenGenerator extends AbstractGenerator {

    // Newest-known-name first — see the 2026-09-03 rename note above.
    private static final List<String> TOKEN_COOKIE_CANDIDATES = List.of("BMS_access_token", "access_token");
    private static final String BMSV2_URL = "https://ftms-stag.fpt.net/bmsv2";
    private static final int POLL_ATTEMPTS = 30;
    private static final long POLL_INTERVAL_MS = 1000;

    public GetTokenGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "auth.get-token"; }
    @Override public String displayName() { return "Lấy Token (Selenium login)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "Đăng nhập thật qua Selenium rồi lấy bearer token nghiệp vụ từ cookie \"BMS_access_token\" (hoặc \"access_token\" nếu site đổi lại) sau khi mở /bmsv2.";
    }
    @Override public String signature()   { return "login(username, password) -> BMS_access_token / access_token cookie"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").required().placeholder("vd: bgd01@fpt.com"),
                ParamSpec.password("password").required()
                        .defaultValue("REDACTED_OTP_CODE")
                        .hint("mã bypass OTP dùng chung trên STG — xem memory/api-contracts.md nếu account khác không nhận")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "login(" + username + ") via Selenium");

        String token = automation.computeWithDriver(driver -> {
            new LoginPage(driver).login(username, password);
            driver.get(BMSV2_URL);
            for (int i = 0; i < POLL_ATTEMPTS; i++) {
                for (String name : TOKEN_COOKIE_CANDIDATES) {
                    Cookie cookie = driver.manage().getCookieNamed(name);
                    if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        return cookie.getValue();
                    }
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            // Throw HERE (while the driver is still alive) so
            // FtmsAutomation.computeWithDriver's own catch block captures an
            // error screenshot — throwing after this lambda returns null
            // happens after the driver already quit, so the screenshot-on-error
            // mechanism silently never fires (confirmed live 2026-09-03: a
            // real Sale01 timeout produced zero diagnostics before this fix).
            // Current URL is included directly since it costs nothing and
            // immediately distinguishes "stuck on login" (OTP bypass
            // rejected / device not yet trusted, see memory/api-contracts.md
            // "Login flow") from "on bmsv2 but cookie name changed".
            String cookieNames = driver.manage().getCookies().stream()
                    .map(Cookie::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
            throw new GeneratorException(
                    "Đăng nhập xong, mở /bmsv2 rồi nhưng sau " + (POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000)
                    + "s vẫn không thấy cookie nào trong " + TOKEN_COOKIE_CANDIDATES + " — currentUrl=" + driver.getCurrentUrl()
                    + " · cookies present=[" + cookieNames + "] — thử lại, hoặc kiểm tra tên cookie có đổi không.");
        });
        trace(log, "token found (checked cookies " + TOKEN_COOKIE_CANDIDATES + "), length=" + token.length());

        return result()
                .put("username", username)
                .put("token", token)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }
}
