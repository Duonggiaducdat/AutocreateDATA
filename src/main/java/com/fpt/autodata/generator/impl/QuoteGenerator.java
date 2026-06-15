package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.BaoGia;

import java.util.List;
import java.util.Map;

@Component
public class QuoteGenerator extends AbstractGenerator {

    public QuoteGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "quote"; }
    @Override public String displayName() { return "Add Quote"; }
    @Override public String category()    { return "Quote"; }
    @Override public String description() { return "Attach dummy.pdf and submit a quotation."; }
    @Override public String signature()   { return "addQuote(): void"; }

    @Override
    public GenerationResult generate(Map<String, Object> params) {
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "addQuote()");

        automation.withDriver(driver -> new BaoGia(driver).addQuote());

        return result().put("attached", "dummy.pdf").log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
