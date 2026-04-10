package com.intellibase.server.service.doc.ocr;

import lombok.Getter;

@Getter
public class OcrException extends RuntimeException {

    private final boolean retryable;

    public OcrException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public OcrException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }
}
