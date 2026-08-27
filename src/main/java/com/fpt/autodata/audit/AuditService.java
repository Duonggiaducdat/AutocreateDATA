package com.fpt.autodata.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class AuditService {

    private static final Path LOG_FILE = Paths.get("audit.jsonl");
    private static final Set<String> SENSITIVE = Set.of(
            "passsale", "password", "pass", "secret",
            "token", "bearertoken", "bearer", "otp", "cookie"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    public synchronized void record(String ip, String type, Map<String, Object> rawParams,
                                     boolean success, long durationMs, String summary) {
        AuditEntry entry = new AuditEntry(
                Instant.now().toString(), ip, type, sanitize(rawParams),
                success, durationMs, summary
        );
        try {
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    public List<AuditEntry> recent(int n) {
        if (!Files.exists(LOG_FILE)) return List.of();
        try {
            List<String> lines = Files.readAllLines(LOG_FILE);
            List<AuditEntry> result = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && result.size() < n; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    result.add(mapper.readValue(line, AuditEntry.class));
                }
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            boolean sensitive = SENSITIVE.stream().anyMatch(s -> k.toLowerCase().contains(s));
            out.put(k, sensitive ? "***" : v);
        });
        return out;
    }
}
