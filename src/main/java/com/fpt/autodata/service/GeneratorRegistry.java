package com.fpt.autodata.service;

import com.fpt.autodata.generator.Generator;
import com.fpt.autodata.generator.GeneratorException;
import com.fpt.autodata.model.GeneratorMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Strategy resolver for {@link Generator} beans.
 *
 * <p>All {@code Generator} implementations in the Spring context are
 * auto-discovered and indexed by their {@link Generator#type()}. This is the
 * single source of truth for the {@code GET /api/generators} catalog and the
 * {@code POST /api/generate} dispatch.</p>
 */
@Service
public class GeneratorRegistry {

    private final Map<String, Generator> byType;

    public GeneratorRegistry(List<Generator> generators) {
        this.byType = Collections.unmodifiableMap(
                generators.stream()
                        .sorted(Comparator
                                .comparing(Generator::category)
                                .thenComparing(Generator::displayName))
                        .collect(Collectors.toMap(
                                Generator::type,
                                g -> g,
                                (a, b) -> {
                                    throw new IllegalStateException(
                                            "Duplicate Generator type: " + a.type());
                                },
                                LinkedHashMap::new
                        ))
        );
    }

    /** Resolve by type or 404. */
    public Generator resolve(String type) {
        Generator g = byType.get(type);
        if (g == null) {
            throw new GeneratorException(
                    "Unknown generator type: " + type,
                    HttpStatus.NOT_FOUND, null);
        }
        return g;
    }

    /** Catalog metadata for the frontend. */
    public List<GeneratorMetadata> catalog() {
        return byType.values().stream()
                .map(GeneratorMetadata::from)
                .collect(Collectors.toUnmodifiableList());
    }

    /** Snapshot for diagnostics. */
    public Map<String, Generator> all() {
        return Collections.unmodifiableMap(byType);
    }
}
