# 生产代码地图与 API 索引

公开基线 · 2026-11-04。早期模块保留 Phase 命名以对应测试目录，当前助手与分诊入口见文末。 用法：先找入口，再读 Service，再查看当前用到的 Repository 方法；不要沿每个工具方法无限深入。下面省略构造函数、普通访问器和 DTO setter 明细。`private` 表示内部共享实现，不是可直接调用的业务 API。模块关系见[架构](ARCHITECTURE.md)，业务语义见[产品说明](PRODUCT_AND_BUSINESS.md)。Phase 2 可直接跳到 [messaging 入口](#messaging)，Phase 3 见 [GitHub 入口](#github)。

## 启动、配置与 identity

这个模块解决“谁在访问、能操作哪个项目”。核心必读 `SecurityConfiguration → CurrentActor → ProjectAccess`，再按需读项目与成员。

| 类 / 文件 | 重要方法、调用方向 |
|---|---|
| [WorkHubApplication](../src/main/java/dev/workhub/WorkHubApplication.java) | `main`：JVM 入口 → `SpringApplication.run`；排除默认 UserDetailsService 自动配置 |
| [SecurityConfiguration](../src/main/java/dev/workhub/config/SecurityConfiguration.java) | Spring 调用 `passwordEncoder`、`userDetailsService`、`securityFilterChain` 三个 Bean 工厂；登录适配 → IdentityRepository.findByLogin；配置 HTTP 认证、CSRF、登录/注销与安全错误；这些方法不是 public 业务 API |
| [IdentityBootstrap](../src/main/java/dev/workhub/identity/IdentityBootstrap.java) | Spring 启动回调 `run`：配置了 login 且用户表为空时直接 JDBC 建首个用户/空间/成员，使用 PasswordEncoder；不通过 HTTP。配置密码先校验，再检查表是否为空 |
| [ActorContext](../src/main/java/dev/workhub/identity/ActorContext.java) | record 保存 userId；`type()` 固定 HUMAN；不是客户端可指定身份/Agent actor 的实现 |
| [CurrentActor](../src/main/java/dev/workhub/identity/CurrentActor.java) | Controller 调 `from(Principal)` → IdentityRepository.findByLogin，确认有效用户，返回 ActorContext |
| [SessionController](../src/main/java/dev/workhub/identity/SessionController.java) | `csrf` 返回 token/headerName；`me` → CurrentActor.from + SessionService.workspaces；内部 `CsrfResponse`、`Me` 是响应 record |
| [SessionService](../src/main/java/dev/workhub/identity/SessionService.java) | `workspaces` → activeUser + IdentityRepository.workspaces；由 SessionController 调用 |
| [ProjectController](../src/main/java/dev/workhub/identity/ProjectController.java) | `create/list/get/member` → ProjectService 同名方法；接收 CreateProject / Membership 请求；成员 expected 由 Inputs.revision 解析 |
| [ProjectService](../src/main/java/dev/workhub/identity/ProjectService.java) | `create`：锁创建者 app_user → 空间授权 → receipt → Project 插入、创建者 MAINTAINER、receipt；`list/get`：读取并授权；`member`：项目锁 + Maintainer + expected → 成员 upsert → Project increment；调用 IdentityRepository、ProjectRepository、ProjectAccess、OperationReceipts、Json |
| [ProjectAccess](../src/main/java/dev/workhub/identity/ProjectAccess.java) | `read/lock` → private `authorize`；调用 IdentityRepository 的有效身份/空间/角色检查和 ProjectRepository.find/lock。`maintainer/isMaintainer` 角色检查；`canPrepareTask/canExecuteTask/canEditTask/editTask` 在已有项目访问检查后按 triageRequired、状态、创建者和当前负责人检查阶段权限；`assignee` 校验负责人；`expected` 比较 businessRevision。由各业务 Service 和 ActivityService 调用 |
| [ProjectRepository](../src/main/java/dev/workhub/identity/ProjectRepository.java) | `find/lock` → private `find(id, boolean)`：按 Project 主键读，lock 附加 FOR UPDATE；`insert` 建项目；`increment` 推进 business_revision；`list` 按空间及用户项目成员过滤。被 ProjectService/ProjectAccess 及 Task/Content/Dependency Service 使用 |
| [IdentityRepository](../src/main/java/dev/workhub/identity/IdentityRepository.java) | `findByLogin` 登录/身份转换；`activeUser/activeWorkspaceMember` 支持读或 FOR SHARE；`activeProjectMember` 联查有效用户/空间/项目资格；`role/membership` 项目角色与原成员状态；`workspaces` 用户空间列表；`saveMembership` 更新角色/active；`lockCreator` 锁 app_user 行；`activeMentions` 将项目内 login 解析为用户 ID。调用者是 config、identity Service、ContentService、ActivityService；`User/Membership/WorkspaceView` 为查询结果 record |
| [Project](../src/main/java/dev/workhub/identity/Project.java) / [ProjectRole](../src/main/java/dev/workhub/identity/ProjectRole.java) | Project 含 businessRevision 等项目数据；ProjectRole 仅 MEMBER、MAINTAINER。无独立 Workspace CRUD 模块 |

## work / Task

这组类解决“创建、读取、修改任务，并维护对应事实”。核心链：`TaskController.update → TaskService.update → applyUpdate → TaskRepository.update`。

### [TaskService](../src/main/java/dev/workhub/work/TaskService.java)

调用者：TaskController。主要依赖：TaskRepository、ProjectAccess、ProjectRepository、SubscriptionRepository、ActivityService、SourceObligations、OperationReceipts、Json、TaskReassignmentRepository。

| 方法 | 责任 |
|---|---|
| `create` | 授权锁、创建收据、普通成员强制 TRIAGE 并拒绝正式指派/优先级、默认值/字段校验、写 Task 与 triageRequired、参与订阅、来源义务、项目计数、创建事实与维护者/负责人通知 |
| `update` | public 事务入口，转入 `applyUpdate(..., false)` |
| `acceptTriage` | public 事务入口，Maintainer + TRIAGE/triageRequired 检查，要求有效负责人，将安排 Patch 与 TODO 状态一起转入 `applyUpdate(..., true)` |
| `get/list/blockers` | 只读事务；项目授权、查询、筛选/limit 校验；blockers 是直接上游 |
| private `applyUpdate` | 锁内读取、阶段编辑资格、正式范围/指派/审批状态维护者检查、Task expected、拼完整新状态、来源/TRIAGE 规则、no-op、条件 UPDATE、副作用 |
| private `require/closed/validatePriority/validateDescription` | 不存在转 404；判断 DONE/CANCELED；字段校验；不是新业务入口 |

### [TaskRepository](../src/main/java/dev/workhub/work/TaskRepository.java)

调用者：TaskService；ContentService 修改讨论/关联；DependencyService 校验端点并读取受影响任务；ActivityService 比较解除阻塞。只负责 JDBC，不判断当前 actor 权限。

| 方法 | 查询/写入能力 |
|---|---|
| `find` | project + task 范围查询；结果含 SQL 派生 isBlocked |
| `list` | status/assignee/priority 过滤，updated_at/id 倒序 keyset；`Cursor(Instant updatedAt, UUID id)` |
| `insert` | 新任务，origin 固定 MANUAL |
| `update` | 完整字段写回，Task revision +1、updatedAt 更新；WHERE 含 project、id、expected revision；返回受影响行数 |
| `downstream` | 直接下游列表，含当前 blocked，用于变更前快照 |
| `blockers` | 直接上游列表 |
| `discussionChanged` | 仅 discussion_revision +1；不推进 Task revision / updatedAt |

### Task 的 HTTP 与数据类型

- [TaskController](../src/main/java/dev/workhub/work/TaskController.java)：`create/get/update/acceptTriage/list/blockers` → TaskService 同名方法；get/update 用 ResponseEntity 设置 ETag；list 解码 cursor 并生成 TaskPage；接纳请求使用 TaskRequests.Patch。完整路径见下方 API 索引。
- [Task](../src/main/java/dev/workhub/work/Task.java)：内部 record；保存状态、负责人、来源、Task/discussion 两个版本、时间、triageRequired 待接纳标记及派生 isBlocked。
- [TaskRequests](../src/main/java/dev/workhub/work/TaskRequests.java)：`Create` record；`Patch` 用 setter 区分缺省与显式 null，assignee/dueDate 另有 presence 标记。不要把 setter 当业务入口。
- [TaskResponse](../src/main/java/dev/workhub/work/TaskResponse.java)：`from(Task)` 转 HTTP 响应，计算 `dependencyRisk = DONE && isBlocked`；`pending` 当前固定 null。
- [TaskStatus](../src/main/java/dev/workhub/work/TaskStatus.java)、[TaskType](../src/main/java/dev/workhub/work/TaskType.java)、[TaskOrigin](../src/main/java/dev/workhub/work/TaskOrigin.java)：合法取值；枚举存在不代表对应来源已经接入。

## work / Dependency

这组类解决“图修改不会制造环，批次失败可整体回滚”。建议先读 add 成功路径，再读 witness 错误路径。

| 类 | 方法 / 调用关系 |
|---|---|
| [DependencyController](../src/main/java/dev/workhub/work/DependencyController.java) | `add/remove` → DependencyService；Request 含 blockerId、blockedId、expectedBusinessRevision；只有 add 从 HTTP 取 Idempotency-Key |
| [Dependency](../src/main/java/dev/workhub/work/Dependency.java) | record 两端 UUID；方向 blocker → blocked |
| [DependencyService](../src/main/java/dev/workhub/work/DependencyService.java) | public `add/remove/replace` 是事务入口，统一调 private `execute`；replace 当前由测试使用，无生产 Controller/Agent 调用入口。Result 只含 businessRevision |
| [DependencyRepository](../src/main/java/dev/workhub/work/DependencyRepository.java) | `exists/insert/delete` 边读写；`reachable(project,start,target,budget)` 节点去重 CTE，返回 Reachability(boolean,long)；`outgoing` 为 witness 局部批量取边；均由 DependencyService 调用，依赖 JdbcTemplate、配置 maxDepth |
| [DependencyBudget](../src/main/java/dev/workhub/work/DependencyBudget.java) | 由 execute 创建；`remainingMillis` 返回剩余时间或抛校验限制；`check` 检查耗尽；`elapsedMicros` 日志计时。Service、Repository 共享一个实例；不是数据库锁 |

DependencyService 的内部实现：

| private 方法 | 责任 |
|---|---|
| `execute` | 编排预算、项目锁与被阻塞任务当前编辑权检查、add 收据、expected、端点、先删后加、CTE、事实通知、Project revision、收据；失败向事务边界抛出 |
| `validateBatch` | 1–20 个删/加操作，拒绝重复或同一边同时删加 |
| `validateEndpoints` | 两端非空、不同、存在于本项目；不存在或跨项目都以 PROJECT_SCOPE_ERROR 拒绝 |
| `concat` | 临时合并列表，供批次校验及影响范围收集 |
| `witness` | 已知有环后 BFS（广度优先搜索），记录 parent 找出具体路径；不是正常判环算法 |
| `isLimit` | 识别 SQL timeout 以及 MySQL 3636/3024/1317/1205，转 DEPENDENCY_CHECK_LIMIT |

Service 调用 ProjectAccess、ProjectRepository、TaskRepository、DependencyRepository、ActivityService、OperationReceipts、Json。图算法与预算细节见[工程概念](ENGINEERING_CONCEPTS.md#dag)。

## work / Content 与 Subscription

这组类解决“协作正文与引用怎么保存，谁关注后续评论”。Content 没有独立 package，它目前属于 work。

### [ContentService](../src/main/java/dev/workhub/work/ContentService.java)

调用者：ContentController。依赖 ContentRepository、TaskRepository、SubscriptionRepository、ProjectAccess/ProjectRepository/IdentityRepository、ActivityService、SourceObligations、OperationReceipts、Json。

| 方法 | 责任 |
|---|---|
| `createDocument` | 项目锁、创建幂等、默认分类、文档/来源/事实/收据 |
| `editDocument` | 文档 expected、完整 title/body 校验、no-op、文档更新/来源/事实 |
| `createComment` | Task 检查、幂等、新评论、discussionRevision、参与者/mention/订阅、来源/事实/通知/收据 |
| `editComment` | Comment expected、正文更新、discussionRevision、来源/事实；不重发评论通知 |
| `subscribe` | 锁项目并检查 Task，仅设置当前用户自己的订阅 |
| `linkUrl` | Task expected、HTTP(S) URL 校验；按项目 + URL 摘要查找/创建资源；转 `changeLink` |
| `linkDocument` | Task expected、同项目文档存在；按 add 参数关联/解除，再转 `changeLink` |
| `unlinkResource` | Task expected，转 `changeLink` 解除 URL 关联 |
| `getDocument/documents/comments/links` | 只读授权查询；links 返回内部 `Links(documents,resources)` record |
| private `changeLink` | 任务编辑资格检查；原关联状态相同则 no-op；改关联、Task revision、Project revision、LINK_ADDED/REMOVED 事实 |
| private `document/comment/requireTask` | 项目范围内读取，不存在转 404 |
| private `checkRevision` | 读取 Task 并检查 Task expected；保护关联列表 |

### [ContentRepository](../src/main/java/dev/workhub/work/ContentRepository.java)

被 ContentService 调用；依赖 JdbcTemplate，使用 common ID/UTC 转换。

| 方法 | 能力 |
|---|---|
| `insertDocument/document/documents/updateDocument` | 项目文档写入、详情、updatedAt/id 倒序分页、带 expected 的条件更新 |
| `insertComment/comment/comments/updateComment` | Task 评论写入、单条内部读取、createdAt/id 正序分页、带 expected 的条件更新 |
| `resourceByKey/insertResource` | 项目内按 provider_key（URL 摘要）复用/保存 URL；没有 HTTP 客户端 |
| `linked/link` | 根据 document 布尔参数在两种关联表中查询/增删；不是泛型内容平台 |
| `linkedDocuments/linkedResources` | 返回文档 ID 列表与资源列表 |

内嵌 `DocumentKind` enum、`Document/Comment/Resource` record 也作为当前 HTTP 数据形状使用。当前没有单独的 JPA Entity。

### [SubscriptionRepository](../src/main/java/dev/workhub/work/SubscriptionRepository.java)

- `participate`：TaskService/ContentService 登记自动参与原因；新记录默认订阅，重复记录只合并 auto_reasons，保留现有 active（包括明确退订）。user 为 null 时不写。
- `set`：ContentService.subscribe 调用；显式设置 active 与显式选择时间。
- `subscribers`：ContentService.createComment 调用；取 active 订阅者，实际通知前还由 ActivityService 过滤权限。

### [ContentController](../src/main/java/dev/workhub/work/ContentController.java)

HTTP 薄入口；重要方法：`createDocument/editDocument/document/documents/createComment/editComment/comments/subscription/links/linkDocument/unlinkDocument/url/unlinkUrl`，分别转入上表对应 Service。private `cursor` 解析 `Instant~UUID`。请求 record 为 DocumentRequest、TextRequest、SubscriptionRequest、UrlRequest。`editDocument` 在 Controller 拒绝非 null kind；路径和版本域见 API 表。

## activity

这个模块解决“发生了什么，以及要让谁看到通知”。它是当前数据库内副作用，不是消息总线。

| 类 | 方法 / 调用关系 |
|---|---|
| [ActivityController](../src/main/java/dev/workhub/activity/ActivityController.java) | `activities/notifications/read` → ActivityService.list/notifications/markRead；CurrentActor 提供身份 |
| [ActivityService](../src/main/java/dev/workhub/activity/ActivityService.java) | `record` → Repository.insert，供 Task/Dependency/Content Service 写事实；`notify` 过滤本人及无权收件人 → Repository.notify；`assigned` 只对变化后的非空负责人建原因；`taskChanged` 合并分诊、接纳决定与新指派的收件人原因，`notifyMaintainers` 通知当前有效维护者；`unblocked` 用 TaskRepository.find 比较 true→false，记录事实并通知当前有效负责人（保留操作者同时也是下游负责人的提醒）；`list/notifications` 授权只读；`markRead` 事务内只改当前收件人的通知 |
| [ActivityRepository](../src/main/java/dev/workhub/activity/ActivityRepository.java) | `insert` 保存 fact_key/前后状态 JSON，返回自增 ID；`notify` 插通知及原因集合；`list` 项目时间线 ID 倒序；`notifications` 当前收件人 ID 倒序；`markRead` 只更新 project + recipient + id，coalesce 保留第一次 readAt；内嵌 Activity / Notification record 为读取结果 |

ActivityService 的 record/notify/assigned/unblocked 不另开事务，依赖调用者的业务事务；读取与标记已读有各自事务注解。主要依赖 identity、common、TaskRepository、ActivityRepository。

## knowledge 与预留模块

[SourceObligations](../src/main/java/dev/workhub/knowledge/SourceObligations.java) 的 `changed/deactivate` 被业务服务调用，登记 TASK、COMMENT、DOCUMENT、GITHUB_COMMENT 的版本与工作义务，参与外层事务。开启检索后的写链为 [SourceText / TextGenerations](../src/main/java/dev/workhub/knowledge/TextGenerations.java) → IndexTextHandler → PublishTextHandler → ElasticsearchTextIndex；读链为 [KnowledgeService / KnowledgeReads](../src/main/java/dev/workhub/knowledge/KnowledgeService.java)。V12 管理 canonical 数据结构；[RetrievalIT](../src/test/java/dev/workhub/phase4/RetrievalIT.java) 验证真实 MySQL/MQ/ES，架构边界见[架构说明](ARCHITECTURE.md)。

项目 API：GET `/projects/{p}/knowledge/search?q=&limit=`、GET `/projects/{p}/knowledge/status`、POST `/projects/{p}/knowledge/rebuild`（Idempotency-Key、可续页 cursor / limit）。维护者 DELETE `/projects/{p}/documents/{d}` 使用 If-Match。公开评测导出与评分入口为 `prepare-phase4-eval.py`、RetrievalIT、`score-retrieval.py`；不另设生产批量导出接口。

[agent](../src/main/java/dev/workhub/agent/) 已实现项目只读问答、三种分诊调查策略、版本提案与人工批准，见文末 Phase 5A/5B 入口；[summary](../src/main/java/dev/workhub/summary/) 仍为预留模块。integration.github 已实现，见下方入口。测试侧 mock/contract 与生产能力、真实模型语义成绩应分别说明。

## common

这里解决重复使用的输入、错误、转换与持久收据问题。先看 Inputs / OperationReceipts，其余遇到再读。

| 类 | 方法与调用者 |
|---|---|
| [OperationReceipts](../src/main/java/dev/workhub/common/OperationReceipts.java) | `find(actor,key,hash)` 校验 key，查记录，无记录返回 null、hash 不同抛409；`save` 保存结果 ID，重复键转409使外层回滚；private `conflict` 构造错误。调用者 Project/Task/Content/Dependency Service |
| [OperationReceiptRepository](../src/main/java/dev/workhub/common/OperationReceiptRepository.java) | `find/insert` 操作 operation_receipt；Receipt record 含 hash、结果 id。唯一键是 actor_id + request_key，不含 project_id |
| [Inputs](../src/main/java/dev/workhub/common/Inputs.java) | `text` 必填/长度；`revision` 非负且必填；`ifMatch` 解析一个带或不带引号的数字；`hash` SHA-256；`limit` 1–100。被 Controller/Service/来源登记使用 |
| [ApiException](../src/main/java/dev/workhub/common/ApiException.java) | 业务运行时异常；`forbidden/missing/invalid/conflict/limit` 构造对应状态、代码及可选 revision/path |
| [ApiError](../src/main/java/dev/workhub/common/ApiError.java) | HTTP 错误 record；`from` 填响应，每次生成 requestId，503 标 retryable、409 标 refreshRequired；不是全链路追踪上下文 |
| [ApiExceptionHandler](../src/main/java/dev/workhub/common/ApiExceptionHandler.java) | Spring 回调 `business/malformed/database`（包可见方法）；映射业务异常、请求解析错误、DataAccessException。安全过滤器错误由 SecurityConfiguration 单独输出 ApiError；这里没有捕获所有 Exception |
| [Json](../src/main/java/dev/workhub/common/Json.java) | `write` 通过 ObjectMapper 编码对象；用于请求 hash 输入与事实/消息 JSON |
| [Ids](../src/main/java/dev/workhub/common/Ids.java) | `bytes/uuid/read`：UUID ↔ BINARY(16)，或从 ResultSet 读取；Repository 使用 |
| [Utc](../src/main/java/dev/workhub/common/Utc.java) | `read/value`：数据库 UTC 时间与 Instant/LocalDateTime 转换；Repository 使用 |

<a id="http"></a>
## 当前完整 HTTP 入口

以下覆盖 6 个 Controller 的 30 个映射，以及框架处理的登录、注销和 health。`p/w/t/d/c/r/u` 分别是项目/空间/任务/文档/评论/资源/用户 UUID；notification id 为 long。业务接口要求登录，写请求还要 CSRF。初始化和登录步骤见[仓库 README](../README.md#identity)。

`K` = 必填请求头 `Idempotency-Key`；`T/D/C` = 必填 `If-Match`，分别对应 Task/Document/Comment revision，例如 `"3"`；`P` = JSON 中 expectedBusinessRevision。缺少 If-Match/P 返回428，缺少必填 K 在请求解析阶段返回422。类型/默认值在[产品说明](PRODUCT_AND_BUSINESS.md)，机制见[学习笔记](ENGINEERING_CONCEPTS.md)。

| 方法与路径 | 真实入口 → Service | 输入与返回 |
|---|---|---|
| GET `/actuator/health`、`/actuator/health/**` | Actuator；Security 放行 | 健康探针；不等于业务完成度 |
| GET `/session/csrf` | SessionController.csrf | headerName/token |
| POST `/session/login` | Security formLogin | form username/password + CSRF；成功204，凭证失败401 |
| POST `/session/logout` | Security logout | CSRF；成功204 |
| GET `/session/me` | SessionController.me → SessionService.workspaces | userId、workspaces |
| POST `/workspaces/{w}/projects` | ProjectController.create → create | K；name；201 Project |
| GET `/workspaces/{w}/projects` | ProjectController.list → list | Project 列表 |
| GET `/projects/{p}` | ProjectController.get → get | Project，含 businessRevision |
| PUT `/projects/{p}/members/{u}` | ProjectController.member → member | P、role、active；Maintainer；返回 Project |
| POST `/projects/{p}/tasks` | TaskController.create → create | K；title 及可选本地字段；201 TaskResponse |
| GET `/projects/{p}/tasks/{t}` | TaskController.get → get | TaskResponse + ETag |
| PATCH `/projects/{p}/tasks/{t}` | TaskController.update → update | T；部分本地字段；TaskResponse + 新 ETag |
| POST `/projects/{p}/tasks/{t}/triage-accept` | TaskController.acceptTriage → acceptTriage | T；Patch 支持 assigneeId/priority/dueDate/type 等安排，要求有效负责人；Maintainer 原子接纳；TaskResponse |
| GET `/projects/{p}/tasks` | TaskController.list → list | status/assignee/priority/cursor/limit；TaskPage(items,nextCursor) |
| GET `/projects/{p}/tasks/{t}/blockers` | TaskController.blockers → blockers | 直接 blocker TaskResponse 列表 |
| POST `/projects/{p}/dependencies` | DependencyController.add → add | K、P、blockerId、blockedId；201 Result(businessRevision) |
| DELETE `/projects/{p}/dependencies` | DependencyController.remove → remove | JSON P、两端 ID；204；无 K |
| POST `/projects/{p}/documents` | ContentController.createDocument → createDocument | K；kind/title/body；201 Document |
| PUT `/projects/{p}/documents/{d}` | ContentController.editDocument → editDocument | D；title/body 必填，kind 不得为非 null；Document |
| GET `/projects/{p}/documents/{d}` | ContentController.document → getDocument | Document，含 revision，无显式 ETag |
| GET `/projects/{p}/documents` | ContentController.documents → documents | cursor/limit；Document 列表，无 nextCursor 字段 |
| POST `/projects/{p}/tasks/{t}/comments` | ContentController.createComment → createComment | K；body；201 Comment |
| PUT `/projects/{p}/tasks/{t}/comments/{c}` | ContentController.editComment → editComment | C；body；Comment |
| GET `/projects/{p}/tasks/{t}/comments` | ContentController.comments → comments | cursor/limit；Comment 列表，无 nextCursor 字段 |
| PUT `/projects/{p}/tasks/{t}/subscription` | ContentController.subscription → subscribe | active，仅修改自己；204 |
| GET `/projects/{p}/tasks/{t}/links` | ContentController.links → links | documents（ID 列表）、resources（id/url） |
| PUT `/projects/{p}/tasks/{t}/documents/{d}` | ContentController.linkDocument → linkDocument(...,true) | T；204 |
| DELETE `/projects/{p}/tasks/{t}/documents/{d}` | ContentController.unlinkDocument → linkDocument(...,false) | T；204 |
| PUT `/projects/{p}/tasks/{t}/url-links` | ContentController.url → linkUrl | T；url；返回 resourceId |
| DELETE `/projects/{p}/tasks/{t}/url-links/{r}` | ContentController.unlinkUrl → unlinkResource | T；204 |
| GET `/projects/{p}/activities` | ActivityController.activities → list | before/limit；Activity 列表 |
| GET `/projects/{p}/notifications` | ActivityController.notifications → notifications | before/limit；当前用户 Notification 列表；无 unread-only 参数 |
| POST `/projects/{p}/notifications/{id}/read` | ActivityController.read → markRead | 204 |

分页 limit 默认50、范围1–100。Task/Document cursor 为最后一项 `updatedAt~id`，Comment 为 `createdAt~id`；Task Controller 自动返回 nextCursor，另外两种由客户端从最后一项构造。Activity/Notification 使用最后一项 ID 作为 before。没有全图 GET、公开 replace、单条 Comment GET、Task DELETE。注册/邀请、知识搜索与Agent分别见后续新增入口。Source 中评论 canonicalUrl 是引用字符串，不能据此推断单条 Comment GET 已存在。

PATCH 示例（带有效会话和 CSRF）：

```http
PATCH /projects/{p}/tasks/{t}
If-Match: "3"
Content-Type: application/json
X-CSRF-TOKEN: <latest-token>

{"status":"DONE","assigneeId":null,"dueDate":null}
```

### 错误形状

统一业务/安全错误字段：`code,message,requestId,currentRevision,retryable,refreshRequired,path`。当前 cycle 才附具体 path。[ApiExceptionHandler](../src/main/java/dev/workhub/common/ApiExceptionHandler.java) 和 [SecurityConfiguration](../src/main/java/dev/workhub/config/SecurityConfiguration.java) 是两条输出路径。

| 状态 | 例子 |
|---|---|
| 401 | 登录失败 UNAUTHENTICATED |
| 403 | FORBIDDEN：未登录业务访问、CSRF 或项目权限失败 |
| 404 | 已授权项目内对象不存在 NOT_FOUND |
| 428 | 缺少版本 PRECONDITION_REQUIRED |
| 409 | TASK/PROJECT/DOCUMENT/COMMENT_REVISION_CONFLICT、IDEMPOTENCY_CONFLICT、DEPENDENCY_EXISTS |
| 422 | VALIDATION_ERROR、PROJECT_SCOPE_ERROR、DEPENDENCY_SELF_LOOP、DEPENDENCY_CYCLE、TRIAGE_ACCEPT_REQUIRED、EXTERNAL_OPERATION_REQUIRED |
| 503 | DEPENDENCY_CHECK_LIMIT 或 DATA_ACCESS_ERROR；写事务异常传播并回滚 |

这些是当前处理器覆盖的异常，不承诺任何未知异常都会变成上述形状。错误中的 retryable 不替客户端决定是否刷新版本或是否保留原幂等键。

## 测试、配置与脚本在哪里

- [phase1 测试](../src/test/java/dev/workhub/phase1/)：领域、真实 MySQL、API、会话、并发与回滚。Phase1Support 是测试初始化/连接/清理设施，不是生产 Service。
- [contract](../src/test/java/dev/workhub/contract/)、[unit](../src/test/java/dev/workhub/unit/)、[external](../src/test/java/dev/workhub/external/)：Phase 0 基础设施合同、离线 HTTP fixture、需另行授权的 live smoke；不能当生产功能入口。
- [application.yml](../src/main/resources/application.yml)、[正式 migrations](../src/main/resources/db/migration/)、[pom.xml](../pom.xml)、[compose.yaml](../compose.yaml)：实际配置和依赖；test resources 的 db/contract 只为隔离探针。
- [scripts](../scripts/)：`run.sh/run-app.sh/admin.sh` 加载本地环境运行；`bootstrap-jdk.py` 与 `jdk-lock.json` 安装校验过的 JDK；`init-env.py/mysql-init.sh/init-test-env.sh` 准备环境和隔离测试库；`test-core.sh` 运行核心回归；三个 `verify-*.cjs` 由 Java 集成测试调用。测试产物写入 `target/`。

<a id="messaging"></a>
## messaging / Phase 2 地址索引

先找下面对应的方法；事务与失败边界见[可靠异步执行](ARCHITECTURE.md#async)。

| 我想找什么 | 首先打开的类 / 方法 | 顺着看哪里 |
|---|---|---|
| 业务修改入口 | [ContentService.editDocument](../src/main/java/dev/workhub/work/ContentService.java#L80) | `sources.changed`；TaskService 的标题/描述变化也进入相同登记入口 |
| Source / WorkJob 初次登记 | [SourceObligations.changed](../src/main/java/dev/workhub/knowledge/SourceObligations.java) | MANDATORY；来源版本、Job payload/INSERT、Outbox.insert；Job INSERT 当前不在 WorkJobRepository |
| Outbox 登记与状态 | [OutboxRepository.insert](../src/main/java/dev/workhub/messaging/OutboxRepository.java#L59) | `claim` 83、`confirmed` 103、`publishFailed` 113、`disposition` 124；发布列/消费列各自更新 |
| 定时发布入口 | [OutboxPublisher.tick / publishOne](../src/main/java/dev/workhub/messaging/OutboxPublisher.java#L23) | claim → RabbitPublisher → token 条件回写/发布退避 |
| 真正发送 AMQP | [RabbitPublisher.publish](../src/main/java/dev/workhub/messaging/RabbitPublisher.java#L24) | messageId、精确 wire、persistent、correlation confirm / return |
| Exchange / queue / binding | [RabbitConfiguration.workTopology](../src/main/java/dev/workhub/messaging/RabbitConfiguration.java) | 实际参数来自 [application.yml](../src/main/resources/application.yml) / [Compose](../compose.yaml) |
| 消息进入 Java 与 ACK | [WorkJobConsumer.receive](../src/main/java/dev/workhub/messaging/WorkJobConsumer.java) | listener → executor.handle → basicAck；异常冷却/关 channel |
| 消息身份与内容验证 | [EnvelopeValidator.matches](../src/main/java/dev/workhub/messaging/EnvelopeValidator.java) | `identity`、持久 Outbox/Job、精确 body、hash、固定 routing 和 schema |
| 工作如何幂等执行 | [WorkJobExecutor.handle / execute](../src/main/java/dev/workhub/messaging/WorkJobExecutor.java) | `execute` 61 锁序和 Handler；`skip` 102 已处置/终态/旧代次；提交边界在 TransactionTemplate |
| Job 锁与结果保存 | [WorkJobRepository.find](../src/main/java/dev/workhub/messaging/WorkJobRepository.java) | `lockProject` 55、`done` 65、`retry` 75、`failed` 87 |
| Java Handler 如何定位 | [HandlerRegistry.require](../src/main/java/dev/workhub/messaging/HandlerRegistry.java#L17) | job.handler → 以 WorkHandler.kind 注册的唯一实例；不是 Rabbit routing |
| Handler 扩展契约 | [WorkHandler](../src/main/java/dev/workhub/messaging/WorkHandler.java) | `lockBusiness`、`execute`、Result；限定短本地 SQL，外部调用协议未提供 |
| INDEX_TEXT | [IndexTextHandler](../src/main/java/dev/workhub/knowledge/IndexTextHandler.java) | 检索开启时建立 canonical generation；关闭时 IndexTextValidationHandler 仅校验来源 |
| 可重试失败如何安排下一次 | [WorkJobExecutor.disposeFailure / schedule](../src/main/java/dev/workhub/messaging/WorkJobExecutor.java) | rollback 后事务、同 Job 新 Outbox、旧消息处置；[RetryPolicy](../src/main/java/dev/workhub/messaging/RetryPolicy.java#L9) / [WorkFailure](../src/main/java/dev/workhub/messaging/WorkFailure.java) 管预算/分类 |
| 终态失败 / quarantine / parking | [FailureRepository.record / quarantine](../src/main/java/dev/workhub/messaging/FailureRepository.java#L27) | 幂等 audit + 无 Job 的停车摘要 Outbox；没有自动 replay |
| 明确运输丢失后的恢复 | [TransportRecovery.run / recoverOne](../src/main/java/dev/workhub/messaging/TransportRecovery.java#L39) | 启动配置 epoch、未终态分页、每 Job 事务；当前 handler 的本地恢复边界 |

### 忘记保证时先找哪个测试

| 想核对的保证 | 测试入口 |
|---|---|
| 正常执行、重复消息只有一份 effect | [ExecutionIT.endToEndCommitAndDuplicateAck](../src/test/java/dev/workhub/phase2/ExecutionIT.java#L22) |
| 同时执行与不同消息指同 Job | [ExecutionIT.twoConsumersAndDifferentMessageSameJobHaveOneEffect](../src/test/java/dev/workhub/phase2/ExecutionIT.java#L37) |
| retry 提交后丢 ACK、旧/早到消息 | [ExecutionIT.retryCommitLostAckOldAndEarlyDeliveryNeverDuplicateOrExecuteEarly](../src/test/java/dev/workhub/phase2/ExecutionIT.java#L70) |
| 无事务拒绝、回滚与真实 validation Handler | [ExecutionIT.sourceRegistrationRollbackAndMinimalHandler](../src/test/java/dev/workhub/phase2/ExecutionIT.java#L171) |
| claim fencing / 发布失败 / return | [PublisherIT](../src/test/java/dev/workhub/phase2/PublisherIT.java#L17) |
| commit 后 ACK 前进程退出不重复 effect | [CrashRecoveryIT.consumerProcessExitRedeliversWithoutDuplicateEffect](../src/test/java/dev/workhub/phase2/CrashRecoveryIT.java)；故障位置在 [CrashWorker](../src/test/java/dev/workhub/phase2/CrashWorker.java#L61) |
| DB 无法提交不能 ACK | [ConsumerOutageIT](../src/test/java/dev/workhub/phase2/ConsumerOutageIT.java#L13) |
| 自动调度与真实 listener | [ListenerIT](../src/test/java/dev/workhub/phase2/ListenerIT.java#L28) |
| broker 重启/删队列、旧 schema 升级 | [BrokerRestartIT](../src/test/java/dev/workhub/phase2/BrokerRestartIT.java)、[MigrationIT](../src/test/java/dev/workhub/phase2/MigrationIT.java) |
| 离线协议边界 | [ProtocolTest](../src/test/java/dev/workhub/phase2/ProtocolTest.java#L17) |

[Phase2Support](../src/test/java/dev/workhub/phase2/Phase2Support.java) 是隔离测试设施；TEST Handler 写的 phase2_effect 计数表不在生产 schema。basicGet 后手动调用 Consumer 的用例与自动 listener 用例分开，当前运行入口见[测试说明](../README.md#测试)。


<a id="github"></a>
## Phase 3 GitHub

| 入口 | 职责 |
|---|---|
| [GitHubService](../src/main/java/dev/workhub/integration/github/GitHubService.java) / [Controller](../src/main/java/dev/workhub/integration/github/GitHubController.java) | 连接、webhook 原子接受、intent API、sync view、人工 reconcile |
| [GitHubHandler](../src/main/java/dev/workhub/integration/github/GitHubHandler.java) | ROUTE/REFRESH/DISPATCH/VERIFY/SCAN/AUDIT/COMMENTS/EVIDENCE；跨事务步骤、lane fencing 与后继义务 |
| [RemoteWorkHandler](../src/main/java/dev/workhub/messaging/RemoteWorkHandler.java) / [WorkJobExecutor](../src/main/java/dev/workhub/messaging/WorkJobExecutor.java) | prepare/perform/complete、RUNNING token、提交后 ACK、持久延迟 |
| [GitHubClient](../src/main/java/dev/workhub/integration/github/GitHubClient.java) | App JWT/token 缓存、固定 API header、单次 HTTP、限流与未知结果 |
| [GitHubStore](../src/main/java/dev/workhub/integration/github/GitHubStore.java) | 连接/资源/意图读取、事务内阶段登记与同步诊断 |
| [TaskService.applyGitHub](../src/main/java/dev/workhub/work/TaskService.java) | confirmed Task diff、副作用及索引来源义务；保留本地字段 |
| [GitHubComments](../src/main/java/dev/workhub/integration/github/GitHubComments.java) / [GitHubEvidence](../src/main/java/dev/workhub/integration/github/GitHubEvidence.java) | 外部评论身份/来源/通知/删除；PR/CI/commit 只读身份校验 |
| [GitHubMaintenance](../src/main/java/dev/workhub/integration/github/GitHubMaintenance.java) | lease 到期补运输、周期 scan/audit、未知结果后续观察、持续错误 episode 通知 |
| [phase3 测试](../src/test/java/dev/workhub/phase3/) | 离线合同、MySQL/fake/Rabbit、子 JVM 退出、V4→V6 迁移 |

HTTP 路径与请求字段见 [GitHubController](../src/main/java/dev/workhub/integration/github/GitHubController.java)，部署前置条件见[根 README](../README.md#可选能力)。


## Phase 3.x HTTP 与业务入口

所有写入口仍要求 session + CSRF；公开账号动作只放开身份认证，不豁免 CSRF。错误统一 ApiError：403 无权限、409 版本/生命周期冲突、410 凭证或文件过期、413 上传超限、422 字段校验、429 限频。旧接口继续有效。

| HTTP | 业务服务 / 阅读目的 |
|---|---|
| POST `/account/register`, `/account/verification`, `/account/verify`, `/account/forgot`, `/account/reset` | [Accounts](../src/main/java/dev/workhub/onboarding/Accounts.java)：验证与重置一次性合同；注册、重发验证和重置请求支持可选 `invitationToken`，须为收件邮箱匹配的有效邀请 |
| GET/PATCH `/account/profile`；POST `/account/password`, `/account/email` | 当前账号资料、原密码校验、旧 session 失效 |
| POST `/workspaces`；GET/PATCH `/workspaces/{w}` | [Workspaces](../src/main/java/dev/workhub/onboarding/Workspaces.java)：空间管理与版本 |
| GET/PUT `/workspaces/{w}/members[/{u}]` | 空间角色、退出/移除和最后管理员保护 |
| GET `/me/invitations`；POST `/me/invitations/{id}/accept` | 当前已验证邮箱的待处理邀请；应用内接受与邮件接受共享事务及邮箱校验 |
| POST `/invitations/preview` | 持有 token 可匿名查看邀请及注册/验证引导，仍要求 CSRF；不授予成员权限 |
| GET/POST `/workspaces/{w}/invitations`；DELETE `.../{id}`；POST `/invitations/accept` | 邀请、撤回、邮箱绑定与重复接受；错误账号返回 `INVITATION_ACCOUNT_MISMATCH` |
| GET `/workspaces/{w}/management/projects`；POST `.../{p}/handover`；GET `/workspaces/{w}/management/activities` | 不暴露正文的管理摘要及显式维护者交接 |
| PATCH `/projects/{p}`；GET `/projects/{p}/members` | [CollaborationService](../src/main/java/dev/workhub/work/CollaborationService.java)：详情、归档/恢复、成员视图 |
| GET `/projects/{p}/task-search`, `/me/tasks` | q/status/assignee/blocked/due、workspaceId/projectId、offset/limit（1–100）；返回 items/offset/hasMore/projects（归属元数据），个人入口始终限定当前负责人 |
| GET `/me/projects` | 有效项目权限下的团队/项目筛选项，最多1000条；ADMIN 不自动获得正文项目选项 |
| GET `/me/notifications`、`/me/notifications/unread-count` | [InboxController](../src/main/java/dev/workhub/activity/InboxController.java)：workspaceId/projectId/unreadOnly/before/limit；列表返回 items/hasMore/nextBefore，全局未读数只统计当前可访问项目 |
| POST `/me/notifications/{id}/read` | 当前收件人及项目授权，Workspace → Project 锁内标读；已读重复请求稳定，越权不暴露所属项目 |
| GET `/projects/{p}/tasks/{t}/detail` | 返回 createdBy、canEditTask、canManageTask、canManageDependencies、canPrepareTask、canRequestReassignment、reassignmentRequests，归档时写能力均为 false；有界聚合；更多评论/文档/活动仍使用原分页接口 |
| POST `/projects/{p}/tasks/{t}/reassignment-requests` | [TaskReassignments](../src/main/java/dev/workhub/work/TaskReassignments.java)：当前负责人，K，reason + expectedTaskRevision；201 申请；通知维护者 |
| POST `.../reassignment-requests/{id}/resolve` | 维护者，If-Match 申请 revision；decision APPROVE/REJECT、批准时 assigneeId、note、expectedTaskRevision；原子决定与改派 |
| DELETE `/projects/{p}/tasks/{t}/comments/{c}` | 作者或维护者删除，需要 If-Match |
| POST multipart `/projects/{p}/imports` | [CsvImports](../src/main/java/dev/workhub/importing/CsvImports.java)：file + Idempotency-Key，冻结输入预检查 |
| GET `/projects/{p}/imports[/{id}]`；POST `.../{id}/confirm`, `.../{id}/resume` | 用户进度、失败/未处理、原导入恢复 |
| GET `.../{id}/source`, `.../{id}/report` | 权限重查与30天保留期 |
| GET `/projects/{p}/github/setup`, `/repositories`, `/connections`, `/operations`；PATCH `/connections/{id}` | [GitHubManagement](../src/main/java/dev/workhub/integration/github/GitHubManagement.java)：维护者接入状态（不返回凭证）、授权范围与业务结果 |
| POST `/projects/{p}/github/connections/{id}/reconcile` | 返回 operationId；UNKNOWN 只读观察，无重发入口 |

部署命令与普通 HTTP 用户操作分离：[DeploymentAdmin](../src/main/java/dev/workhub/admin/DeploymentAdmin.java)、[scripts/admin.sh](../scripts/admin.sh)。页面实现：[app.js](../src/main/resources/static/app.js)。owner 无需逐类阅读，先按 [三条架构路径](ARCHITECTURE.md#owner-tour) 找对应测试。

任务阅读模式与权限回归：[TaskPermissionsIT](../src/test/java/dev/workhub/phase3x/TaskPermissionsIT.java) 检查各角色 API 写权限、改派后撤权与归档。

新增迁移：[V10](../src/main/resources/db/migration/V10__task_reassignment_requests.sql) 保存负责人交接申请，生成列唯一索引约束每任务仅一份待处理申请；[V11](../src/main/resources/db/migration/V11__triage_mirror_approval.sql) 规范化既有 TRIAGE 镜像的待接纳标记。


## Phase 5A 新增入口

| 入口 | 职责 |
|---|---|
| [AgentController](../src/main/java/dev/workhub/agent/AgentController.java) | POST `/projects/{p}/agent/runs`：会话+CSRF+Idempotency-Key，body.question，202；GET `.../{id}` 只返回本人且当前仍授权的运行 |
| [AgentRuns](../src/main/java/dev/workhub/agent/AgentRuns.java) | AgentRun/RUN_AGENT/Outbox/receipt同事务；读取重新验权/验版本 |
| [RunAgentHandler](../src/main/java/dev/workhub/agent/RunAgentHandler.java) | RemoteWorkHandler短事务prepare/complete，锁外有界网络，persisted deadline/attempt fencing |
| [AgentEngine](../src/main/java/dev/workhub/agent/AgentEngine.java) | 原生自主只读Tool Calling循环、结构化最终回答与引用校验 |
| [AgentTools](../src/main/java/dev/workhub/agent/AgentTools.java) | 与 AgentContent 合计12个读工具，严格参数，当前DB事实+现有检索，source/version复核 |
| [GlmChatModel](../src/main/java/dev/workhub/agent/GlmChatModel.java) | Spring AI1.1.8 Z.AI标准API适配，无隐式付费retry，受控HTTP/超时错误 |
| [AgentAudit](../src/main/java/dev/workhub/agent/AgentAudit.java) | 独立审计事务、跨attempt token/tool/cost预留及实际usage |
| [V14](../src/main/resources/db/migration/V14__readonly_project_agent.sql) | agent_run/trace/budget/model_call；无Proposal或业务写入表 |
| [AgentConversation](../src/main/java/dev/workhub/agent/AgentConversation.java) | 完整会话、对象编号、引用编号及失效来源更新 |
| [AgentBaselineIT](../src/test/java/dev/workhub/phase5a/AgentBaselineIT.java) | 真实业务 Service/MySQL/ES/HTTP、本机模型替身、来源及费用验收 |
| [AgentTransportIT](../src/test/java/dev/workhub/phase5a/AgentTransportIT.java) | 隔离库及真实 Rabbit：发布、恢复、重复投递 |
| [project.json](../src/test/resources/phase5a/project.json) | 实际业务夹具、用户问题与期望答案 |


### Phase 5B：单任务分诊

- `AgentRuns.createTriage` / `RunAgentHandler` / `AgentEngine`：服务端绑定任务，独立结构化建议模式，工具仍只读。
- [TriageProposals](../src/main/java/dev/workhub/agent/TriageProposals.java)、`TriageProposalController`、V16：不可变版本、预览绑定、来源重验、事务接纳与唯一收据。复用 `TaskService.acceptTriage`，不重复实现状态机。
- `app.js` 的 `triageProposalPanel`：任务详情的分析、编辑、预览、批准及结果回读。
- [TriageProposalIT](../src/test/java/dev/workhub/phase5b/TriageProposalIT.java)、`scripts/verify-phase5b-ui.cjs`、`src/test/resources/phase5b/triage.json`：明确合成的免费业务与浏览器验证。工程验证与真实模型质量分开，能力范围见[产品规则](PRODUCT_AND_BUSINESS.md)。


### 分诊调查策略（2026-10-14）

- `agent/TriageStrategy.java`：WORKFLOW / GUIDED / AGENT；`TriageContext.java`：固定只读资料收集，策略不同不改变工具授权。
- `AgentRuns.TriageRequest`、V17：保存策略与版本合同；`AgentEngine`：固定采集/补查阶段边界与工具总量；`AgentConversation`：调查结果严格解析。

### 自动分诊、进度与会话

- [AutomaticTriage](../src/main/java/dev/workhub/agent/AutomaticTriage.java)：项目开关、首次任务触发、停止标记与审计；[AutomaticTriageIT](../src/test/java/dev/workhub/phase5b/AutomaticTriageIT.java) 验证业务边界。
- [AgentProgressStreams](../src/main/java/dev/workhub/agent/AgentProgressStreams.java)：持久快照与 SSE；[AgentProgressIT](../src/test/java/dev/workhub/progress/AgentProgressIT.java) 包含断线和刷新恢复的浏览器验证。
- [conversation 测试](../src/test/java/dev/workhub/conversation/)：连续对话、历史、权限和子进程恢复，使用独立测试数据库。

仓库保留业务工程测试，不包含历史评测实验、Judge 脚本或开发集成绩。
