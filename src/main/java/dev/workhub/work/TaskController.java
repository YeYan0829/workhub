package dev.workhub.work;

import dev.workhub.common.*;
import dev.workhub.identity.CurrentActor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}/tasks")
public class TaskController {
    private final TaskService service;
    private final CurrentActor actors;

    public TaskController(TaskService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(
            Principal user,
            @PathVariable UUID p,
            @RequestBody TaskRequests.Create request,
            @RequestHeader("Idempotency-Key") String key) {
        return TaskResponse.from(service.create(actors.from(user), p, request, key));
    }

    @GetMapping("/{t}")
    public ResponseEntity<TaskResponse> get(
            Principal user, @PathVariable UUID p, @PathVariable UUID t) {
        var task = service.get(actors.from(user), p, t);
        return ResponseEntity.ok()
                .eTag(Long.toString(task.revision()))
                .body(TaskResponse.from(task));
    }

    @PatchMapping("/{t}")
    public ResponseEntity<TaskResponse> update(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestBody TaskRequests.Patch request) {
        var task = service.update(actors.from(user), p, t, Inputs.ifMatch(revision), request);
        return ResponseEntity.ok()
                .eTag(Long.toString(task.revision()))
                .body(TaskResponse.from(task));
    }

    @PostMapping("/{t}/triage-accept")
    public TaskResponse acceptTriage(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestBody TaskRequests.Patch request) {
        return TaskResponse.from(
                service.acceptTriage(actors.from(user), p, t, Inputs.ifMatch(revision), request));
    }

    public record TaskPage(List<TaskResponse> items, String nextCursor) {}

    @GetMapping
    public TaskPage list(
            Principal user,
            @PathVariable UUID p,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        TaskRepository.Cursor parsed = null;
        if (cursor != null) {
            try {
                String[] parts = cursor.split("~");
                if (parts.length != 2) throw new IllegalArgumentException();
                parsed =
                        new TaskRepository.Cursor(
                                Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw ApiException.invalid("Invalid task cursor");
            }
        }
        var tasks = service.list(actors.from(user), p, status, assignee, priority, parsed, limit);
        String next = null;
        if (tasks.size() == limit) {
            var last = tasks.get(tasks.size() - 1);
            next = last.updatedAt() + "~" + last.id();
        }
        return new TaskPage(tasks.stream().map(TaskResponse::from).toList(), next);
    }

    @GetMapping("/{t}/blockers")
    public List<TaskResponse> blockers(Principal user, @PathVariable UUID p, @PathVariable UUID t) {
        return service.blockers(actors.from(user), p, t).stream().map(TaskResponse::from).toList();
    }
}
