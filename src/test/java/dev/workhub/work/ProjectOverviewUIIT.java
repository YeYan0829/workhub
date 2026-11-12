package dev.workhub.work;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.identity.ActorContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "workhub.messaging.enabled=false", "workhub.retrieval.enabled=false",
    "workhub.embedding.enabled=false", "workhub.agent.enabled=false",
    "workhub.github.enabled=false", "workhub.model-admission.enabled=false",
    "workhub.bootstrap.login=", "server.servlet.session.cookie.secure=false",
    "debug=false", "logging.level.root=WARN", "workhub.mail.key-file=target/project-overview-ui/mail.key"
})
class ProjectOverviewUIIT {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv().getOrDefault("OVERVIEW_JDBC_URL",
                "jdbc:mysql://127.0.0.1:28306/workhub_overview_ui_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("OVERVIEW_DB_USER", "team_model_fixture"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("OVERVIEW_DB_PASSWORD", "team-model-local-only"));
    }

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired ProjectGraph graph;
    @LocalServerPort int port;
    UUID user, workspace, project;
    final String password = "Overview-local-2026!";

    @BeforeEach void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("workhub_overview_ui_test");
        user = user("林悦"); workspace = UUID.randomUUID();
        db.update("INSERT INTO workspace(id,name) VALUES (?,'星河产品团队')", bytes(workspace));
        db.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'ADMIN')", bytes(workspace), bytes(user));
        project = project("客户报表发布");
    }

    UUID user(String display) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,?,?)",
                bytes(id), "overview-" + id, display, passwords.encode(password));
        return id;
    }
    UUID project(String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO project(id,workspace_id,name) VALUES (?,?,?)", bytes(id), bytes(workspace), name);
        db.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MAINTAINER')", bytes(id), bytes(user));
        return id;
    }
    UUID task(String title, String status, String type, UUID assignee) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO task(id,project_id,title,description,status,type,origin,priority,assignee_id,created_by,triage_required) VALUES (?,?,?,?,?,?,'MANUAL',1,?,?,?)",
                bytes(id), bytes(project), title, "确认工作范围与验收条件，完成后更新任务并记录讨论。", status, type,
                bytes(assignee), bytes(user), status.equals("TRIAGE"));
        return id;
    }
    void edge(UUID from, UUID to) {
        db.update("INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES (?,?,?,?)",
                bytes(project), bytes(from), bytes(to), bytes(user));
    }
    ActorContext actor() { return new ActorContext(user, "HUMAN"); }

    @Test void boundedSnapshotPreservesOmittedRelationsAndRealBlockers() {
        for (int i = 0; i < 201; i++) task("Task " + i, "TODO", "TASK", user);
        var first = graph.snapshot(actor(), project);
        assertThat(first.nodes()).hasSize(200);
        assertThat(first.totalTasks()).isEqualTo(201);
        assertThat(first.partial()).isTrue();
        Set<UUID> shown = new HashSet<>(); first.nodes().forEach(n -> shown.add(n.id()));
        UUID omitted = db.query("SELECT id FROM task WHERE project_id=?", (r, n) -> read(r,"id"), bytes(project))
                .stream().filter(id -> !shown.contains(id)).findFirst().orElseThrow();
        UUID selected = first.nodes().get(0).id();
        edge(omitted, selected);
        var withEdge = graph.snapshot(actor(), project);
        var node = withEdge.nodes().stream().filter(n -> n.id().equals(selected)).findFirst().orElseThrow();
        assertThat(withEdge.edges()).isEmpty();
        assertThat(withEdge.totalEdges()).isEqualTo(1);
        assertThat(node.isBlocked()).isTrue();
        assertThat(node.upstreamCount()).isEqualTo(1);
        db.update("UPDATE project SET archived=true WHERE id=?", bytes(project));
        assertThat(graph.snapshot(actor(), project).totalTasks()).isEqualTo(201);
        project = project("已完成任务的依赖风险");
        UUID upstream = task("已取消的上游", "CANCELED", "TASK", user);
        UUID finished = task("已完成的下游", "DONE", "TASK", user);
        edge(upstream, finished);
        assertThat(graph.snapshot(actor(), project).nodes().stream()
                .filter(n -> n.id().equals(finished)).findFirst().orElseThrow().dependencyRisk()).isTrue();
    }

    @Test void denseDagHasAnExplicitEdgeLimit() {
        var tasks = new ArrayList<UUID>();
        for (int i = 0; i < 47; i++) tasks.add(task("Node " + i, "TODO", "TASK", user));
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < tasks.size(); i++) for (int j = i + 1; j < tasks.size(); j++)
            rows.add(new Object[] {bytes(project), bytes(tasks.get(i)), bytes(tasks.get(j)), bytes(user)});
        db.batchUpdate("INSERT INTO task_dependency(project_id,blocker_id,blocked_id,created_by) VALUES (?,?,?,?)", rows);
        var snapshot = graph.snapshot(actor(), project);
        assertThat(snapshot.nodes()).hasSize(47);
        assertThat(snapshot.edges()).hasSize(1000);
        assertThat(snapshot.totalEdges()).isEqualTo(1081);
        assertThat(snapshot.partial()).isTrue();
        assertThat(snapshot.nodes().stream().mapToInt(ProjectGraph.Node::upstreamCount).sum()).isEqualTo(1081);
    }

    @Test void overviewBrowserJourney() throws Exception {
        UUID developer = user("周航"), tester = user("陈希"), outsider = user("团队管理员");
        for (UUID member : List.of(developer, tester, outsider))
            db.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,?)",
                    bytes(workspace), bytes(member), member.equals(outsider) ? "ADMIN" : "MEMBER");
        for (UUID member : List.of(developer, tester))
            db.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MEMBER')", bytes(project), bytes(member));
        var tasks = new ArrayList<UUID>();
        String[] names = {"确定报表字段与接口", "完成报表页面与导出入口", "修复筛选后导出遗漏数据",
                "回归筛选与导出结果", "补齐报表使用说明", "完成报表发布验收", "发布报表功能公告",
                "整理功能截图素材", "评估导出列顺序自定义"};
        String[] statuses = {"DONE", "DONE", "TODO", "TODO", "IN_PROGRESS", "TODO", "TODO", "TODO", "TRIAGE"};
        String[] types = {"TASK", "FEATURE", "BUG", "TASK", "TASK", "TASK", "TASK", "TASK", "QUESTION"};
        UUID[] owners = {developer, developer, developer, tester, user, user, user, user, null};
        for (int i = 0; i < names.length; i++) tasks.add(task(names[i], statuses[i], types[i], owners[i]));
        for (int[] e : new int[][] {{0,1}, {1,3}, {2,3}, {3,5}, {4,5}, {5,6}})
            edge(tasks.get(e[0]), tasks.get(e[1]));
        String[] descriptions = {
            "确认客户、日期、金额等报表字段及接口返回约定。",
            "完成报表列表、筛选表单和导出入口，等待数据一致性回归。",
            "日期筛选后的导出数据与页面结果不一致。已复现导出请求漏传筛选参数，需要修复并核对跨页导出。",
            "修复完成后，比较筛选页面与导出行数，覆盖客户筛选、日期区间和跨页结果；记录回归结论。",
            "补齐筛选操作、导出限制和常见问题。说明编写可以与代码修复并行。",
            "发布前确认三项条件：导出数据与筛选结果一致、回归通过、使用说明补齐。周航负责修复，陈希负责回归，林悦确认验收。",
            "验收完成后整理并发布功能公告，说明报表入口、筛选和导出方式。",
            "整理报表页面和筛选操作截图，可与修复和说明编写并行推进。",
            "评估用户选择导出列顺序的需求，维护者确认范围和优先级后再安排。"
        };
        for (int i = 0; i < tasks.size(); i++)
            db.update("UPDATE task SET description=? WHERE id=?", descriptions[i], bytes(tasks.get(i)));
        String[] latest = {"回归将覆盖日期筛选、跨页导出和导出行数。", "已复现导出请求漏传日期参数，正在修复。",
                "验收需同时确认数据一致、回归通过和使用说明补齐。", "导出限制和常见问题说明可以与代码修复并行补齐。",
                "修复确认后由陈希开始回归；两项前置工作完成后，我再确认发布。"};
        UUID checklist = UUID.randomUUID(), steps = UUID.randomUUID();
        db.update("INSERT INTO project_document(id,project_id,kind,title,body,created_by) VALUES (?,?,'DOCUMENT',?,?,?)",
                bytes(checklist), bytes(project), "报表发布验收清单",
                "发布验收：导出数据与筛选结果一致；回归通过；使用说明补齐。林悦确认验收后再安排公告。", bytes(user));
        db.update("INSERT INTO project_document(id,project_id,kind,title,body,created_by) VALUES (?,?,'DOCUMENT',?,?,?)",
                bytes(steps), bytes(project), "筛选导出回归步骤",
                "准备两页以上数据；按客户和日期筛选；比较页面与导出行数；补充跨页验证结果。", bytes(user));
        db.update("INSERT INTO task_document(project_id,task_id,document_id) VALUES (?,?,?),(?,?,?),(?,?,?)",
                bytes(project), bytes(tasks.get(5)), bytes(checklist), bytes(project), bytes(tasks.get(2)), bytes(steps),
                bytes(project), bytes(tasks.get(3)), bytes(steps));
        UUID lastComment = null;
        for (int i = 1; i <= 52; i++) {
            lastComment = UUID.randomUUID();
            db.update("INSERT INTO comment(id,project_id,task_id,body,author_id,created_at) VALUES (?,?,?,?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))",
                    bytes(lastComment), bytes(project), bytes(tasks.get(5)),
                    i >= 48 ? latest[i - 48] : "验收讨论 " + i,
                    bytes(i == 48 ? tester : i == 49 ? developer : user), i);
        }
        db.update("INSERT INTO comment(id,project_id,task_id,body,author_id) VALUES (?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(project), bytes(tasks.get(8)),
                "<img src=x onerror=alert(1)>", bytes(user));
        UUID empty = project("新建项目");
        UUID secret = UUID.randomUUID();
        db.update("INSERT INTO project(id,workspace_id,name) VALUES (?,?,'未授权项目')", bytes(secret), bytes(workspace));
        var output = Path.of("target/project-overview-ui", UUID.randomUUID().toString()); Files.createDirectories(output);
        var seed = new LinkedHashMap<String,Object>();
        seed.put("base", "http://127.0.0.1:" + port); seed.put("project", project); seed.put("tasks", tasks);
        seed.put("user", "overview-" + user); seed.put("member", "overview-" + tester);
        seed.put("outsider", "overview-" + outsider); seed.put("password", password);
        seed.put("empty", empty); seed.put("secret", secret); seed.put("lastComment", lastComment);
        json.writeValue(output.resolve("seed.json").toFile(), seed);
        var command = new ProcessBuilder("node", "scripts/verify-project-overview-ui.cjs");
        command.environment().put("OVERVIEW_UI_SEED", output.resolve("seed.json").toAbsolutePath().toString());
        command.environment().put("OVERVIEW_UI_OUTPUT", output.toAbsolutePath().toString());
        command.redirectErrorStream(true); command.redirectOutput(output.resolve("browser.log").toFile());
        var process = command.start();
        assertThat(process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(output.resolve("browser.log"))).isZero();
        assertThat(json.readTree(output.resolve("result.json").toFile()).path("status").asText()).isEqualTo("PASS");
        System.out.println("PROJECT_OVERVIEW_UI " + output.toAbsolutePath());
    }
}
