package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.CreateAppendixContract;

import java.util.List;
import java.util.Map;

@Component
public class ContractRequestGenerator extends AbstractGenerator {

    public ContractRequestGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "contract.request"; }
    @Override public String displayName() { return "Request Contract Appendix"; }
    @Override public String category()    { return "Appendix Contract"; }
    @Override public String description() { return "Open a contract appendix request for a service line."; }
    @Override public String signature()   { return "request(type: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.enumOf("type", Catalog.CONTRACT_TYPES)
                        .required().defaultValue("Trung tâm dữ liệu - Colocation")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String type = Params.requireString(p, "type");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "request(" + type + ")");

        automation.withDriver(driver -> new CreateAppendixContract(driver).request(type));

        return result().put("contractType", type).log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
