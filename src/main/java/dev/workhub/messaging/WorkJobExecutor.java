package dev.workhub.messaging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class WorkJobExecutor {
    private final WorkJobRepository jobs;
    private final OutboxRepository outbox;
    private final FailureRepository failures;
    private final EnvelopeValidator validator;
    private final HandlerRegistry handlers;
    private final RetryPolicy retry;
    private final TransactionTemplate tx;

    public WorkJobExecutor(
            WorkJobRepository jobs,
            OutboxRepository outbox,
            FailureRepository failures,
            EnvelopeValidator validator,
            HandlerRegistry handlers,
            RetryPolicy retry,
            PlatformTransactionManager manager) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.failures = failures;
        this.validator = validator;
        this.handlers = handlers;
        this.retry = retry;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        tx.setTimeout(30);
        tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void handle(String messageId, String ingress, byte[] body) {
        UUID id = validator.identity(messageId);
        var envelope = id == null ? null : outbox.find(id, false);
        var job =
                envelope == null || envelope.jobId() == null
                        ? null
                        : jobs.find(envelope.jobId(), false);
        if (!validator.matches(body, ingress, envelope, job)) {
            tx.executeWithoutResult(s -> failures.quarantine(messageId, ingress, body));
            return;
        }
        WorkHandler selected = null;
        try {
            selected = handlers.require(job.handler());
        } catch (WorkFailure ignored) {
        }
        if (selected instanceof RemoteWorkHandler remote) {
            // Remote exceptions leave the lease and delivery intact. Generic local retry may never
            // turn an already-started external write into another attempt, or bypass token fencing.
            executeRemote(envelope, job, remote);
            return;
        }
        try {
            tx.executeWithoutResult(s -> execute(envelope, job));
        } catch (WorkFailure failure) {
            // All partial local effects have rolled back before a retry/failure can be committed.
            tx.executeWithoutResult(s -> disposeFailure(envelope, job, failure, body));
        }
        // TransactionTemplate has committed here; the caller may now ACK.
    }

    private void executeRemote(
            OutboxRepository.Envelope initial,
            WorkJobRepository.Job initialJob,
            RemoteWorkHandler handler) {
        UUID token = UUID.randomUUID();
        RemoteWorkHandler.Step step =
                tx.execute(
                        s -> {
                            handler.lockBusiness(
                                    initialJob); // Owns Project -> resource order, before
                            // Job/Outbox.
                            var job = jobs.find(initialJob.id(), true);
                            var envelope = outbox.find(initial.id(), true);
                            if (skip(job, envelope)) return null;
                            Instant due =
                                    job.nextAttempt().isAfter(envelope.available())
                                            ? job.nextAttempt()
                                            : envelope.available();
                            if (due.isAfter(jobs.now())) {
                                schedule(job, envelope, due, "EARLY_DELIVERY", false);
                                return null;
                            }
                            if (envelope.sequence() != job.sequence())
                                throw new IllegalStateException("Future sequence");
                            if (!jobs.claimExecution(job, token, handler.executionLeaseSeconds()))
                                throw new IllegalStateException("Execution lease busy");
                            var prepared = handler.prepare(job);
                            if (prepared.result() != null || prepared.retryAt() != null)
                                finishRemote(job, envelope, prepared);
                            return prepared;
                        });
        while (step != null && step.request() != null) {
            Object request = step.request();
            Object response;
            try {
                response = handler.perform(request); // Deliberately outside TransactionTemplate.
            } catch (WorkFailure failure) {
                if (!handler.safeToRetryFailure()) throw failure;
                tx.executeWithoutResult(s -> {
                    handler.lockBusiness(initialJob);
                    var current = jobs.find(initialJob.id(), true);
                    var delivery = outbox.find(initial.id(), true);
                    if (skip(current, delivery)) return;
                    if (!jobs.ownsExecution(current.id(), token))
                        throw new IllegalStateException("Lost lease cannot dispose another worker's execution");
                    disposeFailure(initial, initialJob, failure, initial.wire().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
                return;
            }
            step =
                    tx.execute(
                            s -> {
                                handler.lockBusiness(initialJob);
                                var job = jobs.find(initialJob.id(), true);
                                var envelope = outbox.find(initial.id(), true);
                                if (skip(job, envelope)) return null;
                                if (!jobs.ownsExecution(job.id(), token))
                                    throw new IllegalStateException(
                                            "Execution lease lost; leave delivery unacknowledged");
                                var completed = handler.complete(job, request, response);
                                if (completed.result() != null || completed.retryAt() != null)
                                    finishRemote(job, envelope, completed);
                                return completed;
                            });
        }
    }

    private void finishRemote(
            WorkJobRepository.Job job,
            OutboxRepository.Envelope envelope,
            RemoteWorkHandler.Step step) {
        if (step.retryAt() != null) {
            schedule(job, envelope, step.retryAt(), "REMOTE_DEFERRED", step.attempted());
            return;
        }
        jobs.done(job, step.result());
        outbox.disposition(envelope.id(), "SUCCEEDED", null, null);
    }

    private void execute(OutboxRepository.Envelope initial, WorkJobRepository.Job initialJob) {
        jobs.lockProject(initialJob);
        WorkHandler handler = null;
        try {
            handler = handlers.require(initialJob.handler());
        } catch (WorkFailure e) {
            /* Still check immutable DONE/old dispositions before classifying. */
        }
        if (handler != null) handler.lockBusiness(initialJob);
        var job = jobs.find(initialJob.id(), true);
        var envelope = outbox.find(initial.id(), true);
        if (skip(job, envelope)) return;
        if (envelope.sequence() != job.sequence()
                || !(job.status().equals("WAITING") || job.status().equals("RETRY_WAIT")))
            throw new WorkFailure(false, "INVALID_JOB_STATE");
        Instant due =
                job.nextAttempt().isAfter(envelope.available())
                        ? job.nextAttempt()
                        : envelope.available();
        if (due.isAfter(jobs.now())) {
            schedule(job, envelope, due, "EARLY_DELIVERY", false);
            return;
        }
        if (handler == null) throw new WorkFailure(false, "UNKNOWN_HANDLER");
        WorkHandler.Result result;
        try {
            result = handler.execute(job);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new WorkFailure(false, "LOCAL_INVARIANT_VIOLATION");
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && sql.getErrorCode() == 3819)
                throw new WorkFailure(false, "LOCAL_INVARIANT_VIOLATION");
            throw e;
        } catch (WorkFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkFailure(false, "HANDLER_EXCEPTION");
        }
        if (result == null) throw new WorkFailure(false, "INVALID_RESULT");
        jobs.done(job, result);
        outbox.disposition(envelope.id(), "SUCCEEDED", null, null);
    }

    private boolean skip(WorkJobRepository.Job job, OutboxRepository.Envelope envelope) {
        if (!envelope.disposition().equals("UNHANDLED")) return true;
        if (job.status().equals("DONE")
                || job.status().equals("FAILED")
                || envelope.sequence() < job.sequence()) {
            outbox.disposition(envelope.id(), "SUPERSEDED", null, null);
            return true;
        }
        return false;
    }

    private void disposeFailure(
            OutboxRepository.Envelope initial,
            WorkJobRepository.Job initialJob,
            WorkFailure failure,
            byte[] body) {
        jobs.lockProject(initialJob);
        var job = jobs.find(initialJob.id(), true);
        var envelope = outbox.find(initial.id(), true);
        if (skip(job, envelope)) return;
        String code = failure.getMessage();
        if (failure.transientFailure() && !retry.exhausted(job.attempt() + 1)) {
            schedule(
                    job,
                    envelope,
                    jobs.now().plusMillis(retry.delayMillis(job.attempt() + 1)),
                    code,
                    true);
        } else {
            String category = failure.transientFailure() ? "RETRY_EXHAUSTED" : code;
            failures.record(
                    "message/" + envelope.id(),
                    job.id(),
                    envelope.id(),
                    category,
                    envelope.destination(),
                    body);
            try {
                handlers.require(job.handler()).terminalFailure(job, category);
            } catch (WorkFailure ignored) {
            }
            jobs.failed(job, category);
            outbox.disposition(envelope.id(), "FAILED", null, category);
        }
    }

    private void schedule(
            WorkJobRepository.Job job,
            OutboxRepository.Envelope envelope,
            Instant due,
            String error,
            boolean attempted) {
        UUID next = UUID.randomUUID();
        outbox.insert(
                next,
                job.id(),
                job.sequence() + 1,
                envelope.destination(),
                envelope.handler(),
                envelope.wire(),
                due);
        jobs.retry(job, job.sequence() + 1, due, error, attempted);
        outbox.disposition(envelope.id(), "RETRY_SCHEDULED", next, error);
    }
}
