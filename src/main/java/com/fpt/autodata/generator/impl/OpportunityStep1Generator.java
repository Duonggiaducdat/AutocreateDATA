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
public class OpportunityStep1Generator extends AbstractGenerator {

    public OpportunityStep1Generator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "opportunity.step1"; }
    @Override public String displayName() { return "Create Opportunity · Step 1"; }
    @Override public String category()    { return "Opportunity"; }
    @Override public String description() { return "Add services, set rack option and end-date."; }
    @Override public String signature()   { return "createStep1(serviceGroup, serviceTypes[], options, name): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.enumOf("serviceGroup", Catalog.SERVICE_GROUPS)
                        .required().defaultValue("Trung tâm dữ liệu"),
                ParamSpec.multiEnum("serviceTypes", Catalog.SERVICE_TYPES)
                        .required().hint("multi").defaultValue(List.of("Colocation", "Rack")),
                ParamSpec.enumOf("options", Catalog.RACK_OPTIONS)
                        .hint("rack qty").defaultValue("Nhiều hơn hoặc bằng 1 tủ"),
                ParamSpec.text("name").required().defaultValue("Dat test").placeholder("opportunity name prefix")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String group        = Params.requireString(p, "serviceGroup");
        List<String> types  = Params.optList(p, "serviceTypes", List.of());
        String options      = Params.optString(p, "options", "n/a");
        String name         = Params.requireString(p, "name");

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "createStep1(" + group + ", " + types + ", " + options + ", " + name + ")");

        automation.withDriver(driver ->
                new CreatePage(driver).createStep1(group, types, options, name));

        return result()
                .put("opportunityName", name)
                .put("servicesAdded", types)
                .put("rackOption", options)
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }
}
