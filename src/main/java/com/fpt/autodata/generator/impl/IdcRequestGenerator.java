package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.RequestIDC;

import java.util.List;
import java.util.Map;

@Component
public class IdcRequestGenerator extends AbstractGenerator {

    public IdcRequestGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "idc.request"; }
    @Override public String displayName() { return "Create IDC Request"; }
    @Override public String category()    { return "IDC"; }
    @Override public String description() { return "Branches by service tag (Colocation / Dedicated / Rack)."; }
    @Override public String signature()   { return "createGeneralInformation() + createRequest(): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.enumOf("requestType", Catalog.IDC_REQUEST_TYPES)
                        .label("requestType").hint("expected branch").defaultValue("Colocation")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String requestType = Params.optString(p, "requestType", "Colocation");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "createGeneralInformation() + createRequest() (branch=" + requestType + ")");

        automation.withDriver(driver -> {
            RequestIDC idc = new RequestIDC(driver);
            idc.createGeneralInformation();
            idc.createRequest();
        });

        return result().put("branch", requestType).log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
