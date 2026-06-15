package com.fpt.autodata.service;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.model.GenerateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Orchestrates a single generator run: resolves the strategy, validates input,
 * times execution, and surfaces a structured {@link GenerationResult}.
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final GeneratorRegistry registry;

    public GenerationService(GeneratorRegistry registry) {
        this.registry = registry;
    }

    public GenerationResult execute(GenerateRequest request) {
        Generator generator = registry.resolve(request.type());
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();

        long t0 = System.currentTimeMillis();
        try {
            log.info("generate type={} params.keys={}", request.type(), params.keySet());
            GenerationResult result = generator.generate(params);
            long elapsed = System.currentTimeMillis() - t0;
            // Backfill duration if the generator didn't set it.
            if (result.getDurationMs() == 0L) {
                result = GenerationResult.builder(result.getType() == null ? request.type() : result.getType())
                        .data(result.getData())
                        .log(result.getLog())
                        .durationMs(elapsed)
                        .ranAt(result.getRanAt())
                        .build();
            }
            log.info("generate type={} ok in {} ms", request.type(), result.getDurationMs());
            return result;
        } catch (GeneratorException ge) {
            log.warn("generate type={} failed: {}", request.type(), ge.getMessage());
            throw ge;
        } catch (RuntimeException e) {
            log.error("generate type={} crashed", request.type(), e);
            throw new GeneratorException("Generator crashed: " + e.getMessage(), e);
        }
    }
}
