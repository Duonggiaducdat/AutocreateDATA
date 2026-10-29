package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.RequestCM;

import java.util.List;
import java.util.Map;

@Component
public class CmRequestGenerator extends AbstractGenerator {

    public CmRequestGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "cm.request"; }
    @Override public String displayName() { return "Request CM"; }
    @Override public String category()    { return "CM Request"; }
    @Override public String description() { return "Create contract & PL, send deployment request."; }
    @Override public String signature()   { return "request(maYC: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("maYC").required().placeholder("YC#########").defaultValue("YC1234567890")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String maYC = Params.requireString(p, "maYC");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "request(" + maYC + ")");

        automation.withDriver(driver -> new RequestCM(driver).request(maYC));

        return result().put("maYC", maYC).log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
