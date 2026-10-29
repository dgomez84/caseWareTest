package com.caseware.fanout.exception;

/**
 * Permanent failure from the downstream engagement service (e.g. corrupt archive, 400/404/422).
 * Must bypass retries and route directly to the Dead-Letter sink.
 */
public class NonRetryableDownstreamException extends DownstreamProcessingException {

    public NonRetryableDownstreamException(String message) {
        super(message);
    }

    public NonRetryableDownstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
