package com.fpt.autodata.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fpt.autodata.generator.GenerationResult;

/**
 * Envelope for generator responses.
 * <pre>
 * { "result": { ... } }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerateResponse(GenerationResult result) {

    public static GenerateResponse of(GenerationResult result) {
        return new GenerateResponse(result);
    }
}
