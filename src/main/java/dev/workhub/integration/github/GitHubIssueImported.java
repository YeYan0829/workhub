package dev.workhub.integration.github;

import dev.workhub.work.Task;
import java.time.Instant;
import java.util.UUID;

/** Emitted synchronously with the first confirmed mirror, never from webhook claims. */
public record GitHubIssueImported(Task task, UUID resource, UUID causeJob,
                                  Instant sourceCreatedAt, String discoveryMode) {}
