package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.LoginPage;

import java.util.List;
import java.util.Map;

/**
 * Wraps {@link pages.LoginPage#login(String, String)}.
 */
@Component
public class LoginGenerator extends AbstractGenerator {

    public LoginGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "login"; }
    @Override public String displayName() { return "Login"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() { return "Authenticate against the FTMS portal."; }
    @Override public String signature()   { return "login(username: String, password: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("username").label("username").required().placeholder("sale@fpt.com"),
                ParamSpec.password("password").label("password").required()
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String username = Params.requireString(p, "username");
        String password = Params.requireString(p, "password");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "open https://ftms-stag.fpt.net/bms/OppsMagt/Create");
        trace(log, "login(" + username + ")");

        automation.withDriver(driver -> new LoginPage(driver).login(username, password));

        return result()
                .put("user", username)
                .put("masked", "*".repeat(Math.min(password.length(), 8)))
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }
}
