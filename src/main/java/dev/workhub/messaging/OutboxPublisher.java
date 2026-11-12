package dev.workhub.messaging;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workhub.messaging.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final RabbitPublisher publisher;
    private final RetryPolicy retry;
    private final int batchSize;
    private final long batchNanos;

    public OutboxPublisher(OutboxRepository outbox, RabbitPublisher publisher, RetryPolicy retry) {
        this(outbox, publisher, retry, 20, 200);
    }

    @Autowired
    public OutboxPublisher(
            OutboxRepository outbox,
            RabbitPublisher publisher,
            RetryPolicy retry,
            @Value("${workhub.messaging.publish-batch-size:20}") int batchSize,
            @Value("${workhub.messaging.publish-batch-ms:200}") long batchMillis) {
        if (batchSize < 1 || batchSize > 100 || batchMillis < 1 || batchMillis > 1000) {
            throw new IllegalArgumentException("Invalid outbox publish batch limits");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.retry = retry;
        this.batchSize = batchSize;
        this.batchNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(batchMillis);
    }

    @Scheduled(fixedDelayString = "${workhub.messaging.poll-ms:500}")
    public void tick() {
        try {
            long began = System.nanoTime();
            for (int sent = 0; sent < batchSize; sent++) {
                if (!publishOne()) break;
                // Check between confirmations; an in-flight publish keeps its own timeout.
                if (System.nanoTime() - began >= batchNanos) break;
            }
        } catch (Exception e) {
            log.warn("Outbox polling paused: {}", e.getClass().getSimpleName());
        }
    }

    public boolean publishOne() {
        var envelope = outbox.claim(30);
        if (envelope == null) return false;
        try {
            publisher.publish(envelope);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String code =
                    e instanceof IllegalStateException
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            outbox.publishFailed(
                    envelope, code, Math.max(1000, retry.delayMillis(envelope.publishAttempt())));
            log.warn(
                    "Outbox {} publish attempt {} failed: {}",
                    envelope.id(),
                    envelope.publishAttempt(),
                    code);
            return true;
        }
        // Crash here leaves a CLAIMED row: expiration resends the same messageId.
        outbox.confirmed(envelope);
        return true;
    }
}
