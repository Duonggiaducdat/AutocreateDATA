package com.fpt.autodata.audit;

import java.util.Map;

public record AuditEntry(
        String timestamp,
        String ip,
        String type,
        Map<String, Object> params,
        boolean success,
        long durationMs,
        String summary
) {}
