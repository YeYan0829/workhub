package dev.workhub.messaging;

/** Short local SQL only. RemoteWorkHandler uses the separate cross-transaction execution path. */
public interface WorkHandler {
    String kind();

    default void lockBusiness(WorkJobRepository.Job job) {}

    default void terminalFailure(WorkJobRepository.Job job, String code) {}

    Result execute(WorkJobRepository.Job job);

    record Result(String reference, String hash) {
        public Result {
            if (reference == null
                    || reference.isBlank()
                    || reference.length() > 512
                    || hash == null
                    || !hash.matches("[0-9a-f]{64}"))
                throw new WorkFailure(false, "INVALID_RESULT");
        }
    }
}
