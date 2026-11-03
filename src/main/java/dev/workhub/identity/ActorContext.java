package dev.workhub.identity;

import java.util.UUID;

public record ActorContext(UUID userId, String type, AnalysisScope analysisScope) {
    /** Internal read capability; never accepted from an HTTP request or persisted as a user role. */
    public record AnalysisScope(UUID projectId, UUID taskId) {
        public AnalysisScope {
            java.util.Objects.requireNonNull(projectId);
            java.util.Objects.requireNonNull(taskId);
        }
    }

    public ActorContext(UUID userId, String type) {
        this(userId, type, null);
    }

    public ActorContext(UUID userId) {
        this(userId, "HUMAN");
    }

    public static ActorContext projectAnalysis(UUID requestedBy, UUID project, UUID task) {
        return new ActorContext(requestedBy, "PROJECT_ANALYSIS", new AnalysisScope(project, task));
    }

    // User/account/workspace services must not treat a project read grant as its original human.
    // The initiating user remains available in agent_run.actor_id for audit, not impersonation.
    public UUID userId() {
        if (analysisScope != null) throw dev.workhub.common.ApiException.forbidden();
        return userId;
    }

    public ActorContext {
        if (userId == null) throw new IllegalArgumentException("Actor is required");
        if (!java.util.Set.of("HUMAN", "GITHUB_SYNC", "APPROVED_AGENT", "PROJECT_ANALYSIS").contains(type))
            throw new IllegalArgumentException("Invalid actor type");
        if ("PROJECT_ANALYSIS".equals(type) != (analysisScope != null))
            throw new IllegalArgumentException("Project analysis requires an explicit read scope");
    }
}
