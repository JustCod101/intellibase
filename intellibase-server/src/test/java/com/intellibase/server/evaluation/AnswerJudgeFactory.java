package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class AnswerJudgeFactory {

    private AnswerJudgeFactory() {
    }

    public static AnswerJudge create(ObjectMapper objectMapper) {
        String apiKey = env("EVALUATION_LLM_JUDGE_API_KEY", "");
        if (apiKey.isBlank()) {
            return new HeuristicAnswerJudge();
        }
        String baseUrl = env("EVALUATION_LLM_JUDGE_BASE_URL", "https://api.openai.com/v1");
        String model = env("EVALUATION_LLM_JUDGE_MODEL", "gpt-4o-mini");
        return new OpenAiCompatibleAnswerJudge(objectMapper, apiKey, baseUrl, model);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
