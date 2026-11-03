package dev.workhub.work;

import java.util.UUID;

public record Dependency(UUID blockerId, UUID blockedId) {}
