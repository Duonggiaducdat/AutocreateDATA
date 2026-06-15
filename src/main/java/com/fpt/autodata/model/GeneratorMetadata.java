package com.fpt.autodata.model;

import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.ParamSpec;

import java.util.List;

/**
 * Public metadata describing a generator. Used by the frontend to render
 * cards and form fields without hardcoding any UI logic.
 */
public record GeneratorMetadata(
        String type,
        String displayName,
        String category,
        String description,
        String signature,
        List<ParamSpec> params
) {
    public static GeneratorMetadata from(Generator g) {
        return new GeneratorMetadata(
                g.type(),
                g.displayName(),
                g.category(),
                g.description(),
                g.signature(),
                g.params()
        );
    }
}
