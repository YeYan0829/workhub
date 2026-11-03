package dev.workhub.work;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class TaskReassignments {
    private final ProjectAccess access;
    private final TaskRepository tasks;
    private final TaskService service;
    private final TaskReassignmentRepository requests;
    private final ProjectRepository projects;
    private final ActivityService activity;
    private final OperationReceipts receipts;
    private final Json json;

    public TaskReassignments(
            ProjectAccess access,
            TaskRepository tasks,
            TaskService service,
            TaskReassignmentRepository requests,
            ProjectRepository projects,
            ActivityService activity,
            OperationReceipts receipts,
            Json json) {
        this.access = access;
        this.tasks = tasks;
        this.service = service;
        this.requests = requests;
        this.projects = projects;
        this.activity = activity;
        this.receipts = receipts;
        this.json = json;
    }

    public record Create(String reason, Long expectedTaskRevision) {}

    public record Resolve(
            String decision, UUID assigneeId, String note, Long expectedTaskRevision) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TaskReassignmentRepository.Request create(
            ActorContext actor, UUID project, UUID task, Create input, String key) {
        access.lock(actor, project);
        Task current = tasks.find(project, task).orElseThrow(ApiException::missing);
        if (!access.canExecuteTask(actor, current))
            throw new ApiException(403, "REASSIGNMENT_FORBIDDEN", "只有已接纳任务的当前负责人可以申请更换负责人");
        String reason = Inputs.text(input.reason(), "申请原因", 2000);
        long expected = Inputs.revision(input.expectedTaskRevision());
        String hash = Inputs.hash(json.write(List.of("task-reassignment", project, task, input)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null)
            return requests.find(project, task, previous).orElseThrow(ApiException::missing);
        if (current.revision() != expected)
            throw ApiException.conflict("TASK_REVISION_CONFLICT", current.revision());
        if (requests.pending(project, task))
            throw new ApiException(409, "REASSIGNMENT_PENDING", "已有更换负责人申请等待处理");
        UUID id = UUID.randomUUID();
        requests.insert(id, project, task, actor.userId(), reason);
        var result = requests.find(project, task, id).orElseThrow();
        long fact =
                activity.record(project, task, actor, id, "REASSIGNMENT_REQUESTED", null, result);
        activity.notifyMaintainers(project, fact, actor, "REASSIGNMENT_REQUESTED");
        projects.increment(project);
        receipts.save(actor.userId(), key, project, hash, id);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TaskReassignmentRepository.Request resolve(
            ActorContext actor,
            UUID project,
            UUID task,
            UUID id,
            long expectedRequestRevision,
            Resolve input) {
        access.lock(actor, project);
        access.maintainer(actor, project);
        var request = requests.find(project, task, id).orElseThrow(ApiException::missing);
        if (request.revision() != expectedRequestRevision)
            throw ApiException.conflict("REQUEST_REVISION_CONFLICT", request.revision());
        if (!request.status().equals("PENDING"))
            throw new ApiException(409, "REQUEST_RESOLVED", "这份申请已经处理或失效");
        Task current = tasks.find(project, task).orElseThrow(ApiException::missing);
        long expected = Inputs.revision(input.expectedTaskRevision());
        if (current.revision() != expected)
            throw ApiException.conflict("TASK_REVISION_CONFLICT", current.revision());
        if (current.triageRequired()
                || current.status() == TaskStatus.TRIAGE
                || current.status() == TaskStatus.CANCELED
                || !Objects.equals(current.assigneeId(), request.requestedBy()))
            throw new ApiException(409, "REQUEST_STALE", "任务阶段或负责人已经变化，这份申请不再适用");
        boolean approved = "APPROVE".equals(input.decision());
        if (!approved && !"REJECT".equals(input.decision())) throw ApiException.invalid("请选择批准或拒绝");
        String note = input.note() == null ? "" : input.note().trim();
        if (note.length() > 2000) throw ApiException.invalid("处理说明最长2000字符");
        if (approved) {
            if (input.assigneeId() == null || input.assigneeId().equals(current.assigneeId()))
                throw ApiException.invalid("批准时请选择另一位有效项目成员");
            access.assignee(project, input.assigneeId());
        }
        if (requests.decide(request, actor.userId(), approved ? "APPROVED" : "REJECTED", note) != 1)
            throw ApiException.conflict("REQUEST_REVISION_CONFLICT", request.revision());
        if (approved) {
            var patch = new TaskRequests.Patch();
            patch.setAssigneeId(input.assigneeId());
            service.update(actor, project, task, expected, patch);
        } else projects.increment(project);
        var result = requests.find(project, task, id).orElseThrow();
        long fact =
                activity.record(
                        project,
                        task,
                        actor,
                        UUID.randomUUID(),
                        approved ? "REASSIGNMENT_APPROVED" : "REASSIGNMENT_REJECTED",
                        request,
                        result);
        activity.notify(
                project,
                fact,
                actor,
                Map.of(request.requestedBy(), Set.of("REASSIGNMENT_DECISION")));
        return result;
    }
}
