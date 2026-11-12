package dev.workhub.work;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.SourceObligations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Service
public class TaskService {
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final ActivityService activity;
    private final SubscriptionRepository subscriptions;
    private final SourceObligations sources;
    private final OperationReceipts receipts;
    private final Json json;
    private final TaskReassignmentRepository reassignments;
    private final org.springframework.context.ApplicationEventPublisher events;

    public record Created(ActorContext actor, Task task) {}

    public TaskService(
            TaskRepository tasks,
            ProjectRepository projects,
            ProjectAccess access,
            ActivityService activity,
            SubscriptionRepository subscriptions,
            SourceObligations sources,
            OperationReceipts receipts,
            Json json,
            TaskReassignmentRepository reassignments,
            org.springframework.context.ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.projects = projects;
        this.access = access;
        this.activity = activity;
        this.subscriptions = subscriptions;
        this.sources = sources;
        this.receipts = receipts;
        this.json = json;
        this.reassignments = reassignments;
        this.events = events;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task create(ActorContext actor, UUID project, TaskRequests.Create request, String key) {
        access.lock(actor, project);
        String hash = Inputs.hash(json.write(List.of("task", project, request)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return require(project, previous);
        String title = Inputs.text(request.title(), "title", 500);
        String description = request.description() == null ? "" : request.description();
        validateDescription(description);
        boolean maintainer = access.isMaintainer(actor, project);
        if (!maintainer && (request.assigneeId() != null || request.priority() != null))
            throw new ApiException(403, "TASK_MANAGEMENT_FORBIDDEN", "正式负责人和优先级由项目维护者分诊时确定");
        if (!maintainer && request.status() != null && request.status() != TaskStatus.TRIAGE)
            throw new ApiException(403, "TASK_APPROVAL_REQUIRED", "普通成员创建的任务必须先进入待分诊");
        TaskStatus status =
                request.status() == null
                        ? (maintainer ? TaskStatus.TODO : TaskStatus.TRIAGE)
                        : request.status();
        TaskType type = request.type() == null ? TaskType.TASK : request.type();
        int priority = request.priority() == null ? 2 : request.priority();
        validatePriority(priority);
        access.assignee(project, request.assigneeId());
        UUID id = UUID.randomUUID();
        tasks.insert(
                project,
                id,
                title,
                description,
                status,
                type,
                priority,
                request.assigneeId(),
                request.dueDate(),
                actor.userId());
        tasks.triageRequired(id, status == TaskStatus.TRIAGE);
        subscriptions.participate(project, id, actor.userId(), "CREATOR");
        subscriptions.participate(project, id, request.assigneeId(), "ASSIGNEE");
        sources.changed(
                project,
                id,
                SourceObligations.Owner.TASK,
                title + "\n" + description,
                "/projects/" + project + "/tasks/" + id);
        projects.increment(project);
        Task result = require(project, id);
        long fact =
                activity.record(
                        project,
                        id,
                        actor,
                        id,
                        status == TaskStatus.TRIAGE ? "TASK_SUBMITTED" : "TASK_CREATED",
                        null,
                        result);
        activity.taskChanged(null, result, actor, fact, false);
        receipts.save(actor.userId(), key, project, hash, id);
        // Synchronous listeners register durable work in this transaction, never call a model.
        events.publishEvent(new Created(actor, result));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task update(
            ActorContext actor, UUID project, UUID id, long expected, TaskRequests.Patch request) {
        return applyUpdate(actor, project, id, expected, request, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task acceptTriage(
            ActorContext actor,
            UUID project,
            UUID id,
            long expected,
            TaskType type,
            Integer priority) {
        var patch = new TaskRequests.Patch();
        if (type != null) patch.setType(type);
        if (priority != null) patch.setPriority(priority);
        return acceptTriage(actor, project, id, expected, patch);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task acceptTriage(
            ActorContext actor, UUID project, UUID id, long expected, TaskRequests.Patch patch) {
        access.lock(actor, project);
        access.maintainer(actor, project);
        Task old = require(project, id);
        if (old.status() != TaskStatus.TRIAGE || !old.triageRequired())
            throw ApiException.invalid("任务不在待分诊阶段");
        UUID assignee = patch.assigneePresent() ? patch.assigneeId() : old.assigneeId();
        if (assignee == null) throw ApiException.invalid("接纳任务前请指定负责人");
        access.assignee(project, assignee);
        if (patch.status() != null && patch.status() != TaskStatus.TODO)
            throw ApiException.invalid("接纳后的状态应为待办");
        patch.setStatus(TaskStatus.TODO);
        return applyUpdate(actor, project, id, expected, patch, true);
    }

    private Task applyUpdate(
            ActorContext actor,
            UUID project,
            UUID id,
            long expected,
            TaskRequests.Patch request,
            boolean triageAccept) {
        access.lock(actor, project);
        Task old = require(project, id);
        access.editTask(actor, old);
        boolean maintainer = access.isMaintainer(actor, project);
        boolean preparing = access.canPrepareTask(actor, old);
        if (!maintainer) {
            if (request.assigneePresent() && !Objects.equals(request.assigneeId(), old.assigneeId())
                    || request.priority() != null && request.priority() != old.priority()
                    || request.type() != null && request.type() != old.type()
                    || !preparing
                            && (request.title() != null && !request.title().equals(old.title())
                                    || request.dueDatePresent()
                                            && !Objects.equals(request.dueDate(), old.dueDate())))
                throw new ApiException(
                        403, "TASK_MANAGEMENT_FORBIDDEN", "正式标题、类型、截止时间、负责人和优先级由项目维护者管理");
            if (request.status() != null && request.status() != old.status()) {
                boolean allowed =
                        preparing
                                ? request.status() == TaskStatus.CANCELED
                                : Set.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.DONE)
                                        .contains(request.status());
                if (!allowed)
                    throw new ApiException(
                            403, "TASK_STATUS_FORBIDDEN", "任务接纳、拒绝、退回分诊和正式取消由项目维护者处理");
            }
        }
        if (old.revision() != expected)
            throw ApiException.conflict("TASK_REVISION_CONFLICT", old.revision());
        String title =
                request.title() == null ? old.title() : Inputs.text(request.title(), "title", 500);
        String description =
                request.description() == null ? old.description() : request.description();
        validateDescription(description);
        TaskStatus status = request.status() == null ? old.status() : request.status();
        TaskType type = request.type() == null ? old.type() : request.type();
        int priority = request.priority() == null ? old.priority() : request.priority();
        validatePriority(priority);
        UUID assignee = request.assigneePresent() ? request.assigneeId() : old.assigneeId();
        LocalDate due = request.dueDatePresent() ? request.dueDate() : old.dueDate();
        if (request.assigneePresent()) access.assignee(project, assignee);
        if (old.origin() == TaskOrigin.GITHUB
                && (request.title() != null
                        || request.description() != null
                        || status != old.status() && (closed(status) || closed(old.status()))))
            throw new ApiException(
                    422,
                    "EXTERNAL_OPERATION_REQUIRED",
                    "GitHub shared fields require an external intent");
        if ((old.status() == TaskStatus.TRIAGE || old.triageRequired())
                && status != TaskStatus.TRIAGE
                && status != TaskStatus.CANCELED
                && !triageAccept)
            throw new ApiException(
                    422, "TRIAGE_ACCEPT_REQUIRED", "Use the Maintainer triage acceptance action");
        if (title.equals(old.title())
                && description.equals(old.description())
                && status == old.status()
                && type == old.type()
                && priority == old.priority()
                && Objects.equals(assignee, old.assigneeId())
                && Objects.equals(due, old.dueDate())) return old;
        if (tasks.update(old, title, description, status, type, priority, assignee, due, expected)
                != 1)
            throw ApiException.conflict("TASK_REVISION_CONFLICT", require(project, id).revision());
        if (triageAccept) tasks.triageRequired(id, false);
        else if (status == TaskStatus.TRIAGE) tasks.triageRequired(id, true);
        if (!Objects.equals(assignee, old.assigneeId())
                || status == TaskStatus.TRIAGE
                || status == TaskStatus.CANCELED) reassignments.supersede(project, id);
        subscriptions.participate(project, id, assignee, "ASSIGNEE");
        if (!title.equals(old.title()) || !description.equals(old.description()))
            sources.changed(
                    project,
                    id,
                    SourceObligations.Owner.TASK,
                    title + "\n" + description,
                    "/projects/" + project + "/tasks/" + id);
        projects.increment(project);
        Task result = require(project, id);
        UUID cause = UUID.randomUUID();
        String kind =
                triageAccept
                        ? "TASK_ACCEPTED"
                        : old.triageRequired()
                                        && status == TaskStatus.CANCELED
                                        && old.status() != status
                                ? (maintainer ? "TASK_REJECTED" : "TASK_WITHDRAWN")
                                : status == TaskStatus.TRIAGE && old.status() != status
                                        ? "TASK_RETURNED_TO_TRIAGE"
                                        : old.status() == TaskStatus.DONE
                                                        && status != TaskStatus.DONE
                                                ? "TASK_REOPENED"
                                                : "TASK_UPDATED";
        long fact = activity.record(project, id, actor, cause, kind, old, result);
        activity.taskChanged(
                old,
                result,
                actor,
                fact,
                triageAccept
                        || old.triageRequired()
                                && status == TaskStatus.CANCELED
                                && old.status() != status);
        if (old != null && old.status() != TaskStatus.DONE && status == TaskStatus.DONE)
            activity.upstreamCompleted(project, id, actor, cause);
        return result;
    }

    @Transactional(readOnly = true)
    public Task get(ActorContext actor, UUID project, UUID id) {
        access.read(actor, project);
        return require(project, id);
    }

    @Transactional(readOnly = true)
    public List<Task> list(
            ActorContext actor,
            UUID project,
            TaskStatus status,
            UUID assignee,
            Integer priority,
            TaskRepository.Cursor cursor,
            int limit) {
        access.read(actor, project);
        if (priority != null) validatePriority(priority);
        return tasks.list(project, status, assignee, priority, cursor, Inputs.limit(limit));
    }

    @Transactional(readOnly = true)
    public List<Task> blockers(ActorContext actor, UUID project, UUID id) {
        access.read(actor, project);
        require(project, id);
        return tasks.blockers(project, id);
    }

    /**
     * Caller holds Project -> resource -> Job locks. Never use the human PATCH path for
     * observations.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Task applyGitHub(
            UUID project,
            UUID id,
            UUID importActor,
            UUID cause,
            String title,
            String body,
            TaskStatus status) {
        var actor = new ActorContext(importActor, "GITHUB_SYNC");
        Task old = id == null ? null : require(project, id);
        if (old != null && old.origin() != TaskOrigin.GITHUB)
            throw new IllegalArgumentException("Mirror must be GitHub-origin");
        if (old != null
                && old.title().equals(title)
                && old.description().equals(body)
                && old.status() == status) return old;
        if (old == null) {
            id = UUID.randomUUID();
            tasks.insertGitHub(project, id, title, body, status, importActor);
        } else if (tasks.update(
                        old,
                        title,
                        body,
                        status,
                        old.type(),
                        old.priority(),
                        old.assigneeId(),
                        old.dueDate(),
                        old.revision())
                != 1) throw new IllegalStateException("Project lock did not protect Task");
        tasks.triageRequired(id, status == TaskStatus.TRIAGE);
        if (status == TaskStatus.TRIAGE || status == TaskStatus.CANCELED)
            reassignments.supersede(project, id);
        if (old == null || !old.title().equals(title) || !old.description().equals(body))
            sources.changed(
                    project,
                    id,
                    SourceObligations.Owner.TASK,
                    title + "\n" + body,
                    "/projects/" + project + "/tasks/" + id);
        projects.increment(project);
        Task result = require(project, id);
        activity.record(project, id, actor, cause, "GITHUB_OBSERVED", old, result);
        if (old != null && old.status() != TaskStatus.DONE && status == TaskStatus.DONE)
            activity.upstreamCompleted(project, id, actor, cause);
        return result;
    }

    private Task require(UUID project, UUID id) {
        return tasks.find(project, id).orElseThrow(ApiException::missing);
    }

    private static boolean closed(TaskStatus status) {
        return status == TaskStatus.DONE || status == TaskStatus.CANCELED;
    }

    private static void validatePriority(int value) {
        if (value < 0 || value > 3) throw ApiException.invalid("priority must be 0 through 3");
    }

    private static void validateDescription(String value) {
        if (value.length() > 1_000_000) throw ApiException.invalid("description too large");
    }
}
