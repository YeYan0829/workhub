package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.*;

/** Append-only, isolated S0–S7 fixture. Run explicitly; preserves all previous fixture runs. */
@EnabledIfEnvironmentVariable(named = "WORKHUB_SEED_OVERVIEW_BENCHMARK", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "workhub.messaging.enabled=false",
            "workhub.retrieval.enabled=false",
            "workhub.embedding.enabled=false",
            "workhub.agent.enabled=false",
            "workhub.github.enabled=false",
            "workhub.model-admission.enabled=false",
            "workhub.bootstrap.login=",
            "debug=false",
            "logging.level.root=WARN",
            "workhub.mail.key-file=target/overview-benchmark/mail.key"
        })
class ProjectOverviewBenchmarkIT {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "spring.datasource.url",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "OVERVIEW_BENCH_JDBC_URL",
                                        "jdbc:mysql://127.0.0.1:28306/workhub_overview_benchmark_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&rewriteBatchedStatements=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        p.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("OVERVIEW_DB_USER", "team_model_fixture"));
        p.add(
                "spring.datasource.password",
                () ->
                        System.getenv()
                                .getOrDefault("OVERVIEW_DB_PASSWORD", "team-model-local-only"));
    }

    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    @Test
    void seedEightDatasets() throws Exception {
        if (!"workhub_overview_benchmark_test"
                .equals(db.queryForObject("SELECT DATABASE()", String.class)))
            throw new IllegalStateException("Dedicated benchmark schema required");
        UUID user = UUID.randomUUID(), member = UUID.randomUUID(), workspace = UUID.randomUUID();
        String password = "Overview-benchmark-2026!", hash = passwords.encode(password);
        String login = "bench-" + user, memberLogin = "bench-" + member;
        db.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                        + " (?,?,'林悦',?),(?,?,'陈希',?)",
                bytes(user),
                login,
                hash,
                bytes(member),
                memberLogin,
                hash);
        db.update("INSERT INTO workspace(id,name) VALUES (?,'星河产品团队 · 独立性能数据')", bytes(workspace));
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES"
                        + " (?,?,'ADMIN'),(?,?,'MEMBER')",
                bytes(workspace),
                bytes(user),
                bytes(workspace),
                bytes(member));
        UUID developer = UUID.randomUUID();
        String developerLogin = "bench-" + developer;
        db.update(
                "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'周航',?)",
                bytes(developer),
                developerLogin,
                hash);
        db.update(
                "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'MEMBER')",
                bytes(workspace),
                bytes(developer));
        var datasets = new ArrayList<Map<String, Object>>();
        int[] sizes = {9, 100, 1000, 10000, 10001, 1000, 200, 9},
                edgeCounts = {6, 200, 3000, 30000, 10000, 999, 10000, 6};
        String[] names = {
            "确定报表字段与接口",
            "完成报表页面与导出入口",
            "修复筛选后导出遗漏数据",
            "回归筛选与导出结果",
            "补齐报表使用说明",
            "完成报表发布验收",
            "发布报表功能公告",
            "整理功能截图素材",
            "评估导出列顺序自定义"
        };
        String[] descriptions = {
            "确认客户、日期、金额等报表字段与接口约定。",
            "完成报表页面、筛选表单和导出入口。",
            "日期筛选后导出结果与页面存在遗漏，需要修复并核对跨页数据。",
            "修复完成后比较筛选页面、导出行数和字段，记录回归结论。",
            "补齐筛选操作、导出限制和常见问题，可以和代码修复并行。",
            "发布前确认导出数据一致、回归通过、使用说明补齐；周航负责修复，陈希负责回归，林悦确认验收。",
            "验收完成后发布功能公告，说明入口、筛选与导出方式。",
            "整理报表页面与筛选截图，可以独立推进。",
            "评估自定义导出列顺序，确认范围与优先级后再安排。"
        };
        String[] discussion = {
            "回归将覆盖日期筛选、跨页导出和导出行数。",
            "已复现导出请求漏传日期参数，正在修复。",
            "验收需同时确认数据一致、回归通过和使用说明补齐。",
            "导出限制和常见问题可以与代码修复并行补齐。",
            "修复确认后由陈希开始回归，两项前置工作完成后再确认发布。"
        };
        for (int dataset = 0; dataset < 8; dataset++) {
            final int ds = dataset, n = sizes[ds];
            UUID project = UUID.randomUUID();
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < n; i++) ids.add(UUID.randomUUID());
            tx.executeWithoutResult(
                    transaction -> {
                        db.update(
                                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,?)",
                                bytes(project),
                                bytes(workspace),
                                ds == 0 ? "客户报表发布" : "S" + ds + " 性能数据");
                        db.update(
                                "INSERT INTO project_member(project_id,user_id,role) VALUES"
                                        + " (?,?,'MAINTAINER'),(?,?,'MEMBER')",
                                bytes(project),
                                bytes(user),
                                bytes(project),
                                bytes(member));
                        if (ds == 0 || ds == 7)
                            db.update(
                                    "INSERT INTO project_member(project_id,user_id,role) VALUES"
                                            + " (?,?,'MEMBER')",
                                    bytes(project),
                                    bytes(developer));
                        var rows = new ArrayList<Object[]>();
                        for (int i = 0; i < n; i++) {
                            boolean story = ds == 0 || ds == 7;
                            String status =
                                    story
                                            ? new String[] {
                                                        "DONE",
                                                        "DONE",
                                                        "TODO",
                                                        "TODO",
                                                        "IN_PROGRESS",
                                                        "TODO",
                                                        "TODO",
                                                        "TODO",
                                                        "TRIAGE"
                                                    }
                                                    [i]
                                            : new String[] {
                                                        "TODO",
                                                        "IN_PROGRESS",
                                                        "TRIAGE",
                                                        "DONE",
                                                        "CANCELED"
                                                    }
                                                    [i % 5];
                            rows.add(
                                    new Object[] {
                                        bytes(ids.get(i)),
                                        bytes(project),
                                        story
                                                ? names[i]
                                                : "S" + ds + " 任务 " + String.format("%05d", i),
                                        ds == 7 && i == 5
                                                ? "正文🙂".repeat(333333) + "尾"
                                                : story ? descriptions[i] : "确认工作范围与验收条件。",
                                        status,
                                        status.equals("TRIAGE"),
                                        bytes(
                                                (ds == 0 || ds == 7) && i < 3
                                                        ? developer
                                                        : i == 3 ? member : user),
                                        bytes(user),
                                        i
                                    });
                        }
                        db.batchUpdate(
                                "INSERT INTO"
                                    + " task(id,project_id,title,description,status,triage_required,assignee_id,created_by,updated_at,type,origin,priority)"
                                    + " VALUES (?,?,?,?,?,?,?,?,TIMESTAMPADD(SECOND,?,'2026-11-12"
                                    + " 06:00:00'),'TASK','MANUAL',1)",
                                rows);
                        var edges = new ArrayList<Object[]>();
                        if (ds == 0) {
                            UUID checklist = UUID.randomUUID(), steps = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " project_document(id,project_id,kind,title,body,created_by)"
                                        + " VALUES"
                                        + " (?,?,'DOCUMENT','报表发布验收清单','导出数据与筛选结果一致；回归通过；使用说明补齐。林悦确认验收后安排公告。',?),(?,?,'DOCUMENT','筛选导出回归步骤','准备至少两页数据，按客户和日期筛选，比较页面与导出行数，补充跨页验证记录。',?)",
                                    bytes(checklist),
                                    bytes(project),
                                    bytes(user),
                                    bytes(steps),
                                    bytes(project),
                                    bytes(user));
                            db.update(
                                    "INSERT INTO task_document(project_id,task_id,document_id)"
                                        + " VALUES (?,?,?),(?,?,?),(?,?,?)",
                                    bytes(project),
                                    bytes(ids.get(5)),
                                    bytes(checklist),
                                    bytes(project),
                                    bytes(ids.get(2)),
                                    bytes(steps),
                                    bytes(project),
                                    bytes(ids.get(3)),
                                    bytes(steps));
                        }
                        if (ds == 0 || ds == 7) {
                            for (int[] pair :
                                    new int[][] {{0, 1}, {1, 3}, {2, 3}, {3, 5}, {4, 5}, {5, 6}})
                                edges.add(
                                        new Object[] {
                                            bytes(project),
                                            bytes(ids.get(pair[0])),
                                            bytes(ids.get(pair[1])),
                                            bytes(user)
                                        });
                        } else if (ds == 4) {
                            for (int i = 1; i < n; i++)
                                edges.add(
                                        new Object[] {
                                            bytes(project),
                                            bytes(ids.get(0)),
                                            bytes(ids.get(i)),
                                            bytes(user)
                                        });
                        } else
                            outer:
                            for (int gap = 1; gap < n; gap++)
                                for (int i = 0; i + gap < n; i++) {
                                    edges.add(
                                            new Object[] {
                                                bytes(project),
                                                bytes(ids.get(i)),
                                                bytes(ids.get(i + gap)),
                                                bytes(user)
                                            });
                                    if (edges.size() == edgeCounts[ds]) break outer;
                                }
                        db.batchUpdate(
                                "INSERT INTO"
                                    + " task_dependency(project_id,blocker_id,blocked_id,created_by)"
                                    + " VALUES (?,?,?,?)",
                                edges);
                        if (ds == 0 || ds == 7)
                            for (int i = 0; i < (ds == 7 ? 52 : 5); i++)
                                db.update(
                                        "INSERT INTO"
                                            + " comment(id,project_id,task_id,author_id,body,created_at)"
                                            + " VALUES"
                                            + " (?,?,?,?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))",
                                        bytes(UUID.randomUUID()),
                                        bytes(project),
                                        bytes(ids.get(5)),
                                        bytes(
                                                ds == 0 && i == 0
                                                        ? member
                                                        : ds == 0 && i == 1 ? developer : user),
                                        ds == 7 ? "讨论🙂".repeat(4000) : discussion[i],
                                        i);
                    });
            if (ds == 0 || ds == 7) {
                db.update(
                        "UPDATE task SET discussion_revision=? WHERE id=?",
                        ds == 7 ? 52 : 5,
                        bytes(ids.get(5)));
                db.update("UPDATE task SET type='FEATURE' WHERE id=?", bytes(ids.get(1)));
                db.update("UPDATE task SET type='BUG' WHERE id=?", bytes(ids.get(2)));
                db.update("UPDATE task SET type='QUESTION' WHERE id=?", bytes(ids.get(8)));
            }
            datasets.add(
                    Map.of(
                            "id",
                            "S" + ds,
                            "project",
                            project,
                            "tasks",
                            ids,
                            "nodes",
                            n,
                            "edges",
                            edgeCounts[ds]));
        }
        var loadUsers = new ArrayList<Map<String, Object>>();
        var loadProjects = new ArrayList<Map<String, Object>>();
        if ("true".equals(System.getenv("WORKHUB_OVERVIEW_LOAD_FIXTURE"))) {
            for (var d : datasets)
                if (Set.of("S1", "S2", "S3").contains(d.get("id"))) loadProjects.add(d);
            for (int i = 0; i < 70; i++) {
                UUID id = UUID.randomUUID();
                String name = "load-" + id;
                db.update(
                        "INSERT INTO app_user(id,login,display_name,password_hash) VALUES"
                                + " (?,?,'Load reader',?)",
                        bytes(id),
                        name,
                        hash);
                db.update(
                        "INSERT INTO workspace_member(workspace_id,user_id,role) VALUES"
                                + " (?,?,'MEMBER')",
                        bytes(workspace),
                        bytes(id));
                loadUsers.add(Map.of("user", name, "id", id));
            }
            for (int i = 0; i < 47; i++) {
                UUID project = UUID.randomUUID();
                var ids = new ArrayList<UUID>();
                for (int j = 0; j < 100; j++) ids.add(UUID.randomUUID());
                tx.executeWithoutResult(
                        transaction -> {
                            db.update(
                                    "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Load"
                                            + " project')",
                                    bytes(project),
                                    bytes(workspace));
                            db.update(
                                    "INSERT INTO project_member(project_id,user_id,role) VALUES"
                                            + " (?,?,'MAINTAINER'),(?,?,'MEMBER')",
                                    bytes(project),
                                    bytes(user),
                                    bytes(project),
                                    bytes(member));
                            var rows = new ArrayList<Object[]>();
                            for (int j = 0; j < 100; j++)
                                rows.add(
                                        new Object[] {
                                            bytes(ids.get(j)),
                                            bytes(project),
                                            "Load task " + j,
                                            bytes(user)
                                        });
                            db.batchUpdate(
                                    "INSERT INTO"
                                        + " task(id,project_id,title,description,status,type,origin,created_by)"
                                        + " VALUES (?,?,?,'','TODO','TASK','MANUAL',?)",
                                    rows);
                            rows.clear();
                            outer:
                            for (int gap = 1; gap < 100; gap++)
                                for (int j = 0; j + gap < 100; j++) {
                                    rows.add(
                                            new Object[] {
                                                bytes(project),
                                                bytes(ids.get(j)),
                                                bytes(ids.get(j + gap)),
                                                bytes(user)
                                            });
                                    if (rows.size() == 200) break outer;
                                }
                            db.batchUpdate(
                                    "INSERT INTO"
                                        + " task_dependency(project_id,blocker_id,blocked_id,created_by)"
                                        + " VALUES (?,?,?,?)",
                                    rows);
                        });
                loadProjects.add(
                        Map.of(
                                "id", "L" + i, "project", project, "tasks", ids, "nodes", 100,
                                "edges", 200));
            }
            tx.executeWithoutResult(
                    transaction -> {
                        for (var d : loadProjects) {
                            var rows = new ArrayList<Object[]>();
                            for (var reader : loadUsers)
                                rows.add(
                                        new Object[] {
                                            bytes((UUID) d.get("project")),
                                            bytes((UUID) reader.get("id"))
                                        });
                            db.batchUpdate(
                                    "INSERT INTO project_member(project_id,user_id,role) VALUES"
                                            + " (?,?,'MEMBER')",
                                    rows);
                        }
                    });
        }
        var output = Path.of("target/overview-benchmark/seed.json");
        Files.createDirectories(output.getParent());
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "base",
                System.getenv().getOrDefault("OVERVIEW_BENCH_BASE", "http://127.0.0.1:18080"));
        result.put("user", login);
        result.put("member", memberLogin);
        result.put("developer", developerLogin);
        result.put("password", password);
        result.put("userId", user);
        result.put("memberId", member);
        result.put("developerId", developer);
        result.put("workspace", workspace);
        result.put("datasets", datasets);
        result.put("loadUsers", loadUsers);
        result.put("loadProjects", loadProjects);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("OVERVIEW_BENCHMARK_SEED " + output.toAbsolutePath());
    }
}
