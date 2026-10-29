package com.caseware.fanout.exception;

/**
 * Transient failure from the downstream engagement service (e.g. network timeout, 502/503/504).
 */
public class RetryableDownstreamException extends DownstreamProcessingException {

    public RetryableDownstreamException(String message) {
        super(message);
    }

    public RetryableDownstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
