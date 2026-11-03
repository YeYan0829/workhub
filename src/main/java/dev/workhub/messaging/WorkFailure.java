package dev.workhub.messaging;

/** Only an explicitly classified transient failure is retried as a business attempt. */
public class WorkFailure extends RuntimeException {
    private final boolean transientFailure;

    public WorkFailure(boolean transientFailure, String code) {
        super(code != null && code.matches("[A-Z][A-Z0-9_]{0,59}") ? code : "HANDLER_FAILURE");
        this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
        return transientFailure;
    }
}
