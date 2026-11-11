package dev.workhub.work;

import dev.workhub.activity.ActivityService;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.knowledge.SourceObligations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ContentService {
    private final ContentRepository content;
    private final ProjectAccess access;
    private final IdentityRepository identities;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final SubscriptionRepository subscriptions;
    private final ActivityService activity;
    private final SourceObligations sources;
    private final OperationReceipts receipts;
    private final Json json;

    public ContentService(
            ContentRepository content,
            ProjectAccess access,
            IdentityRepository identities,
            TaskRepository tasks,
            ProjectRepository projects,
            SubscriptionRepository subscriptions,
            ActivityService activity,
            SourceObligations sources,
            OperationReceipts receipts,
            Json json) {
        this.content = content;
        this.access = access;
        this.identities = identities;
        this.tasks = tasks;
        this.projects = projects;
        this.subscriptions = subscriptions;
        this.activity = activity;
        this.sources = sources;
        this.receipts = receipts;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentRepository.Document createDocument(
            ActorContext actor,
            UUID project,
            ContentRepository.DocumentKind kind,
            String title,
            String body,
            String key) {
        access.lock(actor, project);
        Inputs.text(title, "title", 500);
        Inputs.text(body, "body", 1_000_000);
        if (kind == null) kind = ContentRepository.DocumentKind.DOCUMENT;
        String hash = Inputs.hash(json.write(List.of("document", project, kind, title, body)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return document(project, previous);
        UUID id = UUID.randomUUID();
        content.insertDocument(project, id, kind, title, body, actor.userId());
        sources.changed(
                project,
                id,
                SourceObligations.Owner.DOCUMENT,
                title + "\n" + body,
                "/projects/" + project + "/documents/" + id);
        activity.record(project, null, actor, id, "DOCUMENT_CREATED", null, document(project, id));
        receipts.save(actor.userId(), key, project, hash, id);
        return document(project, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentRepository.Document editDocument(
            ActorContext actor, UUID project, UUID id, long expected, String title, String body) {
        access.lock(actor, project);
        var old = document(project, id);
        if (old.revision() != expected)
            throw ApiException.conflict("DOCUMENT_REVISION_CONFLICT", old.revision());
        Inputs.text(title, "title", 500);
        Inputs.text(body, "body", 1_000_000);
        if (title.equals(old.title()) && body.equals(old.body())) return old;
        if (content.updateDocument(project, id, title, body, expected) != 1)
            throw ApiException.conflict("DOCUMENT_REVISION_CONFLICT", old.revision());
        sources.changed(
                project,
                id,
                SourceObligations.Owner.DOCUMENT,
                title + "\n" + body,
                "/projects/" + project + "/documents/" + id);
        var result = document(project, id);
        activity.record(project, null, actor, UUID.randomUUID(), "DOCUMENT_UPDATED", old, result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDocument(ActorContext actor, UUID project, UUID id, long expected) {
        access.lock(actor, project);
        access.maintainer(actor, project);
        var old=document(project,id);
        if(old.revision()!=expected) throw ApiException.conflict("DOCUMENT_REVISION_CONFLICT",old.revision());
        content.deleteDocument(project,id);
        sources.changed(project,id,SourceObligations.Owner.DOCUMENT,"[deleted]","/projects/"+project);
        sources.deactivate(project,id,SourceObligations.Owner.DOCUMENT);
        activity.record(project,null,actor,UUID.randomUUID(),"DOCUMENT_DELETED",Map.of("id",id),Map.of("deleted",true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentRepository.Comment createComment(
            ActorContext actor, UUID project, UUID task, String body, String key) {
        access.lock(actor, project);
        requireTask(project, task);
        Inputs.text(body, "body", 1_000_000);
        String hash = Inputs.hash(json.write(List.of("comment", project, task, body)));
        UUID previous = receipts.find(actor.userId(), key, hash);
        if (previous != null) return comment(project, task, previous);
        UUID id = UUID.randomUUID();
        content.insertComment(project, task, id, actor.userId(), body);
        tasks.discussionChanged(project, task);
        subscriptions.participate(project, task, actor.userId(), "COMMENTER");
        Map<UUID, Set<String>> recipients = new HashMap<>();
        var mentions = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_-]+)").matcher(body);
        while (mentions.find())
            for (UUID user : identities.activeMentions(project, mentions.group(1))) {
                recipients.computeIfAbsent(user, k -> new TreeSet<>()).add("MENTION");
                subscriptions.participate(project, task, user, "MENTION");
            }
        for (UUID user : subscriptions.subscribers(project, task))
            recipients.computeIfAbsent(user, k -> new TreeSet<>()).add("SUBSCRIPTION");
        sources.changed(
                project,
                id,
                SourceObligations.Owner.COMMENT,
                body,
                "/projects/" + project + "/tasks/" + task + "/comments/" + id);
        long fact =
                activity.record(
                        project,
                        task,
                        actor,
                        id,
                        "COMMENT_CREATED",
                        null,
                        comment(project, task, id));
        activity.notify(project, fact, actor, recipients);
        receipts.save(actor.userId(), key, project, hash, id);
        return comment(project, task, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentRepository.Comment editComment(
            ActorContext actor, UUID project, UUID task, UUID id, long expected, String body) {
        access.lock(actor, project);
        var old = comment(project, task, id);
        if (!old.authorId().equals(actor.userId())) throw ApiException.forbidden();
        Inputs.text(body, "body", 1_000_000);
        if (old.revision() != expected)
            throw ApiException.conflict("COMMENT_REVISION_CONFLICT", old.revision());
        if (body.equals(old.body())) return old;
        if (content.updateComment(project, task, id, body, expected) != 1)
            throw ApiException.conflict("COMMENT_REVISION_CONFLICT", old.revision());
        tasks.discussionChanged(project, task);
        sources.changed(
                project,
                id,
                SourceObligations.Owner.COMMENT,
                body,
                "/projects/" + project + "/tasks/" + task + "/comments/" + id);
        var result = comment(project, task, id);
        activity.record(project, task, actor, UUID.randomUUID(), "COMMENT_UPDATED", old, result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteComment(ActorContext actor, UUID project, UUID task, UUID id, long expected) {
        access.lock(actor, project);
        var old = comment(project, task, id);
        if (!old.authorId().equals(actor.userId())) access.maintainer(actor, project);
        if (old.revision() != expected)
            throw ApiException.conflict("COMMENT_REVISION_CONFLICT", old.revision());
        content.deleteComment(project, id);
        tasks.discussionChanged(project, task);
        sources.changed(
                project,
                id,
                SourceObligations.Owner.COMMENT,
                "[deleted]",
                "/projects/" + project + "/tasks/" + task);
        sources.deactivate(project, id, SourceObligations.Owner.COMMENT);
        activity.record(
                project,
                task,
                actor,
                UUID.randomUUID(),
                "COMMENT_DELETED",
                Map.of("id", id),
                Map.of("deleted", true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void subscribe(ActorContext actor, UUID project, UUID task, boolean active) {
        access.lock(actor, project);
        requireTask(project, task);
        subscriptions.set(project, task, actor.userId(), active);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID linkUrl(ActorContext actor, UUID project, UUID task, long expected, String url) {
        access.lock(actor, project);
        Task owner = checkRevision(project, task, expected);
        Inputs.text(url, "url", 2048);
        try {
            var uri = URI.create(url);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Expected an HTTP(S) URL without credentials");
        }
        // Hash only supplies a bounded exact URL identity; nothing fetches this URL.
        String key = Inputs.hash(url);
        UUID id = content.resourceByKey(project, key).orElse(null);
        if (id == null) {
            id = UUID.randomUUID();
            content.insertResource(project, id, key, url);
        }
        changeLink(actor, project, owner, id, false, true);
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void linkDocument(
            ActorContext actor,
            UUID project,
            UUID task,
            long expected,
            UUID document,
            boolean add) {
        access.lock(actor, project);
        Task owner = checkRevision(project, task, expected);
        document(project, document);
        changeLink(actor, project, owner, document, true, add);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unlinkResource(
            ActorContext actor, UUID project, UUID task, long expected, UUID resource) {
        access.lock(actor, project);
        changeLink(actor, project, checkRevision(project, task, expected), resource, false, false);
    }

    private void changeLink(
            ActorContext actor,
            UUID project,
            Task owner,
            UUID target,
            boolean document,
            boolean add) {
        access.editTask(actor, owner);
        boolean exists = content.linked(project, owner.id(), target, document);
        if (exists == add) return;
        content.link(project, owner.id(), target, document, add);
        tasks.update(
                owner,
                owner.title(),
                owner.description(),
                owner.status(),
                owner.type(),
                owner.priority(),
                owner.assigneeId(),
                owner.dueDate(),
                owner.revision());
        projects.increment(project);
        activity.record(
                project,
                owner.id(),
                actor,
                UUID.randomUUID(),
                add ? "LINK_ADDED" : "LINK_REMOVED",
                null,
                Map.of("target", target, "kind", document ? "DOCUMENT" : "URL"));
    }

    @Transactional(readOnly = true)
    public ContentRepository.Document getDocument(ActorContext actor, UUID project, UUID id) {
        access.read(actor, project);
        return document(project, id);
    }

    @Transactional(readOnly = true)
    public List<ContentRepository.Document> documents(
            ActorContext actor, UUID project, TaskRepository.Cursor cursor, int limit) {
        return documents(actor, project, cursor, limit, "");
    }

    @Transactional(readOnly = true)
    public List<ContentRepository.Document> documents(
            ActorContext actor, UUID project, TaskRepository.Cursor cursor, int limit, String query) {
        access.read(actor, project);
        if (query == null || query.length() > 200) throw ApiException.invalid("文档搜索最多200个字符");
        return content.documents(project, cursor, Inputs.limit(limit), query.strip());
    }

    @Transactional(readOnly = true)
    public List<ContentRepository.Comment> comments(
            ActorContext actor, UUID project, UUID task, TaskRepository.Cursor cursor, int limit) {
        access.read(actor, project);
        requireTask(project, task);
        return content.comments(project, task, cursor, Inputs.limit(limit));
    }

    @Transactional(readOnly = true)
    public List<ContentRepository.Comment> commentPage(
            ActorContext actor, UUID project, UUID task, TaskRepository.Cursor cursor,
            int limit, String order, UUID focus) {
        access.read(actor, project);
        requireTask(project, task);
        limit = Inputs.limit(limit);
        if (!Set.of("ASC", "DESC").contains(order)) throw ApiException.invalid("Invalid comment order");
        boolean newestFirst = order.equals("DESC");
        if (focus == null) return content.comments(project, task, cursor, limit, newestFirst);
        if (!newestFirst || cursor != null) throw ApiException.invalid("Focused comments require DESC without a cursor");
        // The anchor is checked against both project and task; never seek into another task.
        var anchor = comment(project, task, focus);
        var result = new ArrayList<ContentRepository.Comment>();
        result.add(anchor);
        if (limit > 1) result.addAll(content.comments(project, task,
                new TaskRepository.Cursor(anchor.createdAt(), anchor.id()), limit - 1, true));
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public ContentRepository.Comment getComment(ActorContext actor, UUID project, UUID id) {
        access.read(actor, project);
        UUID task = content.commentTask(project,id).orElseThrow(ApiException::missing);
        requireTask(project,task);
        return comment(project,task,id);
    }

    @Transactional(readOnly = true)
    public List<ContentRepository.Document> findDocuments(ActorContext actor, UUID project,
            TaskRepository.Cursor cursor, int limit, String query) {
        access.read(actor, project);
        return content.documentCandidates(project,cursor,Inputs.limit(limit),documentQueryTerms(query));
    }

    /** Candidate discovery: literal terms separated by whitespace, any term may match. */
    public static java.util.List<String> documentQueryTerms(String query) {
        if (query == null || query.length() > 200) throw ApiException.invalid("query too long");
        var terms = java.util.Arrays.stream(query.strip().split("\\s+"))
                .filter(term -> !term.isBlank()).distinct().toList();
        if (terms.size() > 8) throw ApiException.invalid("Use at most 8 space-separated search terms");
        return terms;
    }

    public record Links(List<UUID> documents, List<ContentRepository.Resource> resources,
            List<ContentRepository.DocumentReference> documentDetails) {}

    @Transactional(readOnly = true)
    public Links links(ActorContext actor, UUID project, UUID task) {
        access.read(actor, project);
        requireTask(project, task);
        var documents = content.linkedDocumentDetails(project, task);
        return new Links(documents.stream().map(ContentRepository.DocumentReference::id).toList(),
                content.linkedResources(project, task), documents);
    }

    private ContentRepository.Document document(UUID project, UUID id) {
        return content.document(project, id).orElseThrow(ApiException::missing);
    }

    private ContentRepository.Comment comment(UUID project, UUID task, UUID id) {
        return content.comment(project, task, id).orElseThrow(ApiException::missing);
    }

    private Task requireTask(UUID project, UUID id) {
        return tasks.find(project, id).orElseThrow(ApiException::missing);
    }

    private Task checkRevision(UUID project, UUID task, long expected) {
        Task owner = requireTask(project, task);
        if (owner.revision() != expected)
            throw ApiException.conflict("TASK_REVISION_CONFLICT", owner.revision());
        return owner;
    }
}
