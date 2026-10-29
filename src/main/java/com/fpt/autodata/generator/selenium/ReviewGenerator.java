package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.service.automation.FtmsAutomation;
import org.springframework.stereotype.Component;
import pages.ReviewPage;

import java.util.List;
import java.util.Map;

@Component
public class ReviewGenerator extends AbstractGenerator {

    public ReviewGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "review"; }
    @Override public String displayName() { return "Review Order"; }
    @Override public String category()    { return "Review"; }
    @Override public String description() { return "Reviewer step — submits Auto note."; }
    @Override public String signature()   { return "review(): void"; }

    @Override
    public GenerationResult generate(Map<String, Object> params) {
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "review() · note=\"Auto ghi chu\"");

        automation.withDriver(driver -> new ReviewPage(driver).review());

        return result().put("status", "Phê duyệt").log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
