package dev.workhub.integration.github;

import dev.workhub.identity.CurrentActor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class GitHubController {
    private final GitHubService service;
    private final CurrentActor actor;
    private final GitHubEvidence evidence;

    public GitHubController(GitHubService service, CurrentActor actor, GitHubEvidence evidence) {
        this.service = service;
        this.actor = actor;
        this.evidence = evidence;
    }

    @PostMapping("/integrations/github/webhook")
    public ResponseEntity<?> webhook(
            @RequestHeader("X-GitHub-Delivery") String delivery,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) {
        var result = service.webhook(delivery, event, signature, body);
        return ResponseEntity.status(
                        result.status().equals("HASH_CONFLICT")
                                ? 409
                                : result.status().equals("ACCEPTED") ? 202 : 200)
                .body(result);
    }

    @PostMapping("/projects/{project}/github/connections")
    public ResponseEntity<?> connect(
            java.security.Principal principal,
            @PathVariable UUID project,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody GitHubService.Connect request) {
        return ResponseEntity.status(201)
                .body(Map.of("id", service.connect(actor.from(principal), project, request, key)));
    }

    @PostMapping("/projects/{project}/tasks/{task}/github/intents")
    public ResponseEntity<?> intent(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID task,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody GitHubService.IntentRequest request) {
        return ResponseEntity.accepted()
                .body(service.intent(actor.from(principal), project, task, request, key));
    }

    @GetMapping("/projects/{project}/tasks/{task}/github")
    public GitHubService.SyncView view(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID task) {
        return service.view(actor.from(principal), project, task);
    }

    @PostMapping("/projects/{project}/github/connections/{connection}/reconcile")
    public ResponseEntity<?> reconcile(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID connection) {
        return ResponseEntity.accepted()
                .body(
                        Map.of(
                                "operationId",
                                service.reconcile(actor.from(principal), project, connection)));
    }

    @PostMapping("/projects/{project}/tasks/{task}/github/evidence")
    public ResponseEntity<?> evidence(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID task,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody GitHubEvidence.Link request) {
        return ResponseEntity.accepted()
                .body(
                        Map.of(
                                "resourceId",
                                evidence.link(actor.from(principal), project, task, request, key)));
    }

    @GetMapping("/projects/{project}/github/evidence/{resource}")
    public GitHubEvidence.View evidenceView(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID resource) {
        return evidence.get(actor.from(principal), project, resource);
    }

    @PostMapping("/projects/{project}/github/evidence/{resource}/refresh")
    public ResponseEntity<?> refreshEvidence(
            java.security.Principal principal,
            @PathVariable UUID project,
            @PathVariable UUID resource) {
        return ResponseEntity.accepted()
                .body(Map.of("jobId", evidence.refresh(actor.from(principal), project, resource)));
    }
}
