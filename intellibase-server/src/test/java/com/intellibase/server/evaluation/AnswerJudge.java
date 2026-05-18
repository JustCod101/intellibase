package com.intellibase.server.evaluation;

public interface AnswerJudge {

    JudgeScore judge(GoldenQaCase goldenCase, RetrievalRunRecord runRecord);
}
