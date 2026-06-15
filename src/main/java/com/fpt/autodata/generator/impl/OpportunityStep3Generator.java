package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.CreatePage;

import java.util.List;
import java.util.Map;

@Component
public class OpportunityStep3Generator extends AbstractGenerator {

    public OpportunityStep3Generator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "opportunity.step3"; }
    @Override public String displayName() { return "Create Opportunity · Step 3"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() { return "Assign reviewer (TP) and approver (BGĐ)."; }
    @Override public String signature()   { return "createStep3(truongPhong, banGiamDoc): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("truongPhong").label("truongPhong").required().defaultValue("Dungntm10"),
                ParamSpec.text("banGiamDoc").label("banGiamDoc").required().defaultValue("Huyennhk")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String tp = Params.requireString(p, "truongPhong");
        String bg = Params.requireString(p, "banGiamDoc");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "createStep3(" + tp + ", " + bg + ")");

        automation.withDriver(driver -> new CreatePage(driver).createStep3(tp, bg));

        return result().put("reviewer", tp).put("approver", bg)
                .log(log).durationMs(System.currentTimeMillis() - t0).build();
    }
}
