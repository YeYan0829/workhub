# 工程概念：读当前代码时使用

**current · Phase 3 · 2026-09-27。** 这是按项目实例整理的学习笔记，不另立业务规格。先用[代码地图](CODE_MAP.md)找到入口，遇到机制问题再查这里。无需先学会手写整套框架。

## A. Repository 与 Service

Repository（数据访问层）回答“怎样读写数据”。例如 [TaskRepository.update](../src/main/java/dev/workhub/work/TaskRepository.java#L120) 执行带参数的 SQL，返回更新行数；它不知道当前 HTTP 用户是否是 Maintainer。

Service（业务服务层）回答“这次操作允许吗、需要一起改哪些数据”。[TaskService.applyUpdate](../src/main/java/dev/workhub/work/TaskService.java#L111) 检查权限、版本、字段和状态，再协同 Repository、Activity、Source。[DependencyService.execute](../src/main/java/dev/workhub/work/DependencyService.java#L78) 同样把多次边读写组织成一个业务操作。

`JdbcTemplate` 是 Spring 提供的 JDBC 包装：负责取得连接、执行参数化 SQL、把异常转换为 DataAccessException。Repository 中的 `RowMapper`（行映射器）把 ResultSet（SQL 查询结果的一行）变成 Java 对象，例如 `(r, n) -> ...` 这个 lambda（匿名函数）告诉它“每行怎样构造结果”。`?` 的值单独绑定，不靠拼接用户输入生成 SQL。

构造函数注入意味着 Spring 创建 Service 时把它所需的 Repository 等对象传入。`record` 是定义一组固定数据字段的 Java 语法；它不自动保存数据库、不自动产生业务规则。项目没有把每一张表都包装成独立 Entity。

## B. @Transactional：一组变化共同提交

以 [TaskService.create](../src/main/java/dev/workhub/work/TaskService.java#L45) 为例：

```text
Spring 事务代理开始事务
→ Task insert → Subscription → Source/Job/Outbox
→ Project revision → Activity/Notification → Receipt
→ 方法正常结束 → COMMIT
任一步抛出导致回滚的异常 → ROLLBACK
```

COMMIT 是提交，ROLLBACK 是撤销当前事务尚未提交的修改。否则可能出现“任务已创建，但收据没保存，重试又建一个”或“任务失败，却留下通知”的半成品。

注解的作用由 Spring 代理实现。Controller 拿到的是 Spring 管理的 Service；外部调用其 public 事务方法时，代理安排事务。private `applyUpdate/execute` 不需要再建事务，它们已经在外层 public 方法的事务里。自己 `new TaskService(...)` 或同对象内部直接调用另一个带注解的方法，不会凭空多经过一次代理。

当前写入口通常声明 READ_COMMITTED（每次读取已提交状态这一隔离级别），通过同一个事务关联的 JDBC 连接执行。默认传播 REQUIRED 表示：没有事务就开始，有外层事务就加入；不会每调用一个 Service 自动提交一次。

ApiException 是运行时异常，DataAccessException 也是；它们传播出事务边界时触发回滚。Dependency 捕获数据库限制后重新抛业务异常，不在 SQL 失败后继续成功返回。单条 SQL 失败不能被理解为前面所有 SQL 已自行回滚。`readOnly=true` 表达只读意图，也不替代权限检查。

证据：[TaskService](../src/main/java/dev/workhub/work/TaskService.java)、[DependencyService](../src/main/java/dev/workhub/work/DependencyService.java)、[ApiException](../src/main/java/dev/workhub/common/ApiException.java)、[RollbackIT](../src/test/java/dev/workhub/phase1/RollbackIT.java)。

<a id="locks"></a>
## C. FOR UPDATE：现在谁正在改

悲观锁的想法是：先取得互斥资格，再检查和修改共享状态。当前常见写调用链：

```text
TaskService / DependencyService / ContentService / ProjectService.member
→ ProjectAccess.lock
→ IdentityRepository.activeUser(...,true)：app_user 当前用户行 FOR SHARE
→ ProjectRepository.find：普通读取，确定所属空间
→ IdentityRepository.activeWorkspaceMember(...,true)：对应 workspace_member 行 FOR SHARE
→ ProjectRepository.lock：WHERE project.id = ? FOR UPDATE
→ 读取当前项目角色
```

`FOR SHARE` 是共享锁，用来保护授权相关记录；`FOR UPDATE` 是排他的锁定读取。这里项目互斥的中心是 **一条具体 Project 主键行**，不是锁一整张 task 表，不是 JVM 的 synchronized，也不是 SELECT 完就释放。事务提交或回滚时才释放持有的锁。成员关系 role 查询本身没有附加 FOR UPDATE；项目写入口遵守相同项目锁协议。

两个事务往同一项目加反向边时，后来的先等 Project 锁；拿到后再看当前 revision 和图。不同 JVM 连接同一数据库也受此锁约束。数据库外键/索引和其他 DML 还会产生各自的锁，不能据此断言整个事务只锁这一行。

创建 Project 是例外：项目还不存在，[ProjectService.create](../src/main/java/dev/workhub/identity/ProjectService.java#L32) 先通过 IdentityRepository.lockCreator 锁创建者 app_user 行。

证据：[ProjectAccess.authorize](../src/main/java/dev/workhub/identity/ProjectAccess.java#L28)、[ProjectRepository](../src/main/java/dev/workhub/identity/ProjectRepository.java)、[IdentityRepository](../src/main/java/dev/workhub/identity/IdentityRepository.java)、[ConcurrencyIT](../src/test/java/dev/workhub/phase1/ConcurrencyIT.java)。

<a id="revisions"></a>
## D. revision：我是不是基于旧数据修改

revision（版本号）让客户端表达“我基于版本 3 做的决定”。服务器现在是4，就拒绝旧决定。即使拿到了锁，也不能直接替用户把旧决定应用到新状态上。

| 版本 | 当前如何推进 | 在哪里检查 / 读取 |
|---|---|---|
| Task.revision | Task 实际字段变化、Task 文档/URL 关联实际变化；创建初值0 | TaskService.applyUpdate、ContentService.checkRevision 预检；TaskRepository.update 的 WHERE 再含 expected；HTTP 使用 Task If-Match |
| Document.revision | 文档 title/body 实际变化 | ContentService.editDocument + ContentRepository.updateDocument 条件 UPDATE；Document If-Match |
| Comment.revision | 评论 body 实际变化 | ContentService.editComment + ContentRepository.updateComment 条件 UPDATE；Comment If-Match |
| Project.businessRevision | Task create/applyUpdate、ContentService.changeLink、ProjectService.member、DependencyService.execute 各自在成功实际写入路径调用 increment | 只有 ProjectService.member 与 DependencyService.execute 调 ProjectAccess.expected；Task 修改不检查 Project expected |
| Task.discussionRevision | 评论新增/编辑时 discussionChanged +1 | TaskRepository 映射读取，TaskResponse 暴露；也随 Task 快照 JSON 出现。当前没有以它比较 expected 的写入或 Agent proposal 校验消费者 |
| Source.sourceRevision | 来源创建0，正文变化后 +1 | SourceObligations 放入工作 payload；IndexTextValidationHandler 校验请求 revision 与当前 Source |

不要把 businessRevision 理解为“项目任何表一有写入就自增”。文档/评论、订阅、通知已读不推进它；新建 Project 初值0。当前也没有“无论外层事务调多少个写方法都只增一次”的统一机制：是各 Service 显式 increment。Dependency.replace 在自己的整批 execute 中增一次；若一个外层事务调用多个写 Service，则可能增多次。

TaskRepository 的关键防线可压缩成：

```sql
UPDATE task SET ..., revision=revision+1
WHERE project_id=? AND id=? AND revision=?
```

最后一个参数就是 expected。影响行数不为1时 Service 抛冲突，外层事务回滚。Document/Comment 同理。Project.increment 本身不是这种 WHERE expected 的更新，它依赖调用者持锁以及需要时先做 expected 检查。

`If-Match` 是 HTTP 请求头，当前解析一个非负数字（可带引号）；Task get/PATCH 返回 ETag，表示当前 Task revision。ETag 不包含 discussionRevision，所以新评论不会使 Task 字段编辑自动发生版本冲突。Document/Comment 响应里有 revision，但 Controller 没有显式设置 ETag。`ResponseEntity` 是同时指定 HTTP 状态、头和正文的包装。

证据：[TaskRepository.update](../src/main/java/dev/workhub/work/TaskRepository.java#L120)、[ContentRepository](../src/main/java/dev/workhub/work/ContentRepository.java)、[ProjectRepository.increment](../src/main/java/dev/workhub/identity/ProjectRepository.java#L53)、[TaskController](../src/main/java/dev/workhub/work/TaskController.java)、[TaskResponse](../src/main/java/dev/workhub/work/TaskResponse.java)、[Inputs.ifMatch](../src/main/java/dev/workhub/common/Inputs.java#L24)。

<a id="idempotency"></a>
## E. Idempotency：同一次操作又发来了吗

Idempotency（幂等）在这里主要处理“服务器可能已成功，客户端没收到响应，于是重试创建”。它不是并发锁，也不表示任何 API 都接受随意重复。

| 数据 | 在 Work Hub 中的意义 |
|---|---|
| Idempotency-Key | 客户端给一次逻辑操作起的身份；由 HTTP header 传入，非空、最长128字符 |
| actor | 当前认证用户；同一个人自己的 key 空间 |
| request hash | 对约定请求结构的 JSON 做 SHA-256；检查相同 key 是否仍代表同一内容 |
| OperationReceipt | 数据库保存该 actor/key、hash、project、结果 ID 的登记；与业务写入一起提交 |

数据库主键准确是 **`(actor_id, request_key)`**，不是 `(project_id,key)`。同用户跨项目复用 key 也会遇到 hash 冲突。hash 输入含操作类型及项目/空间等上下文，避免把不同操作当成相同请求。它不是对所有语义等价请求做标准化的算法，例如 Task 创建传默认值与省略默认值可能是不同请求结构。

真正要求 K 的 HTTP 入口只有五类：Project create、Task create、Document create、Comment create、Dependency add。Controller 的 `@RequestHeader("Idempotency-Key")` 是来源，然后传给 Service；不是服务端随手新生成一个键。

创建请求流程：授权/锁 → 算 hash → `OperationReceipts.find`。无记录则创建并 `save`；同键同 hash 则读取原 ID 的**当前对象状态**；同键不同 hash 返回409。收据不是原始 HTTP 响应快照，也不永久缓存当时 revision。并发插入撞主键会转为409，当前事务整体回滚。

Dependency add 有两点特别：hash 包含 expectedBusinessRevision；同键重试必须保留原请求 expected，刷新 expected 后仍用旧键会 hash 冲突。同请求 receipt 命中发生在 stale 检查之前，返回当前 Project businessRevision；不会因为边后来被移除就重新插入。

remove/replace 把 null key 传给 execute，当前没有收据逻辑；这是现有 API 范围的选择，不是“删除天然不用考虑重试”的普遍定理。remove 原请求重试通常先遇到 Project stale；刷新版本后删除不存在的边会404。replace 仅为内部原子图操作，没有公开 HTTP 入口。

证据：[OperationReceipts](../src/main/java/dev/workhub/common/OperationReceipts.java)、[OperationReceiptRepository](../src/main/java/dev/workhub/common/OperationReceiptRepository.java)、[DependencyService.execute](../src/main/java/dev/workhub/work/DependencyService.java#L78)、[V1 receipt 主键](../src/main/resources/db/migration/V1__domain_task_dag.sql)、[API 索引](CODE_MAP.md#http)。

把四种机制分开记：

| 机制 | 问题 |
|---|---|
| FOR UPDATE | 有没有别的事务正在改这个共享状态？ |
| 对象 revision | 我是不是基于旧对象修改？ |
| Idempotency-Key | 这是不是同一次逻辑操作再次发送？ |
| Project businessRevision | 代码定义范围内的项目状态变过吗？成员/依赖请求的 expected 是否仍有效？ |

## F. keyset / cursor pagination：从上一页末尾继续

Keyset pagination（按排序键续查）用上一页最后一条的位置选下一页，不用 OFFSET 跳过前 N 条。[TaskRepository.list](../src/main/java/dev/workhub/work/TaskRepository.java#L59) 排序 `updated_at DESC, id DESC`；cursor（游标）是 `(updatedAt,id)`。

假设同一时刻有 ID 顺序 9、7、3，下一时刻更旧的记录为2。第一页取2条得到9、7；第二页使用“时间更旧，或时间相同且 ID 小于7”的条件，得到3、2。这里只用短数字演示排序，实际 ID 是 UUID/BINARY(16)。如果 cursor 只有时间，同时间的3可能丢掉；ID 提供平局时的明确顺序。

HTTP 使用明文 `Instant~UUID`，例如 `<UTC时间>~<UUID>`，不是加密/签名 token。TaskController.list 解码并传 Repository.Cursor，返回 `items,nextCursor`。满页就生成 nextCursor，没有额外取 limit+1 探测，所以最后一个满页之后可能还需请求一页空结果。

Document 复用同一个 Cursor 类型，也是 updatedAt/id 倒序。Comment 虽然复用了字段名叫 updatedAt 的 Cursor，实际查询按 **createdAt/id 正序**，下一页条件方向相反。ContentController 只返回 List，没有自动 nextCursor，客户端取末项相应时间和 ID 拼接。Activity/Notification 是 ID 倒序，使用 before，不用这个复合 Cursor。

游标不冻结一个跨请求快照。期间 Task updatedAt 变化会改变排序位置；当前没有“分页过程中绝不重复/遗漏”的额外一致性协议。

证据：[TaskController.list](../src/main/java/dev/workhub/work/TaskController.java#L78)、[TaskRepository.list](../src/main/java/dev/workhub/work/TaskRepository.java#L59)、[ContentController.cursor](../src/main/java/dev/workhub/work/ContentController.java#L163)、[ContentRepository.documents](../src/main/java/dev/workhub/work/ContentRepository.java#L65)、[ContentRepository.comments](../src/main/java/dev/workhub/work/ContentRepository.java#L131)、[QueryIT](../src/test/java/dev/workhub/phase1/QueryIT.java)。

<a id="dag"></a>
## G. Dependency DAG：判环与解释环分开

DAG 是有向无环图。A → B 中 A 是 blocker，B 是 blocked；`downstream(A)` 查询 A 的直接下游，`blockers(B)` 查询 B 的直接上游。这里的 Repository 两个方法都不遍历整个子图。

要插 A → B，先问当前图 **B 能否沿已有边到达 A**。如果能，再插 A → B 就闭成环。[DependencyRepository.reachable](../src/main/java/dev/workhub/work/DependencyRepository.java#L56) 用 recursive CTE（SQL 递归查询）沿 blocker→blocked 扩展：

```sql
WITH RECURSIVE reach(id) AS (
  起点 B
  UNION DISTINCT
  从已找到的节点，继续找本项目的直接下游
)
汇总是否包含 A，以及访问了多少节点
```

这段是语义示意，不是可复制运行的 SQL。生产查询只让节点 ID 参与去重，不把 path/depth 加入行；没有递归 LIMIT，也不把“查询被截断”解释为不可达。整个查询成功且目标不可达，才允许写边。

`reachable` 只需要回答 true/false；发现有环后，[DependencyService.witness](../src/main/java/dev/workhub/work/DependencyService.java#L186) 才另外用 BFS（广度优先搜索）找一个能解释错误的路径。队列保存待扩展节点，parent 映射记“第一次从谁走到这里”；找到 A 后沿 parent 回溯并反转，得到 B→…→A，再把拟加的 A→B 接上，返回 A→B→…→A。

witness 每次最多批量取128个节点的出边；单批查询取10001条用来检测是否超过10000，访问节点集合也有限制，超限返回明确错误。这个诊断限制不等于生产 CTE 只检查前10000个节点。

项目锁保证检查到插边期间，同项目其他遵守协议的图写入不会夹进来；组合 FK/自环 CHECK/唯一键保护端点和基础结构；CTE 才检查一般环。少掉任一层，防护范围都不同。

证据：[DependencyService](../src/main/java/dev/workhub/work/DependencyService.java)、[DependencyRepository](../src/main/java/dev/workhub/work/DependencyRepository.java)、[DagOracleIT](../src/test/java/dev/workhub/phase1/DagOracleIT.java)。

## H. DependencyBudget：共享剩余时间，不是锁

[DependencyService.execute](../src/main/java/dev/workhub/work/DependencyService.java#L78) 一开始建立 [DependencyBudget](../src/main/java/dev/workhub/work/DependencyBudget.java)，默认2秒，用 `System.nanoTime` 计时。授权、等待项目锁、CTE、witness 和后续写入都消耗同一份 elapsed 时间。`remainingMillis()` 给剩余毫秒，耗尽就抛 DEPENDENCY_CHECK_LIMIT；`check()` 只检查；`elapsedMicros()` 用来写日志。

需要分清“计入耗时”和“能在等待中主动中断”：

| 路径 | 实际保护 |
|---|---|
| 授权与 Project FOR UPDATE | Budget 没作为参数传入 ProjectAccess/ProjectRepository；取得锁后调用 check，发现等待已耗尽则失败。外层 Dependency public 方法的 Spring transaction timeout 经 JdbcTemplate 使用事务剩余时间设置 JDBC statement query timeout，才是等待期间的另一层保护 |
| reachable CTE | remainingMillis 填入 SQL 的 MAX_EXECUTION_TIME；SET_VAR 设置本语句递归上限；JdbcTemplate 同时受事务 timeout 约束 |
| witness outgoing 查询 | SQL MAX_EXECUTION_TIME 使用相同剩余预算，循环中继续 check |
| Java 业务代码 / 最后写入 | 在代码检查点发现耗尽；不是后台线程在第2000ms强行中断整个方法 |

当前**没有显式设置 `innodb_lock_wait_timeout`**，也没有把 budget.remainingMillis 逐次直接传给 FOR UPDATE。Spring 事务超时与 Budget 是两层配合，不是同一个计时器；加入已有外层事务时，不能假设内层注解重新开启自己的事务或重设外层 timeout。

递归超限、SQL 取消、锁等待等限制由 `isLimit` 和 TransactionTimedOutException 路径转换成503；异常继续向事务边界传播，前面删边/插边和其他写入一起回滚。驱动取消和调度有额外开销，不保证精确第2000ms返回。

日志 `lockWaitMicros` 从 execute 开始累计，也包含授权和校验，不是纯数据库等待时间；`heldMicros` 从 access.lock 返回算到 Service 的 finally，**不包含代理之后的 commit/rollback 尾部**。这些值不能直接当完整持锁基准。

证据：[DependencyBudget](../src/main/java/dev/workhub/work/DependencyBudget.java)、[DependencyService](../src/main/java/dev/workhub/work/DependencyService.java)、[DependencyRepository](../src/main/java/dev/workhub/work/DependencyRepository.java)、[ProjectRepository](../src/main/java/dev/workhub/identity/ProjectRepository.java)、[配置](../src/main/resources/application.yml)、[RollbackIT](../src/test/java/dev/workhub/phase1/RollbackIT.java)。

<a id="side-effects"></a>
## I. Subscription / Activity / Notification / Source

| 概念 | 回答的问题 | 当前代码 |
|---|---|---|
| Assignee | 谁负责完成 Task？ | Task.assigneeId |
| Dependency | 哪个 Task 约束哪个 Task？ | Dependency / DependencyRepository |
| Subscription | 谁关注后续变化？ | SubscriptionRepository.participate/set/subscribers |
| Activity | 发生了什么事实？ | ActivityService.record / ActivityRepository.insert |
| Notification | 哪条事实需要让哪个人看到？ | ActivityService.notify / ActivityRepository.notify |
| Source | 哪个业务对象的当前文本是知识来源？ | SourceObligations.changed |

新评论的例子：评论者参与 → 找 @mention 用户并登记参与 → 合并 active 订阅者 → 一个人的多种原因合成集合 → 建 COMMENT_CREATED 事实 → 过滤本人和失去权限的人 → 写收件人通知。自动参与只合并原因，不覆盖明确退订；但是直接提及本身仍能构成通知原因。这里只讲机制，具体用户规则在[产品说明](PRODUCT_AND_BUSINESS.md)。

Source 的后续三类持久记录也要分开：knowledge_source 保存来源身份、版本、正文 hash、canonicalUrl；work_job 保存“某来源某版本需要 INDEX_TEXT”这件逻辑工作；outbox_message 保存未来投递所需的消息身份和 envelope（消息外壳）。当前没有消费者、成功结果协议或搜索索引，登记后仍是 WAITING / PENDING / UNHANDLED。

上述业务副作用仍写同一 MySQL，并在外层业务事务里提交。Phase 2 只异步执行 Source 后台义务，通知没有迁入 RabbitMQ。

证据：[ContentService.createComment](../src/main/java/dev/workhub/work/ContentService.java#L103)、[SubscriptionRepository](../src/main/java/dev/workhub/work/SubscriptionRepository.java)、[ActivityService](../src/main/java/dev/workhub/activity/ActivityService.java)、[SourceObligations](../src/main/java/dev/workhub/knowledge/SourceObligations.java)、[V1](../src/main/resources/db/migration/V1__domain_task_dag.sql)。

## J. PATCH：省略、清空、完整新状态

PATCH 是局部更新。当前 [TaskRequests](../src/main/java/dev/workhub/work/TaskRequests.java) 使用 setter 记录 HTTP JSON 的存在性，不能简单记成“任何 null 都保留旧值”。

| 字段 | 省略 | JSON 显式 null |
|---|---|---|
| title/description/status/type/priority | Java 请求字段留 null，Service 保留旧值 | setter 拒绝，422 |
| assigneeId/dueDate | presence=false，保留旧值 | presence=true，值为 null，清空 |

未知 JSON 字段由 [Jackson 配置](../src/main/resources/application.yml) 拒绝。[TaskService.applyUpdate](../src/main/java/dev/workhub/work/TaskService.java#L111) 先把请求和 old 合并成完整新状态，再一起验证与比较。例如省略 title 时使用 old.title；传入负责人 null 时才用 null 清空。

若所有字段都没变，返回 old，避免无意义地推进 revision、写 Activity 和登记索引工作。注意 no-op 判断在授权、expected、字段/来源/TRIAGE 检查之后；旧版本请求不会因为“结果看起来一样”就被接受。

写入时 Repository 接收完整新状态，易于集中控制可写字段与 revision；这不是把客户端所有 JSON 自动映射到数据库列。实际修改后才根据变化类型产生必要副作用。对应测试见 [TaskDependencyIT](../src/test/java/dev/workhub/phase1/TaskDependencyIT.java)。

<a id="async"></a>
## L. 可靠异步的概念卡

这里只解释“为什么需要这个机制”；当前状态与失败边界见[架构说明](ARCHITECTURE.md#async)，配置数值以 [application.yml](../src/main/resources/application.yml) 为准，方法入口见 [CODE_MAP](CODE_MAP.md#messaging)。

| 概念 | 足以恢复理解的一句话 |
|---|---|
| Transactional Outbox | 先把“必须发送”的义务和业务数据一起提交，再异步发送，避免数据库成功但消息义务丢失的双写窗口 |
| At-least-once delivery | 发送方不知道上次是否成功时可以重发，所以消息可能出现多次；接收方必须允许这种重复 |
| Idempotent consumer | 在持久锁与状态检查下复用已提交结果，让重复消息不产生第二份本地效果；进程内布尔标记不足以防崩溃重投 |
| Publisher confirm / consumer ACK | 前者是 Broker 对发送方的确认，后者是 Consumer 对本次 delivery 的确认；都不能代替 WorkJob 的业务结果 |
| Mandatory return | 没有路由到队列时返回给发布者；confirm ACK 与 return 可以并存，因此正向 confirm 还不够 |
| DB commit before ACK | 先把效果或下一步处置持久提交，再放弃 Broker 的重投机会；顺序反过来会在崩溃窗口丢工作 |
| Publisher retry / execution retry | 前者重试运输同一消息，后者重试同一逻辑工作并建立新运输义务；Broker 断连不消耗业务失败预算 |
| Claim / lease | 暂时取得发送资格，超时后别人可接管；claim 不是业务已经执行成功 |
| Token fencing | 回写必须仍持有效领取 token，阻止过期 worker 覆盖新状态；无法撤销它已经发出的消息 |
| Sequence / disposition | sequence 区分当前运输代次，disposition 记住某条消息已被如何处置；两者共同防旧消息再次执行/排程 |
| Job / message / delivery 身份 | Job 是逻辑工作，messageId 是持久 Outbox 身份，deliveryTag 是 channel 内 ACK 编号；三者不可互换 |
| Failure audit / parking | 数据库保留失败事实，停车队列运输其摘要；停车不可达也不应丢掉审计记录 |

记住一个窗口就够：Handler 与 DONE/result 已提交，进程却在 ACK 前退出。Broker 重投后，Executor 读到已提交处置/终态，跳过效果再 ACK。正确性来自本地原子提交和锁后检查，而不是消息恰好只来一次。

Backoff（退避）把下次到期时间写入数据库；jitter（随机扰动）分散同时重试。publisher claim lease 与 Phase 3 跨事务 Job execution lease 解决不同问题：前者保护发布回写，后者保护本地阶段提交。持锁本地事务不能直接扩展为远端 HTTP；Phase 3 的 SEND_STARTED/VERIFY 协议在网络结果未知时只做读回，不重发旧 PATCH。
