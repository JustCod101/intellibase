package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EvaluationFixtures {

    private EvaluationFixtures() {
    }

    public static List<GoldenQaCase> loadGoldenCases(ObjectMapper objectMapper, String resourcePath) throws IOException {
        return loadJsonl(objectMapper, resourcePath, GoldenQaCase.class);
    }

    public static List<RetrievalRunRecord> loadRunRecords(ObjectMapper objectMapper, String resourcePath) throws IOException {
        return loadJsonl(objectMapper, resourcePath, RetrievalRunRecord.class);
    }

    private static <T> List<T> loadJsonl(ObjectMapper objectMapper, String resourcePath, Class<T> type) throws IOException {
        InputStream inputStream = EvaluationFixtures.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        List<T> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                    result.add(objectMapper.readValue(line, type));
                }
            }
        }
        return result;
    }
}
