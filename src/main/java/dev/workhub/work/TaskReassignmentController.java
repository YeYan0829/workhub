package dev.workhub.work;

import dev.workhub.common.Inputs;
import dev.workhub.identity.CurrentActor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/projects/{p}/tasks/{t}/reassignment-requests")
public class TaskReassignmentController {
    private final TaskReassignments service;
    private final CurrentActor actors;

    public TaskReassignmentController(TaskReassignments service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskReassignmentRepository.Request create(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestBody TaskReassignments.Create request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.create(actors.from(user), p, t, request, key);
    }

    @PostMapping("/{id}/resolve")
    public TaskReassignmentRepository.Request resolve(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID id,
            @RequestBody TaskReassignments.Resolve request,
            @RequestHeader(value = "If-Match", required = false) String revision) {
        return service.resolve(actors.from(user), p, t, id, Inputs.ifMatch(revision), request);
    }
}
