package com.fpt.autodata.generator.selenium;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.service.automation.FtmsAutomation;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared scaffolding for all concrete generators: timing, log buffer, and
 * access to the {@link FtmsAutomation} facade.
 */
public abstract class AbstractGenerator implements Generator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected final FtmsAutomation automation;

    protected AbstractGenerator(FtmsAutomation automation) {
        this.automation = automation;
    }

    @Override
    public List<com.fpt.autodata.generator.ParamSpec> params() {
        return List.of();
    }

    /** Helper to start a result builder pre-stamped with the type and start time. */
    protected GenerationResult.Builder result() {
        return GenerationResult.builder(type());
    }

    /** Append a timestamped trace line to the log list. */
    protected void trace(List<String> log, String line) {
        log.add("[" + java.time.LocalDateTime.now().format(TS) + "] " + line);
    }

    protected List<String> newLog() { return new ArrayList<>(); }
}
