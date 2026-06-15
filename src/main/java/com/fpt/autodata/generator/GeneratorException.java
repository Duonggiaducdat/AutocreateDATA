package com.fpt.autodata.generator;

import org.springframework.http.HttpStatus;

/**
 * Domain-level failure for a generator. The status hint is honoured by
 * {@link com.fpt.autodata.controller.GlobalExceptionHandler}.
 */
public class GeneratorException extends RuntimeException {

    private final HttpStatus status;

    public GeneratorException(String message) {
        this(message, HttpStatus.BAD_REQUEST, null);
    }

    public GeneratorException(String message, Throwable cause) {
        this(message, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    public GeneratorException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
