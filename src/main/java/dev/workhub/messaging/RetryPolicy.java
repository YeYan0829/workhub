package dev.workhub.messaging;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {
    public static final int MAX_ATTEMPTS = 5;

    public long ceilingMillis(int attempt) {
        return Math.min(60_000L, 1000L << Math.min(6, Math.max(0, attempt - 1)));
    }

    public long delayMillis(int attempt) {
        return ThreadLocalRandom.current().nextLong(ceilingMillis(attempt) + 1);
    }

    public boolean exhausted(int attempt) {
        return attempt >= MAX_ATTEMPTS;
    }
}
