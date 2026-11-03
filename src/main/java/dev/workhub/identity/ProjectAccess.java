package dev.workhub.identity;

import dev.workhub.common.ApiException;
import dev.workhub.work.Task;
import dev.workhub.work.TaskStatus;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class ProjectAccess {
    private final IdentityRepository identities;
    private final ProjectRepository projects;

    public ProjectAccess(IdentityRepository identities, ProjectRepository projects) {
        this.identities = identities;
        this.projects = projects;
    }

    public Project read(ActorContext actor, UUID projectId) {
        return authorize(actor, projectId, false);
    }

    // The calling application service owns the READ COMMITTED transaction.
    public Project lock(ActorContext actor, UUID projectId) {
        Project p = authorize(actor, projectId, true);
        if (p.archived()) throw new ApiException(409, "PROJECT_ARCHIVED", "项目已归档，请维护者恢复后操作");
        return p;
    }

    public Project lockManagement(ActorContext actor, UUID projectId) {
        return authorize(actor, projectId, true);
    }

    private Project authorize(ActorContext actor, UUID projectId, boolean lock) {
        if (actor.analysisScope() != null) {
            var scope = actor.analysisScope();
            if (lock || !scope.projectId().equals(projectId)) throw ApiException.forbidden();
            Project project = projects.find(projectId).orElseThrow(ApiException::forbidden);
            if (project.archived()) throw new ApiException(409, "PROJECT_ARCHIVED", "项目已归档，分诊分析停止");
            identities.requireTriageTask(projectId, scope.taskId());
            return project;
        }
        Project project = projects.find(projectId).orElseThrow(ApiException::forbidden);
        if (lock) identities.lockWorkspace(project.workspaceId());
        if (!identities.activeUser(actor.userId(), lock)) throw ApiException.forbidden();
        if (!identities.activeWorkspaceMember(project.workspaceId(), actor.userId(), lock))
            throw ApiException.forbidden();
        if (lock) project = projects.lock(projectId).orElseThrow(ApiException::forbidden);
        if (identities.role(projectId, actor.userId()).isEmpty()) throw ApiException.forbidden();
        return project;
    }

    public boolean isMaintainer(ActorContext actor, UUID project) {
        if (actor.analysisScope() != null) return false;
        return identities.role(project, actor.userId()).orElse(null) == ProjectRole.MAINTAINER;
    }

    public void maintainer(ActorContext actor, UUID project) {
        if (!isMaintainer(actor, project)) throw ApiException.forbidden();
    }

    // Call after read/lock has checked current user, workspace and project membership.
    public boolean canPrepareTask(ActorContext actor, Task task) {
        return actor.analysisScope() == null && task.triageRequired()
                && task.status() == TaskStatus.TRIAGE
                && Objects.equals(actor.userId(), task.createdBy());
    }

    public boolean canExecuteTask(ActorContext actor, Task task) {
        return actor.analysisScope() == null && !task.triageRequired()
                && task.status() != TaskStatus.TRIAGE
                && task.status() != TaskStatus.CANCELED
                && Objects.equals(actor.userId(), task.assigneeId());
    }

    public boolean canEditTask(ActorContext actor, Task task) {
        return isMaintainer(actor, task.projectId())
                || canPrepareTask(actor, task)
                || canExecuteTask(actor, task);
    }

    public void editTask(ActorContext actor, Task task) {
        if (!canEditTask(actor, task))
            throw new ApiException(
                    403, "TASK_EDIT_FORBIDDEN", "待分诊任务由创建者完善；已接纳任务由当前负责人执行，项目维护者可管理全部任务");
    }

    public void assignee(UUID project, UUID user) {
        if (user != null && !identities.activeProjectMember(project, user))
            throw ApiException.invalid("Assignee must be an active project member");
    }

    public void expected(Project project, long expected) {
        if (project.businessRevision() != expected)
            throw ApiException.conflict("PROJECT_REVISION_CONFLICT", project.businessRevision());
    }
}
