package com.fpt.autodata.generator.impl;

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
 *   <li><b>{@code access_token} cookie</b> (this one) — set only after
 *       navigating an authenticated session to plain {@code /bmsv2} and
 *       letting its SPA finish its own bootstrap; contains the full
 *       role/division claims and is accepted by
 *       {@code ftisapi-stag.fpt.net/bms-service/api/*} — verified against a
 *       real endpoint before wiring this in.</li>
 * </ul>
 *
 * <p>Frontend wires this to a "Lấy Token" button: pick a username (role),
 * click, the returned token auto-fills the Bearer Token field on the
 * opportunity.api card.</p>
 */
@Component
public class GetTokenGenerator extends AbstractGenerator {

    private static final String TOKEN_COOKIE = "access_token";
    private static final String BMSV2_URL = "https://ftms-stag.fpt.net/bmsv2";
    private static final int POLL_ATTEMPTS = 12;
    private static final long POLL_INTERVAL_MS = 1000;

    public GetTokenGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "auth.get-token"; }
    @Override public String displayName() { return "Lấy Token (Selenium login)"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() {
        return "Đăng nhập thật qua Selenium rồi lấy bearer token nghiệp vụ từ cookie \"access_token\" sau khi mở /bmsv2.";
    }
    @Override public String signature()   { return "login(username, password) -> access_token cookie"; }

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
                Cookie cookie = driver.manage().getCookieNamed(TOKEN_COOKIE);
                if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            return null;
        });

        if (token == null) {
            throw new GeneratorException(
                    "Đăng nhập xong, mở /bmsv2 rồi nhưng sau " + (POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000)
                    + "s vẫn không thấy cookie \"" + TOKEN_COOKIE + "\" — thử lại, hoặc kiểm tra tên cookie có đổi không.");
        }
        trace(log, "token found in cookie \"" + TOKEN_COOKIE + "\", length=" + token.length());

        return result()
                .put("username", username)
                .put("token", token)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }
}
