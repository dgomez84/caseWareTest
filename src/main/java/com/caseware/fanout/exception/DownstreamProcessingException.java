package com.caseware.fanout.exception;

/**
 * Base exception for failures occurring while invoking the downstream Engagement Management System.
 */
public abstract class DownstreamProcessingException extends RuntimeException {

    protected DownstreamProcessingException(String message) {
        super(message);
    }

    protected DownstreamProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract boolean isRetryable();
}
