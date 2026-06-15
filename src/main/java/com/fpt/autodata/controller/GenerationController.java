package com.fpt.autodata.controller;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.model.GenerateRequest;
import com.fpt.autodata.model.GenerateResponse;
import com.fpt.autodata.model.GeneratorMetadata;
import com.fpt.autodata.service.GenerationService;
import com.fpt.autodata.service.GeneratorRegistry;
import jakarta.validation.Valid;
import models.ServiceGroup;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utils.JsonUtil;

import java.util.List;

/**
 * REST surface for the data generation tool.
 *
 * <ul>
 *   <li>{@code GET  /api/health}      — liveness</li>
 *   <li>{@code GET  /api/generators}  — full catalog (type, name, params)</li>
 *   <li>{@code GET  /api/services}    — service catalog from Services.json</li>
 *   <li>{@code POST /api/generate}    — execute one generator</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generationService;
    private final GeneratorRegistry registry;

    public GenerationController(GenerationService generationService, GeneratorRegistry registry) {
        this.generationService = generationService;
        this.registry = registry;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "status", "UP",
                "generators", registry.all().size()));
    }

    @GetMapping("/generators")
    public ResponseEntity<List<GeneratorMetadata>> catalog() {
        return ResponseEntity.ok(registry.catalog());
    }

    /**
     * Returns the {@code Services.json} content. Frontend uses this to:
     * <ul>
     *   <li>populate the service-group dropdown</li>
     *   <li>populate the service-type chips per chosen group</li>
     *   <li>decide whether to show the rack-option field</li>
     * </ul>
     */
    @GetMapping("/services")
    public ResponseEntity<ServiceGroup> services() {
        return ResponseEntity.ok(JsonUtil.loadJsonData("Services.json", ServiceGroup.class));
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerateRequest request) {
        GenerationResult result = generationService.execute(request);
        return ResponseEntity.ok(GenerateResponse.of(result));
    }
    @GetMapping("/generate")
    public ResponseEntity<?> info() {
        return ResponseEntity.ok(
                "Use POST /api/generate"
        );
    }
}
