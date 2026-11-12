package dev.workhub.activity;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class ActivityService {
    private final ActivityRepository repository;
    private final IdentityRepository identities;
    private final TaskRepository tasks;
    private final ProjectAccess access;

    public ActivityService(
            ActivityRepository repository,
            IdentityRepository identities,
            TaskRepository tasks,
            ProjectAccess access) {
        this.repository = repository;
        this.identities = identities;
        this.tasks = tasks;
        this.access = access;
    }

    public long record(
            UUID project,
            UUID task,
            ActorContext actor,
            UUID cause,
            String kind,
            Object before,
            Object after) {
        return repository.insert(project, task, actor, cause, kind, before, after);
    }

    public void notify(
            UUID project, long activity, ActorContext actor, Map<UUID, Set<String>> recipients) {
        notify(project, activity, actor, recipients, false);
    }

    private void notify(
            UUID project,
            long activity,
            ActorContext actor,
            Map<UUID, Set<String>> recipients,
            boolean includeActor) {
        for (var entry : recipients.entrySet()) {
            UUID user = entry.getKey();
            if ((includeActor || !user.equals(actor.userId()) || actor.type().equals("GITHUB_SYNC"))
                    && identities.activeProjectMember(project, user))
                repository.notify(project, activity, user, entry.getValue());
        }
    }

    public void notifyMaintainers(UUID project, long fact, ActorContext actor, String reason) {
        Map<UUID, Set<String>> recipients = new LinkedHashMap<>();
        for (UUID user : identities.maintainers(project)) recipients.put(user, Set.of(reason));
        notify(project, fact, actor, recipients);
    }

    public void taskChanged(
            Task before, Task after, ActorContext actor, long fact, boolean triageDecision) {
        Map<UUID, Set<String>> recipients = new LinkedHashMap<>();
        if (after.assigneeId() != null
                && (before == null || !after.assigneeId().equals(before.assigneeId())))
            recipients
                    .computeIfAbsent(after.assigneeId(), id -> new LinkedHashSet<>())
                    .add("ASSIGNED");
        if (triageDecision)
            recipients
                    .computeIfAbsent(after.createdBy(), id -> new LinkedHashSet<>())
                    .add("TRIAGE_DECISION");
        if (after.status() == TaskStatus.TRIAGE
                && (before == null || before.status() != TaskStatus.TRIAGE))
            for (UUID user : identities.maintainers(after.projectId()))
                recipients.computeIfAbsent(user, id -> new LinkedHashSet<>()).add("TRIAGE");
        notify(after.projectId(), fact, actor, recipients);
    }

    public void assigned(Task before, Task after, ActorContext actor, long activity) {
        if (after.assigneeId() != null
                && (before == null || !after.assigneeId().equals(before.assigneeId())))
            notify(
                    after.projectId(),
                    activity,
                    actor,
                    Map.of(after.assigneeId(), Set.of("ASSIGNED")));
    }

    public void upstreamCompleted(UUID project, UUID upstream, ActorContext actor, UUID cause) {
        repository.unblockedBy(project, upstream, actor, cause);
    }

    public void unblocked(List<Task> before, ActorContext actor, UUID cause) {
        for (Task old : before) {
            Task current = tasks.find(old.projectId(), old.id()).orElseThrow();
            if (old.isBlocked() && !current.isBlocked()) {
                long fact =
                        record(
                                old.projectId(),
                                old.id(),
                                actor,
                                cause,
                                "TASK_UNBLOCKED",
                                Map.of("isBlocked", true),
                                Map.of("isBlocked", false));
                if (current.assigneeId() != null)
                    notify(
                            old.projectId(),
                            fact,
                            actor,
                            Map.of(current.assigneeId(), Set.of("UNBLOCKED")),
                            true); // A new downstream task is actionable even for the upstream
                // actor.
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ActivityRepository.Activity> list(
            ActorContext actor, UUID project, long before, int limit) {
        access.read(actor, project);
        return repository.list(project, before, Inputs.limit(limit));
    }

    @Transactional(readOnly = true)
    public List<ActivityRepository.Notification> notifications(
            ActorContext actor, UUID project, long before, int limit) {
        access.read(actor, project);
        return repository.notifications(project, actor.userId(), before, Inputs.limit(limit));
    }

    @Transactional(readOnly = true)
    public ActivityRepository.InboxPage inbox(
            ActorContext actor,
            UUID workspace,
            UUID project,
            boolean unread,
            long before,
            int limit) {
        if (before < 1) throw ApiException.invalid("Invalid notification cursor");
        return repository.inbox(
                actor.userId(), workspace, project, unread, before, Inputs.limit(limit));
    }

    public long unreadCount(ActorContext actor) {
        return repository.unreadCount(actor.userId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markRead(ActorContext actor, long id) {
        UUID project = repository.notificationProject(actor.userId(), id);
        access.lockManagement(actor, project);
        if (repository.markRead(project, actor.userId(), id) == 0) throw ApiException.missing();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markRead(ActorContext actor, UUID project, long id) {
        access.lockManagement(actor, project);
        if (repository.markRead(project, actor.userId(), id) == 0) throw ApiException.missing();
    }
}
