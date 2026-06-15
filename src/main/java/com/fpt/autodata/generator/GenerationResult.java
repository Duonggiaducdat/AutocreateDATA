package com.fpt.autodata.generator;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured outcome of a single generator run.
 *
 * <p>Returned by every {@link Generator#generate(Map)} implementation. Use
 * {@link Builder} for construction.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class GenerationResult {

    private final String type;
    private final Instant ranAt;
    private final long durationMs;
    private final Map<String, Object> data;
    private final List<String> log;

    private GenerationResult(Builder b) {
        this.type = b.type;
        this.ranAt = b.ranAt;
        this.durationMs = b.durationMs;
        this.data = b.data == null ? Map.of() : Map.copyOf(b.data);
        this.log = b.log == null ? List.of() : List.copyOf(b.log);
    }

    public String getType() { return type; }
    public Instant getRanAt() { return ranAt; }
    public long getDurationMs() { return durationMs; }
    public Map<String, Object> getData() { return data; }
    public List<String> getLog() { return log; }

    public static Builder builder(String type) { return new Builder(type); }

    public static final class Builder {
        private final String type;
        private Instant ranAt = Instant.now();
        private long durationMs;
        private Map<String, Object> data = new LinkedHashMap<>();
        private List<String> log = new ArrayList<>();

        private Builder(String type) { this.type = type; }

        public Builder durationMs(long v)  { this.durationMs = v; return this; }
        public Builder ranAt(Instant v)    { this.ranAt = v; return this; }
        public Builder data(Map<String, Object> v) { this.data = v; return this; }
        public Builder put(String k, Object v) {
            if (v != null) {
                this.data.put(k, v);
            }
            return this;
        }
        public Builder log(List<String> v) { this.log = v; return this; }
        public Builder addLog(String line) { this.log.add(line); return this; }

        public GenerationResult build() { return new GenerationResult(this); }
    }
}
