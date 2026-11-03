package dev.workhub.work;

import dev.workhub.common.*;
import dev.workhub.identity.CurrentActor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}")
public class ContentController {
    private final ContentService service;
    private final CurrentActor actors;

    public ContentController(ContentService service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    public record DocumentRequest(ContentRepository.DocumentKind kind, String title, String body) {}

    public record TextRequest(String body) {}

    public record SubscriptionRequest(boolean active) {}

    public record UrlRequest(String url) {}

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentRepository.Document createDocument(
            Principal user,
            @PathVariable UUID p,
            @RequestBody DocumentRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.createDocument(
                actors.from(user), p, request.kind(), request.title(), request.body(), key);
    }

    @PutMapping("/documents/{d}")
    public ContentRepository.Document editDocument(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID d,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestBody DocumentRequest request) {
        if (request.kind() != null) throw ApiException.invalid("Document kind is immutable");
        return service.editDocument(
                actors.from(user), p, d, Inputs.ifMatch(revision), request.title(), request.body());
    }

    @GetMapping("/documents/{d}")
    public ContentRepository.Document document(
            Principal user, @PathVariable UUID p, @PathVariable UUID d) {
        return service.getDocument(actors.from(user), p, d);
    }

    @DeleteMapping("/documents/{d}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(Principal user, @PathVariable UUID p, @PathVariable UUID d,
            @RequestHeader(value="If-Match", required=false) String revision) {
        service.deleteDocument(actors.from(user),p,d,Inputs.ifMatch(revision));
    }

    @GetMapping("/documents")
    public List<ContentRepository.Document> documents(
            Principal user,
            @PathVariable UUID p,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.documents(actors.from(user), p, cursor(cursor), limit);
    }

    @PostMapping("/tasks/{t}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentRepository.Comment createComment(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestBody TextRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.createComment(actors.from(user), p, t, request.body(), key);
    }

    @PutMapping("/tasks/{t}/comments/{c}")
    public ContentRepository.Comment editComment(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID c,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestBody TextRequest request) {
        return service.editComment(
                actors.from(user), p, t, c, Inputs.ifMatch(revision), request.body());
    }

    @DeleteMapping("/tasks/{t}/comments/{c}")
    public void deleteComment(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID c,
            @RequestHeader(value = "If-Match", required = false) String revision) {
        service.deleteComment(actors.from(user), p, t, c, Inputs.ifMatch(revision));
    }

    @GetMapping("/tasks/{t}/comments")
    public List<ContentRepository.Comment> comments(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.comments(actors.from(user), p, t, cursor(cursor), limit);
    }

    @PutMapping("/tasks/{t}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscription(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestBody SubscriptionRequest request) {
        service.subscribe(actors.from(user), p, t, request.active());
    }

    @GetMapping("/tasks/{t}/links")
    public ContentService.Links links(Principal user, @PathVariable UUID p, @PathVariable UUID t) {
        return service.links(actors.from(user), p, t);
    }

    @PutMapping("/tasks/{t}/documents/{d}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void linkDocument(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID d,
            @RequestHeader(value = "If-Match", required = false) String revision) {
        service.linkDocument(actors.from(user), p, t, Inputs.ifMatch(revision), d, true);
    }

    @DeleteMapping("/tasks/{t}/documents/{d}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkDocument(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID d,
            @RequestHeader(value = "If-Match", required = false) String revision) {
        service.linkDocument(actors.from(user), p, t, Inputs.ifMatch(revision), d, false);
    }

    @PutMapping("/tasks/{t}/url-links")
    public Map<String, UUID> url(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestBody UrlRequest request) {
        return Map.of(
                "resourceId",
                service.linkUrl(actors.from(user), p, t, Inputs.ifMatch(revision), request.url()));
    }

    @DeleteMapping("/tasks/{t}/url-links/{r}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkUrl(
            Principal user,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @PathVariable UUID r,
            @RequestHeader(value = "If-Match", required = false) String revision) {
        service.unlinkResource(actors.from(user), p, t, Inputs.ifMatch(revision), r);
    }

    private static TaskRepository.Cursor cursor(String value) {
        if (value == null) return null;
        try {
            var parts = value.split("~");
            if (parts.length != 2) throw new IllegalArgumentException();
            return new TaskRepository.Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.invalid("Invalid cursor");
        }
    }
}
