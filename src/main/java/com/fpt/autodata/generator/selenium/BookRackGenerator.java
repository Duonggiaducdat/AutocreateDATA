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
public class BookRackGenerator extends AbstractGenerator {

    public BookRackGenerator(FtmsAutomation automation) { super(automation); }

    @Override public String type()        { return "idc.book-rack"; }
    @Override public String displayName() { return "Book Rack"; }
    @Override public String category()    { return "IDC"; }
    @Override public String description() { return "Reserve a rack in the chosen DC (uploads dummy.pdf, 100W, +1 month)."; }
    @Override public String signature()   { return "createBookRack(location: String): void"; }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.enumOf("location", Catalog.IDC_LOCATIONS)
                        .required().defaultValue("FORNIX - HN02")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String location = Params.requireString(p, "location");
        long t0 = System.currentTimeMillis();
        List<String> log = newLog();
        trace(log, "createBookRack(" + location + ")");

        automation.withDriver(driver -> new RequestIDC(driver).createBookRack(location));

        return result().put("location", location).put("power", "100W").log(log)
                .durationMs(System.currentTimeMillis() - t0).build();
    }
}
