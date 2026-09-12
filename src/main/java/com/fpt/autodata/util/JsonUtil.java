package com.fpt.autodata.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads JSON resources off the classpath.
 *
 * <p>Place files under {@code src/main/resources/} (e.g.
 * {@code Services.json}) so they ship inside the application JAR.</p>
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static <T> T loadJsonData(String resourceName, Class<T> type) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + resourceName);
            }
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read JSON: " + resourceName, e);
        }
    }
}
