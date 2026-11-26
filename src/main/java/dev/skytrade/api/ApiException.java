package dev.skytrade.api;

/** Failure fetching or validating an API response. */
public final class ApiException extends Exception {
    public final int status;
    public final boolean retryable;

    public ApiException(String message, int status, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.retryable = true;
    }
}
