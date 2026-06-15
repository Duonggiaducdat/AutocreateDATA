package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.LogoutPage;

import java.util.List;
import java.util.Map;

@Component
public class LogoutGenerator extends AbstractGenerator {

    public LogoutGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "logout"; }
    @Override public String displayName() { return "Logout"; }
    @Override public String category()    { return "Authentication"; }
    @Override public String description() { return "End the current FTMS session."; }
    @Override public String signature()   { return "Logout(): void"; }

    @Override
    public GenerationResult generate(Map<String, Object> params) {
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "navbarDropdownUser → Đăng xuất");

        automation.withDriver(driver -> new LogoutPage(driver).Logout());

        return result().log(log).durationMs(System.currentTimeMillis() - t0).build();
    }
}
