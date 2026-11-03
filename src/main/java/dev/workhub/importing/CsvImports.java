package dev.workhub.importing;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.messaging.*;
import dev.workhub.work.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Service
public class CsvImports implements WorkHandler {
    private final JdbcTemplate db;
    private final ProjectAccess access;
    private final TaskService tasks;
    private final Json json;
    private final BusinessJobs jobs;
    private final OperationReceipts receipts;

    public CsvImports(
            JdbcTemplate db,
            ProjectAccess access,
            TaskService tasks,
            Json json,
            BusinessJobs jobs,
            OperationReceipts receipts) {
        this.db = db;
        this.access = access;
        this.tasks = tasks;
        this.json = json;
        this.jobs = jobs;
        this.receipts = receipts;
    }

    public String kind() {
        return "CSV_ROW";
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID upload(ActorContext a, UUID p, String filename, byte[] source, String key) {
        access.lock(a, p);
        access.maintainer(a, p);
        var rows = CsvFormat.parse(source);
        if (filename == null || filename.length() > 200) filename = "import.csv";
        String hash = Inputs.hash(Base64.getEncoder().encodeToString(source));
        UUID prior = receipts.find(a.userId(), key, Inputs.hash(p + hash));
        if (prior != null) return prior;
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " csv_import(id,project_id,actor_id,filename,input_hash,source,expires_at)"
                    + " VALUES (?,?,?,?,?,?,TIMESTAMPADD(DAY,30,UTC_TIMESTAMP(6)))",
                bytes(id),
                bytes(p),
                bytes(a.userId()),
                filename,
                hash,
                source);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String error = null;
            try {
                request(p, row);
            } catch (RuntimeException e) {
                error = message(e);
            }
            db.update(
                    "INSERT INTO csv_import_row(import_id,line_number,input,state,error_code)"
                            + " VALUES (?,?,?, ?,?)",
                    bytes(id),
                    i + 1,
                    json.write(row),
                    error == null ? "PENDING" : "FAILED",
                    error);
        }
        receipts.save(a.userId(), key, p, Inputs.hash(p + hash), id);
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirm(ActorContext a, UUID p, UUID id, boolean retry) {
        access.lock(a, p);
        var batch = batch(p, id);
        access.maintainer(a, p);
        live(batch);
        String state = (String) batch.get("state");
        if (state.equals("RUNNING") || state.equals("SUCCEEDED")) return;
        if (!retry && !state.equals("PREVIEW"))
            throw new ApiException(409, "USE_RETRY", "使用恢复入口继续原导入");
        int generation = ((Number) batch.get("generation")).intValue() + 1;
        db.update(
                "UPDATE csv_import SET state='RUNNING',stop_reason=NULL,generation=?,actor_id=?"
                        + " WHERE id=?",
                generation,
                bytes(a.userId()),
                bytes(id));
        if (retry)
            db.update(
                    "UPDATE csv_import_row SET state='PENDING',error_code=NULL WHERE import_id=?"
                            + " AND state<>'SUCCEEDED'",
                    bytes(id));
        var rows =
                db.queryForList(
                        "SELECT line_number FROM csv_import_row WHERE import_id=? AND"
                                + " state='PENDING'",
                        bytes(id));
        for (var r : rows) {
            int n = ((Number) r.get("line_number")).intValue();
            jobs.enqueue(
                    kind(),
                    p,
                    id,
                    "csv/" + id + "/" + generation + "/" + n,
                    Map.of("row", n, "generation", generation));
        }
        finish(id);
    }

    private Map<String, Object> batch(UUID p, UUID id) {
        var list =
                db.queryForList(
                        "SELECT * FROM csv_import WHERE id=? AND project_id=?",
                        bytes(id),
                        bytes(p));
        if (list.isEmpty()) throw ApiException.missing();
        return list.get(0);
    }

    private void live(Map<String, Object> b) {
        if (b.get("source") == null
                || db.queryForObject(
                        "SELECT expires_at<=UTC_TIMESTAMP(6) FROM csv_import WHERE id=?",
                        Boolean.class,
                        b.get("id")))
            throw new ApiException(410, "IMPORT_EXPIRED", "导入文件已过保留期，请重新上传");
    }

    public List<Map<String, Object>> list(ActorContext a, UUID p) {
        access.read(a, p);
        return db.queryForList(
                "SELECT BIN_TO_UUID(id) id,filename,state,stop_reason stopReason,created_at"
                    + " createdAt FROM csv_import WHERE project_id=? ORDER BY created_at DESC LIMIT"
                    + " 100",
                bytes(p));
    }

    public Map<String, Object> view(ActorContext a, UUID p, UUID id) {
        access.read(a, p);
        var b = batch(p, id);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String k :
                List.of(
                        "filename",
                        "state",
                        "stop_reason",
                        "input_hash",
                        "created_at",
                        "expires_at")) result.put(k, b.get(k));
        result.put("id", id);
        result.put("canManage", !access.read(a, p).archived() && access.isMaintainer(a, p));
        result.put(
                "expired",
                b.get("source") == null
                        || Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT expires_at<=UTC_TIMESTAMP(6) FROM csv_import WHERE"
                                                + " id=?",
                                        Boolean.class,
                                        bytes(id))));
        result.put("counts", counts(id));
        result.put(
                "rows",
                db.queryForList(
                        "SELECT line_number rowNumber,JSON_UNQUOTE(JSON_EXTRACT(input,'$[0]'))"
                            + " title,state,error_code error,BIN_TO_UUID(task_id) taskId FROM"
                            + " csv_import_row WHERE import_id=? ORDER BY line_number LIMIT 1000",
                        bytes(id)));
        return result;
    }

    public byte[] source(ActorContext a, UUID p, UUID id) {
        access.read(a, p);
        var b = batch(p, id);
        live(b);
        return (byte[]) b.get("source");
    }

    public String report(ActorContext a, UUID p, UUID id) {
        access.read(a, p);
        live(batch(p, id));
        StringBuilder s = new StringBuilder("row,state,error,task_id\n");
        for (var row :
                db.queryForList(
                        "SELECT line_number,state,error_code,BIN_TO_UUID(task_id) task_id FROM"
                                + " csv_import_row WHERE import_id=? ORDER BY line_number",
                        bytes(id))) {
            for (String k : List.of("line_number", "state", "error_code", "task_id"))
                s.append('"')
                        .append(Objects.toString(row.get(k), "").replace("\"", "\"\""))
                        .append("\",");
            s.setLength(s.length() - 1);
            s.append('\n');
        }
        return s.toString();
    }

    private Map<String, Long> counts(UUID id) {
        Map<String, Long> counts =
                new LinkedHashMap<>(Map.of("SUCCEEDED", 0L, "FAILED", 0L, "PENDING", 0L));
        db.query(
                "SELECT state,COUNT(*) n FROM csv_import_row WHERE import_id=? GROUP BY state",
                r -> {
                    counts.put(r.getString(1), r.getLong(2));
                },
                bytes(id));
        return counts;
    }

    private void finish(UUID id) {
        var c = counts(id);
        if (c.get("PENDING") == 0)
            db.update(
                    "UPDATE csv_import SET state=? WHERE id=? AND state='RUNNING'",
                    c.get("FAILED") == 0
                            ? "SUCCEEDED"
                            : c.get("SUCCEEDED") == 0 ? "FAILED" : "PARTIAL_FAILED",
                    bytes(id));
    }

    public Result execute(WorkJobRepository.Job job) {
        var batch = batch(job.project(), job.owner());
        var payload = json.read(job.payload(), Map.class);
        int row = ((Number) payload.get("row")).intValue();
        String reference = "csv/" + job.owner() + "/" + row;
        if (!batch.get("state").equals("RUNNING")
                || ((Number) batch.get("generation")).intValue()
                        != ((Number) payload.get("generation")).intValue())
            return new Result(reference, Inputs.hash(reference));
        var data =
                db.queryForMap(
                        "SELECT * FROM csv_import_row WHERE import_id=? AND line_number=? FOR"
                                + " UPDATE",
                        bytes(job.owner()),
                        row);
        if (data.get("state").equals("SUCCEEDED"))
            return new Result(reference, Inputs.hash(reference));
        ActorContext actor = new ActorContext(uuid((byte[]) batch.get("actor_id")));
        try {
            access.lock(actor, job.project());
            access.maintainer(actor, job.project());
            live(batch);
        } catch (ApiException e) {
            db.update(
                    "UPDATE csv_import SET state='STOPPED',stop_reason=? WHERE id=?",
                    e.code,
                    bytes(job.owner()));
            return new Result(reference, Inputs.hash(reference));
        }
        TaskRequests.Create request;
        try {
            request = request(job.project(), json.read((String) data.get("input"), List.class));
        } catch (RuntimeException e) {
            db.update(
                    "UPDATE csv_import_row SET state='FAILED',error_code=? WHERE import_id=? AND"
                            + " line_number=?",
                    message(e),
                    bytes(job.owner()),
                    row);
            finish(job.owner());
            return new Result(reference, Inputs.hash(reference));
        }
        Task t = tasks.create(actor, job.project(), request, reference);
        db.update(
                "UPDATE csv_import_row SET state='SUCCEEDED',task_id=?,error_code=NULL WHERE"
                        + " import_id=? AND line_number=?",
                bytes(t.id()),
                bytes(job.owner()),
                row);
        finish(job.owner());
        return new Result(reference, Inputs.hash(reference));
    }

    public void terminalFailure(WorkJobRepository.Job job, String code) {
        var payload = json.read(job.payload(), Map.class);
        db.update(
                "UPDATE csv_import_row SET state='FAILED',error_code=? WHERE import_id=? AND"
                        + " line_number=? AND state<>'SUCCEEDED'",
                code,
                bytes(job.owner()),
                ((Number) payload.get("row")).intValue());
        finish(job.owner());
    }

    private TaskRequests.Create request(UUID p, List<String> r) {
        if (r.size() != 6) throw ApiException.invalid("需要6列");
        Inputs.text(r.get(0), "title", 500);
        if (r.get(1).length() > 10000) throw ApiException.invalid("描述过长");
        TaskType type = r.get(2).isBlank() ? TaskType.TASK : TaskType.valueOf(r.get(2));
        int priority = r.get(3).isBlank() ? 2 : Integer.parseInt(r.get(3));
        if (priority < 0 || priority > 3) throw ApiException.invalid("优先级应为0–3");
        LocalDate due = r.get(4).isBlank() ? null : LocalDate.parse(r.get(4));
        UUID assignee = null;
        if (!r.get(5).isBlank()) {
            var ids =
                    db.query(
                            "SELECT u.id FROM app_user u JOIN project_member pm ON pm.user_id=u.id"
                                + " JOIN project p ON p.id=pm.project_id JOIN workspace_member wm"
                                + " ON wm.workspace_id=p.workspace_id AND wm.user_id=u.id WHERE"
                                + " p.id=? AND u.login=? AND pm.active AND wm.active AND u.active",
                            (rs, n) -> read(rs, "id"),
                            bytes(p),
                            r.get(5));
            if (ids.size() != 1) throw ApiException.invalid("负责人必须精确匹配有效项目成员的登录名");
            assignee = ids.get(0);
        }
        return new TaskRequests.Create(
                r.get(0), r.get(1), TaskStatus.TODO, type, priority, assignee, due);
    }

    private String message(RuntimeException e) {
        return e instanceof ApiException ? e.getMessage() : "字段格式错误：检查类型、优先级和日期";
    }
}
