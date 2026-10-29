package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.RequestIDC;

import java.util.List;
import java.util.Map;

@Component
public class SendIdcGenerator extends AbstractGenerator {

    public SendIdcGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "idc.send"; }
    @Override public String displayName() { return "Send to FTEL IDC"; }
    @Override public String category()    { return "IDC"; }
    @Override public String description() { return "Approve and forward booking to FTEL IDC."; }
    @Override public String signature()   { return "sendIDC(maCH: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.text("maCH").required().placeholder("CH#########").defaultValue("CH00081978")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String maCH = Params.requireString(p, "maCH");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "sendIDC(" + maCH + ")");

        automation.withDriver(driver -> new RequestIDC(driver).sendIDC(maCH));

        return result().put("maCH", maCH).put("ftelStatus", "Sent").log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
