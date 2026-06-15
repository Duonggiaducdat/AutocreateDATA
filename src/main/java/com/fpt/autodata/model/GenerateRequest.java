package com.fpt.autodata.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Inbound request for {@code POST /api/generate}.
 * <pre>
 * {
 *   "type":   "login",
 *   "params": { "username": "...", "password": "..." }
 * }
 * </pre>
 */
public record GenerateRequest(
        @NotBlank(message = "type is required") String type,
        Map<String, Object> params
) {}
