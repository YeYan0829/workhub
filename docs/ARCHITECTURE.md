# 架构与核心流程

WorkHub 是一个 Spring Boot 单体应用，前端由应用提供静态 HTML、CSS 和 JavaScript，后端使用显式 JDBC 和 Flyway 迁移。当前入口为 [WorkHubApplication](../src/main/java/dev/workhub/WorkHubApplication.java)，配置见 [application.yml](../src/main/resources/application.yml)。

```mermaid
flowchart LR
    UI[浏览器] --> API[HTTP / Session / CSRF]
    API --> B[业务服务与项目授权]
    B --> DB[(MySQL)]
    DB --> O[Outbox 发布]
    O --> MQ[RabbitMQ]
    MQ --> W[持久任务执行]
    W --> ES[(Elasticsearch)]
    W --> GH[GitHub / SMTP]
    W --> G[模型用量与速率准入]
    G --> R[(专用 Redis)]
    G --> M[模型 HTTP]
    ES --> B
```

MySQL 保存业务、权限、会话、工作状态和模型用量记录；RabbitMQ 负责运输与缓冲。Elasticsearch 是可重建的检索投影。独立 Redis 仅在开启共享模型准入时协调速率、冷却和熔断，不能代替 MySQL 持久调用记录。

## 容器运行

[Dockerfile](../Dockerfile) 使用固定摘要的 Maven/Java 17 构建镜像和 Java 17 JRE 运行镜像。构建上下文由 [.dockerignore](../.dockerignore) 限定为应用源码、构建配置、探针源码与许可文件；本机 JAR、凭据、文档及测试输出不进入构建。镜像以 UID/GID 10001 运行，包含 MIT 许可，Java 进程直接接收停止信号。

Compose 的 `app` profile 启动应用与基础中间件；应用等待数据库和 RabbitMQ 健康后启动，Flyway 执行迁移。容器内使用服务名和内部端口连接，外部端口保持 loopback 绑定。默认 `async` profile 保留宿主机开发方式，检索和共享模型准入分别使用额外 profile。

数据库、消息队列和应用密钥使用独立命名卷。RabbitMQ 使用固定 hostname，容器替换后保持相同节点身份与消息存储目录。应用密钥路径为 `/var/lib/workhub/mail.key`，重建应用容器会复用 `app-data`；CSV 源文件及结果存储在数据库。RabbitMQ 健康探针以 rabbitmq 用户运行，避免首次启动时探针生成 root 所有的 cookie；其 CLI 调度线程和探针超时有界配置，启动等待不改变业务执行期限。

## 宿主机开发

保留容器中间件，在宿主机运行应用时，需要 Bash 和 Java 17：

```bash
python3 scripts/init-env.py
docker compose up -d --wait
scripts/run.sh -DskipTests package
scripts/run-app.sh
```

默认 `async` profile 只启动中间件。已有 JDK 时设置 `JAVA_HOME` 或将 `java` 放到 PATH；Linux x86_64 也可运行 `python3 scripts/bootstrap-jdk.py` 下载锁定的 JDK。同一个数据库同时只运行一种应用启动方式。

宿主机默认 `.tools/mail.key` 和容器的 `app-data` 各自保存邮件密钥；已有数据库切换运行方式时，需要一起迁移原密钥。宿主机端口与容器内部服务地址分别配置，容器内部连接使用服务名及内部端口。

## 模块入口

| 模块 | 责任与主要入口 |
|---|---|
| `config` / `identity` | Session、CSRF、当前身份与项目授权：[SecurityConfiguration](../src/main/java/dev/workhub/config/SecurityConfiguration.java)、[ProjectAccess](../src/main/java/dev/workhub/identity/ProjectAccess.java) |
| `onboarding` | 账号、邮件验证、邀请、团队成员生命周期：[Accounts](../src/main/java/dev/workhub/onboarding/Accounts.java)、[Workspaces](../src/main/java/dev/workhub/onboarding/Workspaces.java) |
| `work` / `activity` | 任务、依赖、正文、订阅及通知：[TaskService](../src/main/java/dev/workhub/work/TaskService.java)、[DependencyService](../src/main/java/dev/workhub/work/DependencyService.java)、[ContentService](../src/main/java/dev/workhub/work/ContentService.java) |
| `messaging` | Outbox、发布确认、消费确认与可恢复执行：[OutboxPublisher](../src/main/java/dev/workhub/messaging/OutboxPublisher.java)、[WorkJobExecutor](../src/main/java/dev/workhub/messaging/WorkJobExecutor.java) |
| `integration.github` | Inbox、同步、远端意图和未知结果核对：[GitHubService](../src/main/java/dev/workhub/integration/github/GitHubService.java)、[GitHubHandler](../src/main/java/dev/workhub/integration/github/GitHubHandler.java) |
| `knowledge` | 正文版本、索引生成与检索：[SourceObligations](../src/main/java/dev/workhub/knowledge/SourceObligations.java)、[KnowledgeService](../src/main/java/dev/workhub/knowledge/KnowledgeService.java)、[DenseSearch](../src/main/java/dev/workhub/knowledge/DenseSearch.java) |
| `agent` | 只读调查、提案批准、调用治理与会话：[AgentEngine](../src/main/java/dev/workhub/agent/AgentEngine.java)、[TriageProposals](../src/main/java/dev/workhub/agent/TriageProposals.java)、[ConversationService](../src/main/java/dev/workhub/agent/ConversationService.java) |
| `importing` | CSV 预检查、逐行提交及恢复：[CsvImports](../src/main/java/dev/workhub/importing/CsvImports.java) |

容器模式下，可用镜像内的 Java 执行部署管理命令，无需宿主机 JDK。以下参数由部署者依据自己的授权仓库填写：

```bash
docker compose --profile app exec app java \
  -Dloader.main=dev.workhub.admin.DeploymentAdmin \
  -cp /opt/workhub/workhub.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  github-grant <Workspace-UUID> <installation-ID> <repository-ID> <owner/repo>
```

## 业务写入与权限

Controller 解析 HTTP 并建立当前 Actor，Service 负责授权、版本和事务，Repository 使用参数化 SQL。Workspace 成员身份不会自动授予所有项目访问权；Workspace ADMIN 也不能直接读取未授权项目正文。

业务写通常先取得 Workspace 授权屏障，再锁 Project，随后检查对象版本并修改业务。撤权与后台批次提交遵循相同锁序。项目锁保障并发正确性，也会成为热点项目的吞吐限制。任务和文档使用版本检查拒绝旧表单覆盖；数据库组合外键保护项目范围，但不代替有效成员校验。

创建任务的典型事务为：授权 → 创建幂等收据检查 → Task → Subscription / Activity / Notification → 来源与后台工作义务 → 保存收据 → 提交。事务失败时业务与义务共同回滚。Actor 类型和项目范围由服务端确定，模型不能通过参数冒充用户。

同项目依赖通过递归 CTE 判环，Project 锁阻止并发创建相互成环的边；检查时间预算耗尽则整体回滚。非 DONE 的直接上游造成阻塞，CANCELED 仍阻塞；阻塞用于提示风险，不强制禁止下游完成。

待分诊任务由维护者确定正式安排并接纳。接纳后，创建者身份不再额外授予编辑权；负责人可推进执行和维护允许的内容，正式安排与改派由维护者管理。交接申请在批准前不改变指派。归档保留可读历史并拒绝新业务写入；最后一位管理员或维护者受保护。

## 页面交互与查询

创建任务、文档和评论时，浏览器保留当前提交的请求标识与内容。响应丢失后，确认原请求会复用同一标识；成功返回后才结束这次创建意图。输入暂时锁定，避免在结果不明时悄悄换成另一份内容。后端仍按当前身份检查权限和幂等收据。

任务详情默认展示最新评论，并通过时间与 ID 游标加载较早讨论。引用深链可以直接定位同任务的历史评论；默认升序评论接口保留给既有调用方。文档列表可继续加载，关联文档使用当前项目内的文字搜索；关联标题直接随关系元数据返回，不依赖首批文档列表。解除关联保留原资料，并检查当前编辑权与任务版本。

项目助手通过受项目授权保护的接口返回可用状态与原因码。页面按团队未配置、连接停用、项目模型失效等原因提示下一步操作；历史会话继续可读。

## 后台执行

业务数据、WorkJob 和初始 Outbox 在同一事务登记。发布器以短事务领取 Outbox，在事务外发送 RabbitMQ，再按领取 token 条件提交结果。租约到期可能产生重复运输，消费端必须依赖持久状态去重。

发布调度每轮最多处理 20 条，并在每次确认后检查 200ms 调度预算，避免单条/每轮的固定等待限制突发工作吞吐。`WORKHUB_PUBLISH_BATCH_SIZE` 和 `WORKHUB_PUBLISH_BATCH_MS` 可调整上限；设为 1 可复现逐条调度。预算在操作之间检查，正在等待的发布仍遵守独立确认超时。每条继续独立领取、发送与确认，保留 `SKIP LOCKED` 和 token 条件更新；批处理不改变消息身份、重复消费或模型准入规则。

[WorkJobConsumer](../src/main/java/dev/workhub/messaging/WorkJobConsumer.java) 在 Executor 成功提交后才 ACK。无法持久提交时不确认消息；失败处置先回滚本地效果，再保存重试或终态及停车义务。发布确认不等于业务完成，工作终态也不代表所有外部事实已经确认。

本地 Handler 在短事务中提交效果；[RemoteWorkHandler](../src/main/java/dev/workhub/messaging/RemoteWorkHandler.java) 将准备、外部 I/O 和条件完成分开。attempt token、租约和资源代次拒绝旧执行者提交。该模型依赖至少一次运输和幂等本地效果，不提供跨系统原子性或严格 exactly-once。

CSV 按稳定行身份逐行提交，结果区分成功、失败和未处理。撤权、归档及过期会阻止后续提交；恢复重查当前维护权，已经成功的行不重复创建任务。这牺牲整文件原子性，换取有界事务和可解释的部分结果。

## GitHub 同步与远端意图

GitHub 通知先进入 Inbox，再登记资源观察和后台工作；不直接把 webhook 当作最终业务事实。Issue 首次确认后成为本地镜像，远端标题、正文与关闭状态由读取结果确认，本地接纳和执行安排单独保存。

远端修改依次经历 PRECHECK GET、持久化 SEND_STARTED、PATCH 与 GET 核对。SEND_STARTED 之后恢复执行只登记 VERIFY，避免网络超时后盲目重发可能已经生效的写入。即使 PATCH 返回 2xx，也要经读取和业务应用确认；发送标记已保存却尚未发包时同样采用保守观察。GitHub 并发编辑仍存在无法通过本地锁消除的窗口。

自动分析默认关闭。开启后，可信创建时间晚于开启时间、首次同步且仍待分诊的新 Issue 可以原子登记一次分析；历史回填不触发模型。首次发现分类、唯一任务分析键和版本校验阻止重复登记。关闭时结束排队的自动分析，运行中的下一次模型准入被阻止；已经发送的请求继续记账。

GitHub App 安装归属由部署者预授权，不提供公开 OAuth/安装回调的自动绑定。Agent 批准只执行本地接纳，不修改远端 labels、assignee 或评论。

## 检索与来源有效性

正文变化在业务事务中保存来源 revision/hash 和索引义务。开启检索时，后台生成不可变 canonical chunks，通过发布任务写入 Elasticsearch，按来源、generation 和租约条件激活；关闭时仅做来源校验。旧校验任务成功不能冒充真实索引发布。

BM25 提供词法候选，Dense 使用团队 Embedding 连接、项目 active 空间和向量产物，Hybrid 在核验两路候选后执行 RRF。查询结果按来源去重，并经 MySQL 重查当前权限、正文版本和 active generation，返回原文位置及实际检索策略。检索降级显式标识；结构化状态、负责人和依赖通过业务工具查询。

来源有效不等于回答正确，top-k 为空也不能证明全项目不存在相关资料。候选窗口和分块有界，`retrievalComplete` 不表示全库召回或 ES 物理完整。自动物理对账和旧索引 GC 尚未实现，当前由维护者 rebuild 恢复。

## 项目助手与提案批准

Run 与后台工作义务共同登记。[AgentTools](../src/main/java/dev/workhub/agent/AgentTools.java) 只提供绑定项目的读取工具；模型不能提交 Actor、任意 SQL 或业务写操作。工具循环限制轮次、工具次数、输入输出容量与总期限。检索正文属于不可信资料；引用身份和来源版本由服务端绑定，后续读取和发布重新核验。

单任务分诊有固定资料 WORKFLOW、固定资料加有限补查 GUIDED 和自主只读调查 AGENT 三种策略。处置建议、相关任务和待补问题均先保存供人核对；负责人由维护者选择，疑似重复不自动合并任务。

提案版本只追加。预览凭据绑定维护者、版本与内容 hash；明确批准时重查当前权限、任务与讨论版本、负责人和实际观察来源，再由 TaskService 原子接纳并保存唯一执行收据。并发批准串行化，成功收据用于恢复重复请求；编辑草稿或替换版本后不能沿用旧预览。已知范围外的新资料不自动使整个提案过期。

## 模型治理、会话与恢复

[GovernedModelRequests](../src/main/java/dev/workhub/agent/GovernedModelRequests.java) 为每次请求执行权限检查、持久登记及可选共享准入，等待和模型 I/O 不持有业务数据库事务。MySQL 保存每次物理尝试和原始用量，UTC 月份仅用于查询归属，专用 Redis/Lua 按稳定团队域原子维护令牌桶、冷却和熔断；没有状态时拒绝出站，需显式初始化恢复。429 和指定 5xx 采用分类处理与有限重试，未知网络结果不盲重发；每次重试重新准入。

限流许可在 HTTP 发送边界检查有效期，过期则重新申请。登记、发送和响应是不同事实；进程丢失或调用结果未知时保留未知状态，不自动当成零用量或重发。共享配额不提供 Workspace 公平队列或生产容量承诺。

私人 Conversation 保存 Turn、消息、摘要与 Run checkpoint。上下文按容量压缩，保留近期轮次、完整工具调用配对以及服务端来源和对象注册表；原消息保留并支持有界回读。checkpoint 只在完整工具边界保存；无法确认旧模型响应时明确失败，避免重发未知调用。夹具能验证这些工程路径，真实模型的指代、纠正和摘要保真需分别评估。

阶段进度采用持久快照和 SSE。每次发送前重查会话与当前权限，Servlet 写入前结束数据库事务；重连读取当前快照，不通过重连创建新 Run。多个应用可读取共同数据库中的同一 Run。快照不包含模型输入或思维过程，也不是完整事件重放日志。

取消和删除阻止后续准入与迟到发布。会话删除清理在线正文、摘要、checkpoint 和来源副本，保留无正文调用骨架；备份按部署保留政策处理，不能将在线清理理解为已擦除所有备份。

旧问答导入保留原提问与回答时间、最后活动时间和引用；重复导入返回同一会话，不产生模型请求。


## 团队模型治理 API

文本生成默认采用团队 BYOK。部署者设置 `WORKHUB_AGENT_ENABLED=true`、`WORKHUB_MODEL_ADMISSION_ENABLED=true`，并在环境中提供 `WORKHUB_MODEL_MASTER_KEY`（32 个随机字节的 Base64，可用 `openssl rand -base64 32` 生成）。容器启动需同时增加 `model-governance` profile。主密钥必须与数据库一起备份；缺失、错误或密文验证失败均拒绝使用密钥，不生成临时主密钥，也不保存明文。不要记录配置请求体或认证头。

一个 Workspace 只有一个当前连接。团队 ADMIN 管理连接和调用速率，项目 MAINTAINER 设置项目模型与单次分析边界；普通成员按当前项目权限发起分析。Workspace ADMIN 可查询本团队用量数字，不因此取得其他项目正文或私人会话访问权。所有接口沿用 Session 与 CSRF，PUT 使用完整配置和 `expectedVersion`；首次创建为 0，之后提交 GET 返回的版本，冲突为 `MODEL_CONFIG_CONFLICT`。

| 接口 | 请求或响应要点 |
|---|---|
| `GET /workspaces/{w}/model-catalog` | ADMIN；返回服务端允许的供应商与模型，供配置表单选择 |
| `GET/PUT /workspaces/{w}/model-connection` | ADMIN；PUT 字段为 `expectedVersion, provider, model, allowedModels, enabled, requestsPerSecond, burst, apiKey`；GET 返回 `hasKey, keyMarker, version, quotaScope` 等脱敏信息 |
| `GET/PUT /projects/{p}/model-policy` | GET 需项目访问权，PUT 需 MAINTAINER；字段为 `expectedVersion, model, maxCalls, maxMillis`；`model=null` 继承团队默认模型；`modelAllowed=false` 表示团队移除了该模型，可按当前版本更新修复 |
| `GET /workspaces/{w}/model-usage` | ADMIN；可选 `project`、`period=YYYY-MM-01`、`limit`（1–100，默认 100）；最多返回 100 个 run/模型/版本分组 |
| `GET /projects/{p}/model-usage` | 当前项目成员；相同月份和结果上限；仅返回本项目用量 |

连接更新时 `apiKey=null` 或省略字段表示保留现有密钥；非空字符串表示替换，空字符串无效；首次配置必须提供。禁用使用 `enabled=false`，保留密文与历史。服务器目录 [AgentProvider](../src/main/java/dev/workhub/agent/AgentProvider.java) 固定供应商、端点、协议与模型：`ZAI` 允许 `glm-4.7` / `glm-5.3`，`DASHSCOPE` 允许 `qwen3.7-plus`，`ARK` 允许 `doubao-seed-2-1-pro-260628`。客户端不能提交端点、价格、连接 ID 或用量归属。协议与币种完全独立。

团队文本生成不配置或估算调用金额，也不读取余额、兑换币种或实施金额预算。价格、充值、余额及财务硬限额由团队在供应商 API 平台管理。`model-budget` 接口已移除；项目策略也不接受 `maxCostNanos`。已有金额配置不再参与团队调用准入，不迁入新统计或重新计价。

项目默认继承团队模型，最多 20 次物理尝试、300 秒。MAINTAINER 可设置 1–20 次与 1–300 秒。回答、工具循环、摘要与 HTTP 重试共用同一 run 限额；失败或明确未发送的尝试也计入次数，防止无限准备/重试。输入输出容量、工具次数、平台线程/队列及准入等待上限继续生效。自动分析沿用 `/projects/{p}/agent/triage-settings` 的现有开关和触发规则。

### 调用记录与配置失效

[TeamModels](../src/main/java/dev/workhub/agent/TeamModels.java) 从项目关系解析 Workspace，在 `team_model_execution` 保存连接版本、供应商、模型及执行限制。`team_model_usage` 保存每次尝试的项目、run、attempt、UTC 月份、模型/连接版本、用途、输入输出本地上界、供应商报告的 input/output tokens、耗时和错误。执行用户和后台触发来源可从 run 追踪。队列只传 run ID，密钥不进入消息、run 合同、用量接口或管理审计。AES-GCM 使用随机 nonce 和 Workspace AAD，防止跨团队替换密文。

[TeamModelUsage](../src/main/java/dev/workhub/agent/TeamModelUsage.java) 在短事务中锁定 run，检查累计次数后登记尝试。流程为 `当前权限/版本检查 → 执行边界检查 → 登记 → Redis 准入 → HTTP → 记录结果和用量`。不存在月度金额锁、预留、退款或结算。HTTP 编码、Redis 等待和网络 I/O 均在事务外；所有 SQL 授权检查均在获取短许可之前完成，随后保留 500 ms 许可有效期检查。数据库检查与网络发送之间仍有短时间窗口，无法提供跨系统原子撤权。

执行状态与用量完整性分开记录：`PENDING` 表示尚无持久结果，`COMPLETE` 表示已收到明确响应（错误由 error_code 区分），`UNKNOWN` 表示请求结果不明，`NOT_SENT` 表示有同步证据证明没有发送。`usage_state` 为 `KNOWN / PARTIAL / UNKNOWN / NOT_APPLICABLE`。供应商缺少或返回无效计数时对应字段为 NULL；有效回答不因缺少 usage 被拒绝。明确报告的零是有效值，本地字节上界不是供应商 token 数，报告值超过本地上界也不会触发金额拒绝。超时、进程退出和未知结果不伪造为零，也不自动重放；重复完成不重复累计。

用量接口按 run/项目/模型/版本分组，返回 `attempts, completedCalls, successfulCalls, failedCalls, pendingCalls, unknownCalls, notSentCalls, knownUsageCalls, partialUsageCalls, unknownUsageCalls, inputTokens, outputTokens, elapsedMillis`。Token 合计只包含已报告的字段，全部缺失时为 NULL，必须结合缺失计数解读；不是完整账单或计费 token 统计。目前标准化 input/output tokens，不推断缓存命中、阶梯价格或最终账单。接口最多返回指定分组数，不把截断结果当作团队总量，不返回正文或提示词。

连接每次修改都会递增版本。轮换、禁用、模型或限额变更后，旧 run 在后续发送授权中以 `MODEL_CONNECTION_CHANGED` 停止；项目策略编辑也使旧 run 失效。已发调用继续记录在原执行和原月份。人工执行重查当前用户与项目权限，自动分诊使用项目当前自动分析授权；检查点恢复重查配置和权限，不能改用另一凭据。结果未知会阻止自动恢复；仅 usage 缺失但响应和检查点完整时可以继续。

### Redis 初始化和恢复

团队域为连接 GET 返回的 `quotaScope`（`team-<workspace UUID>`），不包含 key 或模型。所有应用实例必须使用同一部署主密钥、MySQL 和专用 Redis，并保持一致的应用版本。不同团队独立配额的前提是供应商额度独立；同账号的不同 key 或模型未必独立，系统不会自动识别。

首次配置后由部署者在 Redis 所在主机显式初始化（容器端口通常仅内部可见，宿主机按 Compose 映射设置 `--port`）：

```bash
python3 scripts/model-admission.py initialize --scope team-<workspace-uuid> \
  --team-version 1 --rate 4 --burst 2 --port 16380 \
  --recovery-ms 1000 --confirmed-prior-cooldown
```

替换示例中的 UUID、版本、速率和突发容量为连接 API 的当前值。恢复等待必须覆盖 `ceil(1000 * burst / rate)` 与先前供应商冷却期；初始化从零 token 开始，已有状态不会覆盖。`inspect` 用相同 scope 检查状态。Redis 缺失、断开或配置不一致时拒绝发送；业务 CRUD 继续可用。

已有域的更高配置版本在 Lua 中原子迁移，清空剩余 token 并保留恢复时间、冷却、熔断、代次与在途回执；旧版本无法覆盖新配置。旧调用回报的 429 仍作用于原域。轮换、切模型、重启应用都不会补满新桶。Redis 丢失后不能确定旧冷却，必须人工核查后显式恢复；异常 Retry-After 会要求同样的运维恢复。

### 迁移和兼容

[V23](../src/main/resources/db/migration/V23__team_model_governance.sql) 保留原团队金额表；[V24](../src/main/resources/db/migration/V24__team_model_usage_only.sql) 新增纯用量执行/调用表，并允许项目策略省略旧金额字段。已有 V14/V15 部署账本和 V23 团队周期、价格、RESERVED/UNKNOWN 记录及外键保留，不重算、不转零、不自动释放。新团队执行仅写 V24 用量表。旧费用模式的未完成 run 必须重新发起，不能恢复到新凭据/新口径；部署升级前停止旧 worker 并处理在途请求，避免混跑两个版本。

默认不使用 `WORKHUB_GLM_API_KEY` 等部署级生成凭据。显式 `WORKHUB_AGENT_LEGACY_MODE=true` 只保留原部署/测试兼容路径，旧金额属性仅在此模式使用；该开关影响整个部署，不是团队缺配置时的回退，也不是新团队功能。团队执行不能转为 legacy 执行，旧执行也不能自动借用新团队凭据。

[V25](../src/main/resources/db/migration/V25__team_embedding.sql) 新增团队 Embedding 连接、不可变向量空间、项目路由、调用操作和纯用量记录；[V26](../src/main/resources/db/migration/V26__team_embedding_provider_workspace.sql) 保存供应商业务空间 ID。旧 V13 预算、调用、向量产物和部署空间保留，不自动分配团队归属，也不转换成新空间。历史连接缺少供应商业务空间 ID 时须由管理员更新，系统不会猜测账号或继续请求旧地址。

### 团队 Embedding 与索引切换

部署启用 `WORKHUB_RETRIEVAL_ENABLED=true`、`WORKHUB_EMBEDDING_ENABLED=true`、共享 Redis 和模型加密主密钥，并启动 `retrieval` / `model-governance` profile。项目默认不发送资料给 Embedding 供应商，须由项目 MAINTAINER 显式启用。默认团队模式不读取 `MODEL_API_KEY` 或部署级 Embedding 金额授权；未配置不会回退。仅为旧部署/夹具保留显式 `WORKHUB_EMBEDDING_LEGACY_MODE=true`，它不与团队路由混用。

[TeamEmbeddings](../src/main/java/dev/workhub/knowledge/TeamEmbeddings.java) 分离三个对象：团队连接管理凭据和速率；不可变空间管理模型、维度和输入规则；项目策略管理启用状态及 active/building 指针。当前目录支持百炼北京地域的 `text-embedding-v4`，维度为 64、128、256、512、768、1024、1536、2048；`providerWorkspaceId` 是百炼业务空间 ID，区别于 WorkHub Workspace UUID。后端只接受合法的单个 DNS 标签，并拼接固定的 `cn-beijing.maas.aliyuncs.com` 原生接口地址，不接受任意 URL。参数范围和请求结构参照[百炼同步接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。

| 接口 | 权限与字段 |
|---|---|
| `GET /workspaces/{w}/embedding-catalog` | ADMIN；返回当前模型、合法维度及地域 |
| `GET/PUT /workspaces/{w}/embedding-connection` | ADMIN；PUT 完整字段为 `expectedVersion, model, dimensions, queryInstruction, enabled, useGenerationKey, requestsPerSecond, burst, apiKey, providerWorkspaceId`；GET 返回版本、密钥存在标记、默认空间、有效 quota 和 configurationIssue，不返回密文/明文 |
| `GET /workspaces/{w}/embedding-spaces` | ADMIN；最多返回最新 100 个空间的模型、维度、输入规则和身份；不能改写已创建空间 |
| `GET/PUT /projects/{p}/embedding-policy` | 当前成员可读，MAINTAINER 可写；PUT 为 `expectedVersion, enabled, configId`；`configId=null` 选择当前团队默认空间；GET 包含 active/building、可选团队空间、当前资料数量、向量覆盖、未结束及累计失败任务数 |
| `POST /projects/{p}/knowledge/embedding-rebuild` | MAINTAINER；请求 `expectedVersion`，为当前 building 或 active 空间登记持久回填，不改变已启用指针 |
| `POST /projects/{p}/knowledge/embedding-activate` | MAINTAINER；请求 `expectedVersion`，仅在 building 空间覆盖当前所有可见资料时切换；未追平返回 `EMBEDDING_INDEX_NOT_READY` |

独立连接首次提供 `apiKey`，后续 null/省略保留，空字符串无效；禁用保留凭据和历史。`useGenerationKey=true` 只允许显式复用已启用的百炼生成连接，且不同时提交 apiKey；数据库不复制生成密文。改回独立连接时须提供独立 key。复用时采用生成连接的有效速率和 `team-<workspace UUID>` 配额域；独立连接使用 `team-embedding-<workspace UUID>`。GET 的 `quota` 返回真正生效的 scope/version/rate/burst；按前述初始化命令替换这些值。各域均保持已有冷却、熔断和单调版本规则，密钥不进入域名。供应商是否按账号或模型共享额度需团队自行核实。

例如首次独立配置的请求体如下；通过已登录 Session 和 CSRF 发送到团队接口。主密钥由部署者提供，供应商 key 由团队提供：

```json
{"expectedVersion":0,"model":"text-embedding-v4","dimensions":1024,"queryInstruction":"Retrieve relevant project documents.","enabled":true,"useGenerationKey":false,"requestsPerSecond":4,"burst":2,"apiKey":"<team-api-key>","providerWorkspaceId":"<bailian-business-space-id>"}
```

创建/更新连接时，相同模型空间身份复用同一 configId；key、连接版本和供应商业务空间账号不进入向量指纹。供应商/地域/协议、服务端模型合同版本、维度、查询指令、文档输入形式、分块和编码规则进入指纹，变化建立新空间及独立物理索引。服务端合同版本并非供应商权重快照；供应商若改变同名模型语义，须更新目录合同并构建新空间，不能声称旧向量自动兼容。团队之间即使参数相同也不共享空间或产物。

项目维护者启用目标空间后，持久重建任务每批扫描最多 100 个来源，复用现有 canonical TEXT 分块，经 `TEAM_EMBED_TEXT → TEAM_EMBED_CHUNK → TEAM_EMBED_PUBLISH` 形成 VECTOR 代次。文档、任务和评论的后续变化同时投递 active 与 building 空间。旧 active 继续服务；删除和撤权立即由当前正文回读过滤，不等待 ES 清理。维护者切换时短事务锁定 Project，再核对当前源版本覆盖，原子修改指针。切换失败或未完成重建不会替换旧指针；旧空间保留，可按同样流程选择旧空间、追平后切回。

[TeamEmbeddingCalls](../src/main/java/dev/workhub/knowledge/TeamEmbeddingCalls.java) 的查询和后台调用统一经过当前权限/项目授权、连接/策略版本、持久尝试记录及 Redis 准入。输入本地字节上界为 8192，单次完整 HTTP 响应最多 20 秒、1 MB，单次准入等待最多 15 秒、同步操作总期限 60 秒。一次同步调用最多增加一次明确 429/502/503/504 重试；每个持久分块或搜索操作跨恢复最多登记 3 次。网络超时、进程退出或成功响应丢失且未形成产物时不自动重发。确认未发送标为 NOT_SENT；明确 HTTP 错误与未知网络结果分开保存。供应商没返回 usage 时仍接受合格向量，inputTokens=NULL，不估算 token 或金额。

模型等待和 HTTP 不持有数据库事务。请求体、密钥解析和 SQL 检查均在 Redis 短许可之前完成，过期许可重取；后台只在当前来源与路由仍有效时保存产物。产物按 source/config/inputHash 复用、首次提交获胜。ES 部分写入或发布响应丢失只重发不可变索引数据；同空间重建和索引丢失恢复使用已有产物。轮换凭据不会重算已有向量，未完成任务可用 rebuild 继续补齐。

[TeamDenseSearch](../src/main/java/dev/workhub/knowledge/TeamDenseSearch.java) 固定读取一个 active 空间，以同一空间生成查询向量并搜索，不混用不同维度或模型。无 active、连接不可用、供应商失败或 ES 故障时回到 canonical BM25，并返回实际 strategy、requestedStrategy、degradedReason 和 retrievalComplete。权限撤销返回禁止访问。结果正文、引用和源版本仍由 MySQL 当前权限回读校验；Agent 工具检索也接入此路径，在等待期间重新检查 run 的取消、当前权限、配置和总期限。项目自动分析使用服务器项目授权，不把原启用者当作仍有成员权限的用户。

统一 `model-usage` 接口增加 `capability` 与 `executionId`：生成按 run，Embedding 按搜索操作或后台 job 归属；后台索引 actorId=NULL，触发来源为 INDEX，项目授权的自动查询为 AUTOMATIC_QUERY。查询结果携带 executionId，Agent 工具输出可据此关联调用记录。Embedding 的 outputTokens 不适用，为 NULL；仅记录供应商报告的输入用量，不把不同模型 token 当作同一账单。生成的 maxCalls 限制文本生成尝试；检索操作另受上述调用次数、工具次数及 run 总期限约束，后台任务受单独的 prefetch-one Embedding 消费队列约束。没有金额、月度 token 硬预算或跨团队公平调度。


### 配置页面与用量展示

团队管理员从团队页进入“模型服务”，分别管理文本生成和 Embedding。供应商与可选模型由目录接口提供。密钥输入留空表示保留，首次配置或更换供应商时填写新密钥；成功保存后清空输入。Embedding 可以独立配置密钥，也可选择复用已启用的百炼文本生成凭据及其有效速率。

项目成员在“模型与检索”查看当前模型策略、向量空间和项目用量；项目维护者可选择团队默认或指定模型，配置最多 1–20 次文本生成请求及 1–300 秒分析期限。配置表单按 expectedVersion 保存，冲突时保留当前输入，重新载入后核对新版本。

向量页面分别展示 active、building 和目标资料覆盖数。后台准备新空间期间，active 空间继续服务；覆盖当前可见资料后由维护者确认切换。待处理和累计失败数是项目检索任务计数，包含文本索引及其他空间任务，覆盖就绪由目标空间数据与 activate 接口共同核验。页面每四秒刷新在建进度，支持显式刷新、同空间重建及选择旧空间追平后切回。提交响应丢失时展示待确认状态，先刷新当前配置与任务进度。

用量页按 UTC 月份和项目请求明细，返回最多 100 个执行/模型/版本分组，调用类型筛选作用于本次返回记录。每组分别展示尝试、成功、失败、待完成、结果未知和未发送次数，以及已报告的 input/output tokens、用量完整性与累计耗时。NULL 展示为“未报告”，明确报告的零展示为 0，Embedding 输出 token 展示为“不适用”。团队管理员可查询团队数字明细，项目成员按项目权限查询项目用量。

项目资料搜索支持关键词、语义和混合三种策略，并展示实际采用的策略与降级原因。首次团队连接的 Redis 配额域由部署者按前述步骤初始化。


## 项目概览与实时依赖图

概览先展示最多 50 个任务、150 条依赖，小项目在预算内直接展示完整节点范围。按进行中、待办和待分诊选择重点工作，再补充直接上下游；全项目状态计数与真实关系数量始终可见。成员可以搜索项目内其他任务、定位其周边，并按每次最多 20 个直接邻居逐层展开。当前画布上限为 200 个任务、1000 条边，节点与关系的省略分别标记。阻塞依据全部直接上游计算，包含图外任务；已取消的上游仍属于未完成。

| 接口（前缀 `/projects/{p}`） | 用途 |
|---|---|
| `GET /graph?view=overview` | 首屏局部图；可指定 nodeLimit、edgeLimit |
| `GET /graph?view=focus&focusTaskId={id}` | 固定包含所选任务并加载一层关系 |
| `POST /graph/view` | 以 nodeIds 重取范围，可带 expand 的 taskId、direction、cursor、limit |
| `POST /graph/sync` | 以 afterSeq、nodeIds、selectedTaskId 合并当前范围的变化 |
| `GET /graph/state` | 轻量版本与保留水位；授权后处理 If-None-Match |
| `GET /graph/events?after={seq}&connectionId={uuid}` | SSE 版本提示、恢复提示与心跳 |
| `DELETE /graph/events?connectionId={uuid}` | 当前登录身份与会话主动释放自己的订阅，保留 CSRF 校验 |
| `GET /tasks/{t}/preview?commentLimit=5` | 任务摘要、当前负责人及最近五条本地讨论 |
| `GET /task-search?projection=overview&q=...&limit=20` | 全项目标题定位的轻量候选 |

无 view 参数的 `/graph` 保留原有 200 节点、1000 边的兼容接口。旧 task-search 的筛选与详情返回契约保持。所有新读取每次重新检查用户、团队与项目权限，图范围读取使用 REPEATABLE READ；只读 POST 继续要求 CSRF。请求体上限 32KiB，图响应上限 1MiB，预览上限 128KiB。

### 事务记录与读模型

[V27](../src/main/resources/db/migration/V27__project_overview_realtime.sql) 追加 `project_overview_state` 和 `project_view_change`，回填状态计数及依赖总数，并增加种子查询索引。已有迁移保留。MySQL 事务触发器统一接入任务、依赖、评论、文档、资料关联、项目设置及项目成员的实际变化，覆盖人工、GitHub、CSV、负责人交接和后台写入。日志只保存对象引用与字段失效信息，任务和评论正文留在业务表。

迁移账号需要目标库的建表、索引、存储过程和触发器权限。启用 binary log 的 MySQL 还限制普通账号创建触发器；仓库的独立 Compose MySQL 设置 `log_bin_trust_function_creators=ON`。使用外部数据库时，由数据库管理员按部署权限策略执行迁移，或为受控迁移开启该设置，避免启动时出现错误 1419；设置作用于整个数据库实例。[MySQL 存储程序与二进制日志说明](https://dev.mysql.com/doc/refman/8.0/en/stored-programs-logging.html)。

业务服务继续持有原有的项目锁、权限与乐观锁。状态行分配项目连续序号，回滚同时撤销业务数据、计数和日志；相同值更新与幂等重放保持序号不变。高扇出状态变化记录单条失效信息，sync 只重算当前范围内受影响的下游。上游完成通知采用批量 SQL 保留每项任务的活动与收件人，避免逐项读取下游正文。

三个版本分别承担职责：既有 businessRevision/task.revision 处理写入冲突；topologyRevision 绑定邻接游标；viewSeq 恢复项目画面。新序号与拓扑版本以十进制字符串传输，前端用 BigInt 比较。评论沿用 discussionRevision。展开游标绑定项目、任务、方向和拓扑版本，拓扑过期返回 GRAPH_CURSOR_STALE，页面保留范围并从该方向第一页补齐。

每分钟维护任务轮转最多 20 个项目，按 24 小时或每项目最近 100000 条清理日志，删除与保留水位推进同事务完成。维护同时检测状态计数与真实聚合的漂移，持项目锁复核后修复，并记录告警和修复日志。`workhub.overview.maintenance-ms` 可调整维护周期。该任务独立于消息队列开关。

### 协作、恢复与连接

每个 JVM 每 500ms 批量核对活跃项目版本，同项目观看者共享检查；graph/state 复用最多 1 秒的实例内版本提示缓存，每次仍重新鉴权，请求 Cache-Control: no-cache 可立即核对，snapshot 与 sync 继续读取一致数据库快照；身份、项目和会话组合单独批量鉴权。SSE 帧只含版本，大小最多 1KiB；15 秒心跳，实例最多 200 条订阅，同一用户在该实例最多 3 条。每条非阻塞输出只保留最新通知，5 秒持续不可写便关闭。容量满返回 503 和 Retry-After。页面离开时主动释放连接，断连还由输出错误和心跳回收。

前端分别保存 seenSeq 与 appliedSeq，每页同一时刻一个 sync，通知合并 200ms，最多每 500ms 发起一次范围同步。读取失败保留 appliedSeq；预览 dirty 直到成功读取对应版本才清除。SSE 不可用时每 5 秒校对版本，正常连接每 30 秒校对；断网后按 1、2、4、最多 5 秒加少量随机偏移重连。隐藏 30 秒关闭订阅，回到前台立即校对。

日志过期、积压超过 1000 条或游标异常返回 GRAPH_RESYNC_REQUIRED，前端重取当前 nodeIds。范围请求和预览都有独立代数校验，晚到响应被丢弃。撤权、账号失效或退出后，订阅关闭，当前项目图、预览和临时缓存清理。

节点、边、邻接和 DOM 均有索引。状态、负责人及讨论更新保持节点元素、坐标、选中项、缩放与画布滚动；拓扑变化和展开再布局，并尽量保留旧节点位置。正在阅读讨论时保留当前内容，显示“有新讨论”，由成员主动更新。展示名直接从数据库读取，预览带回的当前负责人资料同步到已显示的任务卡片。预览在 SQL 中切片描述和评论，完整任务与历史讨论继续通过原文入口阅读。

字段增量保持已有边集合，服务端只在拓扑变化时查询并返回 `edgesReplace`。此时字段增量节点的 `visibleUpstreamCount`、`visibleDownstreamCount` 为 null，表示沿用当前画布的可见关系；快照和边替换时提供相应数值。前端始终根据实际绘制边的邻接计算省略数量，避免密集图的首屏150边预算与更大范围预算混用。真实出入度和阻塞仍由数据库计算，包含图外上游。

多实例部署共享 MySQL 即可传播变化。反向代理须为 `/graph/events` 关闭缓冲与缓存，保持长连接空闲时间大于 15 秒，浏览器继续使用同源会话。安装 V27 及回填期间暂停写入，迁移完成后启动新应用；保留触发器的旧写入者也进入同一事务日志。恢复旧应用版本时保留追加迁移、触发器与业务历史。
