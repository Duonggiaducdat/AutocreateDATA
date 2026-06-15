package com.fpt.autodata.generator;

import java.util.List;
import java.util.Map;

/**
 * Strategy contract for a single data generator.
 *
 * <p>Each generator describes itself via {@link #type()}, {@link #displayName()},
 * {@link #category()}, and a list of {@link ParamSpec parameter specs} so the
 * frontend can render its form dynamically. The actual work happens in
 * {@link #generate(Map)}, which returns a {@link GenerationResult}.</p>
 *
 * <p>Implementations are Spring {@code @Component}s and are auto-discovered by
 * {@link com.fpt.autodata.service.GeneratorRegistry}. To add a new generator,
 * create a new class — no other wiring is required.</p>
 */
public interface Generator {

    /** Stable, machine-readable identifier (e.g. {@code "login"}). */
    String type();

    /** Human-readable name shown on the UI card. */
    String displayName();

    /** UI grouping (e.g. {@code "Authentication"}, {@code "IDC"}). */
    String category();

    /** Short description rendered under the title. */
    default String description() { return ""; }

    /** Optional Java method signature for the UI hint line. */
    default String signature() { return type() + "(...)"; }

    /** Parameter specifications used by the UI to auto-render form fields. */
    List<ParamSpec> params();

    /**
     * Execute the generator with the given parameters.
     *
     * @param params validated, name-keyed parameter values
     * @return structured result with data, log, and timing
     * @throws GeneratorException for any business or runtime failure that should
     *         surface as a {@code 4xx}/{@code 5xx} response
     */
    GenerationResult generate(Map<String, Object> params) throws GeneratorException;
}
