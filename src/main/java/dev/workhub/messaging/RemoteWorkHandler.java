package dev.workhub.messaging;

/** Network phases are never invoked inside the executor's transaction. */
public interface RemoteWorkHandler extends WorkHandler {
    record Step(Object request, Result result, java.time.Instant retryAt, boolean attempted) {
        public Step {
            if ((request == null ? 0 : 1) + (result == null ? 0 : 1) + (retryAt == null ? 0 : 1)
                            != 1
                    || attempted && retryAt == null)
                throw new IllegalArgumentException(
                        "A remote step must call, finish, or durably defer");
        }

        public static Step call(Object request) {
            return new Step(request, null, null, false);
        }

        public static Step retry(java.time.Instant due) {
            return new Step(null, null, due, false);
        }

        public static Step retryRead(java.time.Instant due) {
            return new Step(null, null, due, true);
        }

        public static Step done(String reference) {
            return new Step(
                    null,
                    new Result(reference, dev.workhub.common.Inputs.hash(reference)),
                    null,
                    false);
        }
    }

    Step prepare(WorkJobRepository.Job job);

    Object perform(Object request);

    Step complete(WorkJobRepository.Job job, Object request, Object response);

    /** Cover the bounded remote operation plus its final database transaction. */
    default int executionLeaseSeconds() { return 60; }

    /** Opt-in only for immutable, identity/hash-checked remote effects (never GitHub PATCH). */
    default boolean safeToRetryFailure() { return false; }

    default Result execute(WorkJobRepository.Job job) {
        throw new IllegalStateException("Remote work requires the cross-transaction executor");
    }
}
