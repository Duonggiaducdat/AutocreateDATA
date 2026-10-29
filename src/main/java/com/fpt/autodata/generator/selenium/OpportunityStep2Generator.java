package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.CreatePage;

import java.util.List;
import java.util.Map;

@Component
public class OpportunityStep2Generator extends AbstractGenerator {

    public OpportunityStep2Generator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "opportunity.step2"; }
    @Override public String displayName() { return "Create Opportunity · Step 2"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() { return "Pick the customer."; }
    @Override public String signature()   { return "createStep2(customerName: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("customerName").required()
                        .placeholder("name / mã KH / MST / CCCD")
                        .defaultValue("CTY TNHH ABC")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String customerName = Params.requireString(p, "customerName");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "createStep2(" + customerName + ")");

        automation.withDriver(driver -> new CreatePage(driver).createStep2(customerName));

        return result().put("customer", customerName).log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
