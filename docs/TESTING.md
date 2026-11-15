# 测试分组与环境

`scripts/run.sh test` 使用 Surefire 运行 `*Test`，不需要中间件；`-Pcontracts verify` 通过 Failsafe 运行 `*IT`。测试编译成功只说明依赖完整，不证明集成行为正确。记录结果时分别列出通过、失败、跳过与未执行。

## 核心回归

先按根 README 生成 `.env`，启动默认 MySQL / RabbitMQ / Mailpit，运行 `scripts/init-test-env.sh`。随后运行 `scripts/test-core.sh`，它选择以下测试，不要求浏览器或故障专用容器：

- `SchemaIT`：正式迁移与数据库约束。
- `MigrationAndLimitsIT`：V6 升级、身份与作者保留、空库迁移、重复迁移和 CSV 边界。
- `TaskPermissionsIT`：任务生命周期及成员/维护者权限。
- `PublisherIT` / `ExecutionIT`：消息发布、重复投递与持久执行。
- `CrashRecoveryIT`：子进程退出后恢复。

核心入口对六种崩溃窗口各跑一轮；直接运行完整 `CrashRecoveryIT` 默认各跑三轮。`workhub.test.crash-repetitions` 可选择轮数，`workhub.test.child-timeout-seconds` 控制包含整个 Spring 启动的子进程看门狗，核心入口为 180 秒、测试默认 45 秒。它不改变业务执行的 deadline、故障注入点、预期退出码或恢复后的断言。资源紧张时可通过核心入口末尾的 Maven 参数覆盖启动预算。

`ExecutionIT` 还通过两个真实 AMQP channel、`basicQos(1)` 和独立 consumer tag 同时接收相同 messageId，验证两条消费者回调执行后只有一次持久业务效果。突发发布对照需显式启用，并使用相同的真实测试 broker：

```bash
WORKHUB_BENCH_OUTBOX=true scripts/run.sh -Pcontracts \
  -Dit.test=OutboxBatchBenchmarkIT verify
```

该夹具每轮创建 32 条消息，比较每次调度最多 1 条和最多 20 条，各运行三轮；保留发布身份、确认结果、broker 实收数量和时间，输出 `target/outbox-benchmark/<run-id>/results.json`。耗时表示这批消息发布并收到确认的时间；消费完成时间需由执行器测试和实际业务另行采集。

初始化脚本创建 phase1 / phase2 / phase3 / phase3x / phase4 / phase4b / phase5a 隔离库和相关 vhost。部分测试执行清库、删除队列或杀死自己的 JVM 子进程；只应使用专用的本地测试环境。

## 其他测试组

| 测试组 | 额外要求 |
|---|---|
| `contract` | MySQL、RabbitMQ、Elasticsearch 和普通 Redis；使用 `--profile full` 启动中间件 |
| `phase1` 的并发诊断 | 除 MySQL 外，需要 Docker CLI/socket 读取当前 Compose MySQL 的锁信息 |
| `phase2/BrokerRestartIT` | 专用故障 RabbitMQ；见下方，必须与普通 phase2 测试分开执行 |
| `phase3` / `phase3x` | 对应隔离库和 vhost；GitHub 用本地 HTTP 替身，账号邮件使用测试设施 |
| `phase4` / `phase4b` / `phase5a` | MySQL、RabbitMQ、Elasticsearch；使用脚本模型/Embedding 替身，不需要付费模型 |
| `phase5b` / `progress` | 部分方法启动 Node.js 和 Chromium，需要 `npm ci` 与 `npm run browser:install`；普通业务方法使用合成模型 |
| `governance/SharedBudgetIT` | 已初始化的 `workhub_phase5a_test`；读取 `.env` 中 MySQL 端口与凭据 |
| `governance/ModelAdmissionIT` | 下方专用 Redis，包含重启与状态丢失测试 |
| `conversation` | `docker compose -f compose.conversation-test.yaml up -d --wait`；默认使用 `23316` 与独立测试凭据；`CONVERSATION_JDBC_URL` / `CONVERSATION_DB_USER` / `CONVERSATION_DB_PASSWORD` 可选择其他隔离实例（库名仍须为 `workhub_conversation_test`） |
| `external/*Smoke` | `-Pexternal-contracts verify`，独立凭据与开关；未配置时跳过 |

Linux 上 Chromium 缺少系统库时，`npx playwright install-deps chromium` 可安装依赖，可能需要系统管理员权限。浏览器脚本使用标准 Playwright 安装位置，也支持 `PLAYWRIGHT_BROWSERS_PATH`；不再依赖开发仓库的 `.tools/browser`。

`TriageProposalIT` 的浏览器子进程看门狗默认 90 秒，可用 `-Dworkhub.test.browser-timeout-seconds=240` 扩展整个页面流程的等待。它与模型运行期限及业务断言分开；自动分诊测试本身有 240 秒进程预算。

`GitHubTriageIT` 的事务回滚测试会在隔离库创建临时触发器。测试 MySQL 开启 binary log 时，先检查 `@@GLOBAL.log_bin_trust_function_creators`；使用测试实例管理员临时设为 `1`，执行该组后恢复原值。应用账号不需要获得 `SUPER` 权限。这个配置仅用于专用故障测试实例。

### RabbitMQ 故障测试

```bash
docker compose -f compose.test.yaml up -d --wait
# 建立故障 broker 专属 vhost，不能复用日常 broker 的 vhost。
docker compose -f compose.test.yaml exec -T rabbitmq sh -ec '
  rabbitmqctl list_vhosts -q name | grep -qx phase2_test || rabbitmqctl add_vhost phase2_test
  rabbitmqctl set_permissions -p phase2_test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"
'
(
  set -a; source .env; set +a
  WORKHUB_ENV_FILE=/dev/null RABBITMQ_PORT=15673 scripts/run.sh \
    -Pcontracts -Dit.test=BrokerRestartIT verify
)
```

`WORKHUB_ENV_FILE` 可选择加载的配置文件；默认 `.env`。这里先加载本地凭据，再让脚本跳过再次读取 `.env`，使故障 broker 的端口覆盖生效。该测试会停止并重新启动专用 broker 的应用。

### 模型准入故障测试

```bash
docker compose -f compose.governance-test.yaml up -d --wait
W2_REDIS_CONTAINER=workhub-w2-redis MODEL_REDIS_PORT=26379 scripts/run.sh \
  -Pcontracts -Dit.test=ModelAdmissionIT verify
```

测试会重启固定名称的 `workhub-w2-redis`，不使用应用的普通 Redis 或模型准入 Redis。Redis 不保存持久卷，重启应丢失配额状态并要求显式恢复。

## 验证边界

合成模型、GitHub HTTP 替身和真实中间件验证业务合同与恢复行为，不代表真实模型语义质量或 GitHub 的全部服务端行为。历史外部评测素材没有导入本仓库；包含凭据的 `.env`、密钥和报告不得提交。构建/测试报告写到 `target/`。


## 公开范围与提交检查

```bash
# 检查工作区中的已跟踪文件与未忽略的新文件
python3 scripts/check-public-tree.py
# git add 后检查暂存区的实际文件及内容
python3 scripts/check-public-tree.py --staged
# 检查器自身的回归，不需要中间件
python3 scripts/test-public-tree.py
```

允许范围是应用与正式测试源码、必要运行脚本、构建配置、README、本文、架构说明和选定的产品截图。新增脚本、文档或构建入口时更新检查器允许清单。工作记录、原始报告、缓存和本地配置不进入公开提交；被忽略但已经跟踪的文件仍会被检查。

检查器同时检查本地 Markdown 文件链接和 Compose 文件挂载依赖。CI 对每次 push 与 pull request 检查 Git 索引；本地 `--staged` 也读取索引中的原始内容，未暂存的修改不能掩盖待提交的问题。通过公开范围检查后，CI 运行 Java 17 单元测试，再从源码构建应用镜像；Java 集成测试仍需按环境分组执行。

分支采用 `main` 加短期功能分支。合入前检查公开范围、运行受影响的测试并完成必要的浏览器验收；展示版本使用 tag。CI 工作流不会自动配置远端分支保护，需要在托管平台另行启用必需检查。


## Docker 启动与数据保留

使用独立 Compose 项目和新的数据卷，关闭模型与外部 GitHub 调用。首次从源码构建并启动：

```bash
python3 scripts/init-env.py
docker compose -p workhub-docker-test --profile app up --build -d --wait --wait-timeout 300
npm ci
npm run browser:install
WORKHUB_DOCKER_TEST=true node scripts/verify-docker-ui.cjs seed
```

浏览器会实际注册独立账号，从本地 Mailpit 获取验证邮件，完成验证、登录、团队/项目/任务/评论创建。仅在专用测试环境使用该脚本；默认 URL 对应 `.env.example`，其他端口通过 `APP_URL`、`MAILPIT_URL` 指定。结果与浏览器会话写入 `target/docker-test`，用新的 `DOCKER_TEST_DIR` 开始另一轮。

检查应用容器重建后的数据库、会话和邮件密钥保留：

```bash
docker compose -p workhub-docker-test --profile app stop mailpit
WORKHUB_DOCKER_TEST=true node scripts/verify-docker-ui.cjs queue
docker compose -p workhub-docker-test --profile app stop app
docker compose -p workhub-docker-test --profile app up -d --wait --wait-timeout 300 --force-recreate app
WORKHUB_DOCKER_TEST=true node scripts/verify-docker-ui.cjs check
```

`queue` 在 SMTP 暂停时注册另一测试账号，留下使用旧密钥加密的待发邮件；重建应用会启动其 Mailpit 依赖。`check` 使用保存的数据库会话读取原任务与评论，再接收并验证旧待发邮件，检查持久数据、恢复投递和密钥复用。也可在保留卷的情况下先 `down` 再 `up`，重新运行 `check`。测试完成后只停止该专用项目，不操作其他开发服务。

镜像构建不执行 Java 测试；单元和集成测试按上述分组单独运行。容器启动及浏览器流程通过不代表真实模型语义质量，文档与产品展示效果另行验收。

## 内容交互回归

`ContentInteractionIT` 使用 phase3x 专用测试库，验证最新评论与旧接口排序、时间戳相同时的游标、历史评论定位与跨任务隔离、完整关键词文档搜索、关联标题及解除后的资料保留。助手状态接口同时检查项目授权和最小返回字段。

```bash
scripts/init-phase3x-test-env.sh
scripts/run.sh -Pcontracts -Dit.test=dev.workhub.phase3x.ContentInteractionIT verify
```

正式浏览器回归覆盖创建任务回执丢失后的刷新确认、长评论与草稿、历史引用及编辑、101 篇文档的分页和关联、文档和网页的解除关系，以及筛选任务后导航保持选中。先在专用本地 Compose 环境生成合成账号，再运行内容交互检查：

```bash
npm ci
npm run browser:install
WORKHUB_DOCKER_TEST=true node scripts/verify-docker-ui.cjs seed
WORKHUB_FRONTEND_TEST=true node scripts/verify-frontend-ui.cjs
```

默认应用地址为 `http://127.0.0.1:18080`，邮件地址为 `http://127.0.0.1:18025`。可用 `APP_URL`、`MAILPIT_URL` 和 `DOCKER_TEST_DIR` 指定专用环境；前端回归通过 `FRONTEND_SEED_DIR` 读取种子输出，`FRONTEND_TEST_DIR` 指定报告目录，默认分别为 `target/docker-test` 和 `target/frontend-test`。脚本只接受本机 HTTP 地址并要求显式测试开关，创建的账号、任务和资料均为合成数据。

种子输出包含测试账号和会话，只保留在忽略目录。已有输出时使用新的种子目录；失败日志和截图另存，不覆盖此前结果。浏览器工程回归使用可控场景，真实模型语义、真实 GitHub 读取和写回分别验收。

## 团队 BYOK 与用量验收

正式入口只依赖本仓库源码、Java 17、MySQL、Redis、Elasticsearch 和本地假供应商，不读取生成模型真实凭据。独立 Compose 项目使用 28306 / 28379 / 29200，不复用演示环境：

```bash
docker compose -f compose.team-model-test.yaml up -d --wait
scripts/init-team-model-test.sh
scripts/test-team-model.sh
python3 scripts/check-public-tree.py
python3 scripts/test-public-tree.py
```

`init-team-model-test.sh` 为专用 Compose MySQL 建立升级、模型设置、概览与性能夹具数据库并授权合成账号；模型治理应用测试库为 `workhub_team_model_test`。升级测试会清空 upgrade 库，先从 V22 建立旧 run 和 RESERVED/UNKNOWN 调用，再在 V23 加入旧团队金额/周期及未决调用后升级 V24，核对金额、快照与外键不变，随后重建空库并重复迁移。不要把测试地址指向业务数据库。默认连接使用 `team_model_fixture` 和专用 Compose 中的公开合成密码，不加载 `.env`。

已有隔离 MySQL/Redis 时可设置 `TEAM_MODEL_JDBC_URL`、`TEAM_MODEL_DB_USER`、`TEAM_MODEL_DB_PASSWORD`、`TEAM_MODEL_REDIS_PORT`、`TEAM_MODEL_ES_URL`。JDBC URL 必须选择 `workhub_team_model_test`；升级测试将库名替换为 `workhub_team_model_upgrade_test`，因此同一测试账号须能访问这两个专用库。数据库会保留合成数据；结束后仅停止该测试项目，清理数据时使用它自己的 `down -v`。

| 测试 | 证明的合同 |
|---|---|
| `TeamModelGovernanceIT` | ADMIN/成员/跨团队权限、AES-GCM 与脱敏、版本冲突、无平台 key 回退、项目策略、双项目归属、并发单次执行次数上限、幂等记录、UNKNOWN/NOT_SENT、缺失/部分/零 usage、跨月归属、无预算或旧零预算也可调用、跨供应商无币种依赖、轮换等待、权限撤销、独立团队、Redis 丢失/断开/显式恢复、重试与读超时 |
| `TeamModelRuntimeIT` | 正式 Spring Session/CSRF API、持久任务执行器中的手动问答、连续会话、摘要和自动分诊；真实子进程在工具检查点后退出，再轮换或撤权，恢复时没有新增 HTTP |
| `TeamModelMigrationIT` | V22→V23→当前版本 保留旧部署/团队金额账本、旧 Embedding 金额记录及外键，全新数据库与重复迁移 |
| `TeamModelMultiJvmIT` | 两个真实 JVM、一个团队、两个项目、12 个 run、每个 3 次调用，6:6 分配；同一生产登记/准入/HTTP/用量链路共享 4 次/秒、突发 2；实际发出 HTTP 后杀死 JVM，未知调用仍保留 |

双 JVM 假供应商自身为 4 次/秒、桶容量 4，固定成功响应延迟 100 ms。测试检查 36 次成功响应、最多每 run 2 次额外重试、12 个 run 的用量归属，并按许可时间验证同一 4 次/秒、突发 2 的令牌桶包络。许可与网络到达之间存在调度延迟，允许供应商返回 429，但所有实际重试都必须有记录，并保存服务端到达时间、许可时间、等待、状态、错误和整批耗时。整批耗时从同时释放两个 worker 到最后任务结束，包含准入和本机组件开销；它不是平均模型延迟，也不代表性能改进或回答质量。没有与静态方案做性能比较。

原始输出位于忽略目录 `target/team-model-governance/<日期>/`，每次运行使用新子目录；检查点子进程日志与双 JVM 汇总均保留。其他集成和单元结果位于 Maven 报告目录。未知结果不自动重发，缺失用量不补零；团队测试不进行任何金额估算或预留。`workhub.team-model.fixture-endpoint` 是测试进程专用的服务端 loopback 配置，不是团队 API 字段。

共享配额的对照夹具可单独运行。它启动两个真实 JVM，分别使用独立逻辑桶或同一个桶，请求同一个有固定限流的 HTTP 供应商夹具，各重复三轮。供应商速率 2.5 次/秒、突发 2，客户端配额 2 次/秒、突发 1；输出实际 HTTP 到达、429、许可和配额区间核验结果：

```bash
WORKHUB_BENCH_MODEL_ADMISSION=true scripts/run.sh -Pcontracts \
  -Dit.test=ModelAdmissionComparisonIT verify
```

输出在 `target/model-admission-comparison/<run-id>/`。独立逻辑桶用于复现每实例单独计数的行为；这些对照数字描述固定夹具下的限流效果。`ModelAdmissionIT` 另验真实 Redis 重启、共享冷却、半开竞争、状态丢失和过期许可，不以对照夹具替代恢复测试。

旧部署模型夹具显式使用 `workhub.agent.legacy-mode=true`，继续验证旧账本合同。既有治理、会话和分诊测试仍可按各自隔离库执行；团队测试不要求 RabbitMQ，运行的是正式持久任务执行器，实际 broker 运输由现有 TransportIT 测试组覆盖。旧 DenseRetrievalIT 显式设置 `workhub.embedding.legacy-mode=true`，仅用于旧部署兼容；新的团队路径默认不创建旧 DenseConfiguration/CloudEmbeddings/EmbeddingBudget bean。


`TeamEmbeddingIT` 使用真实 MySQL/Redis/Elasticsearch 和可控 HTTP 向量，验证团队配置/CSRF、加密/脱敏、跨团队空间拒绝、密钥轮换不换空间、输入规则/维度变更、新库及旧账本保留、独立或复用生成凭据、用量合并与缺失计数、等待中撤权/轮换、Redis 丢失及团队冷却隔离。索引验收覆盖首次启用、并行空间、新增/修改/删除追平、原子切换、陈旧响应不入库、真实严格 mapping 部分失败、ES 响应丢失、真实物理索引删除后复用产物重建，以及禁用后的 canonical BM25 降级。另有两个真实 JVM 的共享许可包络和实际 HTTP 发出后杀 JVM 的未知结果恢复验证。自动分析项目授权在启用者移除后仍按项目范围工作，不冒用其个人身份。

Embedding 原始证据保存在 `target/team-model-governance/embedding/`，包括 worker 日志、输入、双进程结果与崩溃结果；生成专项仍使用日期子目录。假模型固定返回合法向量，验收工程隔离与恢复，不评价真实模型语义质量，也不调用付费服务。正式团队入口通过持久 Outbox/WorkJobExecutor 执行业务链路；RabbitMQ 实际运输和旧部署 DenseRetrievalIT 所需的 broker 仍按各自独立环境另验。


## 团队模型设置浏览器验收

准备 Java 17、Node.js 20+、Chromium，以及团队专项的 MySQL、Redis、Elasticsearch：

```bash
npm ci
npm run browser:install
docker compose -f compose.team-model-test.yaml up -d --wait
scripts/init-team-model-test.sh
scripts/test-model-settings-ui.sh
```

入口使用独立 `workhub_model_ui_test` 库，在随机 loopback 端口启动真实 Spring Boot 应用。Java 夹具建立管理员、项目维护者、普通成员与另一个团队，Playwright 通过实际登录、Session/CSRF 和生产配置接口操作页面。模型目录、配置权限、密钥保留/轮换、版本冲突、模型继承修复、Embedding 凭据复用、向量重建/切换/切回、检索与问答、用量缺失、禁用原因、移动端和键盘操作均在同一次浏览器运行中验证。

测试在 loopback 假供应商返回确定性回答与合法向量，使用真实 MySQL/Redis/Elasticsearch，隔离执行器处理对应项目的正式 Outbox/WorkJobExecutor。首次配额初始化由测试夹具执行部署步骤。浏览器记录、截图和逐项结果保存到 `target/model-settings-ui/<随机标识>/`；该入口验收页面与工程链路。供应商语义质量按实际模型场景另验。

可通过 `TEAM_MODEL_JDBC_URL`、`TEAM_MODEL_DB_USER`、`TEAM_MODEL_DB_PASSWORD`、`TEAM_MODEL_REDIS_PORT`、`TEAM_MODEL_ES_URL` 指向另一套专用测试组件。测试使用的 loopback 模型地址仅由服务端测试配置注入。工作树及暂存区公开范围检查包含正式浏览器入口，过程证据留在忽略目录。


## 项目概览依赖图

浏览器检查使用真实应用和 MySQL，账号及场景写入专用数据库 `workhub_overview_ui_test`。准备 Java 17、Node.js 20+ 和 Playwright Chromium 后，启动测试 MySQL 并初始化数据库：

```bash
docker compose -f compose.team-model-test.yaml up -d --wait mysql
scripts/init-team-model-test.sh
npm ci
npm run browser:install
scripts/test-project-overview-ui.sh
```

通过 `OVERVIEW_JDBC_URL`、`OVERVIEW_DB_USER`、`OVERVIEW_DB_PASSWORD` 指定测试数据库连接。夹具校验数据库名称后创建独立项目，保留已有测试数据。

`ProjectOverviewUIIT` 覆盖节点与依赖数量上限、图外关系、完成任务的依赖风险、归档读取，以及完整浏览器操作。浏览器检查分支汇合与独立任务、类型颜色、任务详情与最近讨论、引用转义、返回路径、键盘操作、缩放定位、上游完成后的状态变化、失败重试、成员权限和 390px 布局。结果与截图位于 `target/project-overview-ui/<run-id>/`。


## 局部图、恢复同步与性能复现

初始化脚本还会建立 `workhub_overview_realtime_test` 和 `workhub_overview_benchmark_test`。新增回归使用前者，逐次创建独立项目，保留已有数据：

V27 安装事务触发器。测试 Compose 已配置 `--log-bin-trust-function-creators=ON`；外部测试 MySQL 启用 binary log 时，需由管理员配置同等迁移权限条件，参见架构文档。首次迁移出现 1419 时先保留失败日志，修复权限并检查 Flyway 和已提交的 DDL，再恢复迁移。已有数据的库应按备份与迁移修复流程处理。

```bash
scripts/test-project-overview-realtime.sh
```

使用 `OVERVIEW_REALTIME_JDBC_URL` 指定连接，数据库名称须为 `workhub_overview_realtime_test`。`ProjectOverviewRealtimeIT` 覆盖事务回滚、幂等与同值更新、全状态计数、图外上游、范围/游标/交叉边、重放过期、大正文与 Unicode、密集关系、节点移除、计数修复和 10000 下游完成通知。`ProjectGraphStreamsTest` 在 Servlet 输出边界验证单帧合并、慢输出关闭、用户与实例配额和连接回收。原有 `ProjectOverviewUIIT` 继续运行完整浏览器旅程。

八类性能数据由显式夹具准备。创建后，在同一隔离库运行应用，再启动采样：

```bash
WORKHUB_SEED_OVERVIEW_BENCHMARK=true scripts/run.sh -Pcontracts \
  -Dit.test=ProjectOverviewBenchmarkIT verify
# 将应用连接到 workhub_overview_benchmark_test 后启动；默认预览地址为 18080。
OVERVIEW_BENCH_SEED=target/overview-benchmark/seed.json \
OVERVIEW_BENCH_VERSION=B1 OVERVIEW_BENCH_WRITE=true \
OVERVIEW_BENCH_OUTPUT=target/overview-performance \
node scripts/benchmark-project-overview.cjs
```

夹具只接受专用 benchmark 库，新增账号、项目与 S0–S7 数据，输出登录信息和项目 ID 到忽略的 target 目录；重复准备会追加新数据。可用 `OVERVIEW_BENCH_JDBC_URL` 指定 JDBC，用 `OVERVIEW_BENCH_BASE` 指定 loopback 应用地址。采样默认各 40 次、额外预热 5 次，保存逐次 JSONL、响应与分位数。B0 选择兼容接口；B1 选择局部图、preview 和轻量标题候选。`OVERVIEW_BENCH_BROWSER=true` 追加桌面与 390px 的首屏和键盘选中测量，使用 50ms 网络时延、10/5Mbps 带宽，窄屏 CPU 四倍节流。`OVERVIEW_BENCH_BROWSER_SAMPLES` 调整浏览器样本数。

双成员与跨 JVM 旅程使用同一夹具。两个应用连接同一个隔离库，分别指定写入者和观察者地址：

```bash
OVERVIEW_BENCH_SEED=target/overview-benchmark/seed.json \
OVERVIEW_BENCH_BASE=http://127.0.0.1:18080 \
OVERVIEW_SECOND_BASE=http://127.0.0.1:18081 \
OVERVIEW_REALTIME_OUTPUT=target/overview-collaboration \
node scripts/verify-project-overview-realtime.cjs
```

脚本使用真实浏览器、正式任务与讨论接口，以及网络中断和读取失败注入，输出结果、桌面/窄屏图片和录屏。性能顺序采样与并发负载需分别报告；固定应用堆、数据库缓冲、硬件、日志级别和数据种子后，再计算 B0/B1 比例。原始失败、环境中断、样本数、预热与持续时长都应保留。

同一组双实例和种子还可运行 `node scripts/verify-overview-instance-switch.cjs`，沿用上述四个环境变量。脚本建立临时loopback流式代理，真实切断实例B的SSE并将重连送到实例A；普通范围读取继续经过B。三次重复检查切换后的实际状态绘制、游标单调、选择、缩放和布局保持，结果与截图写入 `OVERVIEW_REALTIME_OUTPUT`。

正常读写负载下的故障延迟可使用 `verify-overview-under-load.cjs`。将两个应用连接到独立、可恢复的夹具副本，保留原种子中的账号与项目ID，再运行：

```bash
OVERVIEW_BENCH_SEED=target/overview-benchmark/seed.json \
OVERVIEW_LOAD_ORIGINS=http://127.0.0.1:18080,http://127.0.0.1:18081 \
OVERVIEW_REALTIME_OUTPUT=target/overview-fault-load \
node scripts/verify-overview-under-load.cjs
```

该脚本启动99个真实协议订阅、10项目、每秒5次计划写入的后台负载，预热120秒、采样600秒；另一个真实Chromium成员观察相同项目，正常合计100订阅。在采样窗口内重复20次正式成员撤权/恢复、20次真实连接配额503后的轮询补齐、5次浏览器断网20秒后恢复，保存每个样本、分位数、阈值超出次数、截图与录屏。轮询阶段以同一用户的3条占位连接触发配额，瞬时总连接数为102；断网阶段在线99。延迟从正式修改成功响应或恢复在线开始，到DOM满足条件并经过两个动画帧。后台每笔确认写入的观察覆盖、最终任务事实及计数继续独立核对；测量使用的任务与后台串行写入任务通过 `workload.json` 明确分开。成员操作遇到业务版本冲突时读取新版本重试并保留次数。脚本会实际修改指定项目的成员权限及任务状态，宜单独串行运行；完成后恢复成员和任务状态。

浏览器性能观察使用固定CDP网络条件：时延50ms、下载10Mbps、上传5Mbps；故障脚本通过同一个CDP会话切换offline，同时保留这些网络参数，并检查浏览器实际离线状态。HTTP/SSE协议发生器保留未限速loopback条件。各自的网络参数写入结果，浏览器绘制和协议应用分别判读。

首屏与键盘响应的目标负载验收使用独立的夹具副本，设置与上述负载故障相同的 `OVERVIEW_BENCH_SEED`、`OVERVIEW_LOAD_ORIGINS`、`OVERVIEW_REALTIME_OUTPUT` 后执行 `node scripts/verify-overview-entry-under-load.cjs`。每轮保持99个协议观看者、10项目、每秒5次计划写入，另一位真实浏览器用户反复进入S1–S3，打开后合计100订阅。预热120秒、后台采样600秒；每个数据集和视口额外预热2次，再各测15次入页及键盘选中，按6秒间隔交错取样，全部位于后台采样窗口内。390px使用CPU四倍节流，网络条件保持50ms/10/5Mbps。重复三轮时分别恢复同一冻结数据并重启应用；原始浏览器数值、后台覆盖与最终事实、截图和超标项一并保存。顺序B0/B1的入页对照用于比较变化，目标负载的首屏及选中阈值由此入口另行判定。

顺序采样将每数据集首个请求单列为 `*_first_hit_ms`，再预热五次并保留正式样本。首个请求可能受操作系统和数据库缓存影响，报告应同时记录服务重启与数据恢复条件。每个 API 样本携带 `requestId`；需要判断服务端处理时间时，可在隔离环境启用 Tomcat AccessLog，记录同一个 `X-Request-Id` 和 `%{us}T` 微秒计时，再与客户端单调时钟的往返时间分别汇总。API阈值使用客户端往返口径，服务端计时用于解释调度、SQL与传输开销。


并发负载可在准备夹具时增加 `WORKHUB_OVERVIEW_LOAD_FIXTURE=true`，额外创建 70 位合成读者与 50 个项目，原有数据继续保留。指定订阅数、项目数和写入速率运行：

```bash
OVERVIEW_BENCH_SEED=target/overview-benchmark/seed.json \
OVERVIEW_BENCH_VERSION=B1 OVERVIEW_LOAD_ORIGINS=http://127.0.0.1:18080,http://127.0.0.1:18081 \
OVERVIEW_LOAD_SUBSCRIBERS=100 OVERVIEW_LOAD_PROJECTS=10 OVERVIEW_LOAD_WRITES=5 \
OVERVIEW_LOAD_WARMUP_SECONDS=120 OVERVIEW_LOAD_SECONDS=600 \
OVERVIEW_LOAD_BROWSER=true OVERVIEW_BENCH_OUTPUT=target/overview-load \
node scripts/load-project-overview.cjs
```

负载脚本按实际登录身份建立有界订阅，复用当前范围同步协议，记录 API、字节量、实际吞吐、调度滞后、客户端补齐及 Chromium 绘制延迟；结束后关闭自己的全部订阅。B0 使用兼容读取及相同正式写接口，实时指标从 B1 开始测量。每个条件使用独立输出目录；将预热和采样时长分别设为 120 与 600，重复三次得到标准采样。200 订阅的 30 分钟稳定性运行设 `OVERVIEW_LOAD_SECONDS=1800`，同时采集应用与数据库资源及连接指标。`ProjectGraphStreams` 每 30 秒以 INFO 记录活跃连接、共享版本检查、鉴权、输出帧、慢连接回收和最大调度滞后。


内存较少的本机可使用 `-Dworkhub.test.jvmArgs="-Xmx192m -XX:ActiveProcessorCount=2"` 调整测试 JVM；默认仍为 384MiB。多 JVM 用例及 ES/Chromium 组合应串行执行，并记录实际堆配置、交换压力和原断言的复验结果。

4 vCPU / 8GiB 的专用测试机采用串行阶段：先构建与准备数据，再运行领域/模型回归，结束 Elasticsearch 和测试 JVM 后启动概览性能阶段。B0、B1 依次运行；应用总堆固定 512MiB，双 JVM 各 256MiB，MySQL buffer pool 固定 512MiB。200 个订阅由 HTTP/SSE 客户端建立，真实 Chromium 观察者按需开启；桌面和窄屏依次测量。负载驱动可用 `NODE_OPTIONS=--max-old-space-size=256` 启动，完整样本流式落盘，内存仅保留分位数所需数值，同时每 10 秒记录发生器 RSS、CPU 和事件循环延迟。此参数约束 Node 堆，Chromium 及堆外 RSS 仍需单独监测。同机负载发生器与服务端共享 CPU 和磁盘，应在环境报告中说明，并保留实际完成吞吐、调度延迟、系统交换和 CPU steal；资源不足时保留中断结果，先排除发生器瓶颈再复测。

负载报告的 `actualWritesPerSecond` 使用采样窗口内收到成功响应的写入数除以实际窗口时长，设置的速率另列。排队到窗口结束仍未执行的写入保留计数；API 延迟按窗口内开始的请求统计，观察者延迟按窗口内确认的写入统计，并允许在补齐阶段收集最后的结果。通知合并后，观察者到达对应或更新的序号即记录该写入的追平延迟，`observationCoverage` 核对每笔确认写入与每位对应观看者的覆盖数，避免只统计未被合并的末次更新。Chromium 指标包含绘制，HTTP/SSE 客户端指标表示协议应用，两者分别报告。

写入场景为每项目一个可见任务，按revision串行完成取CSRF、PATCH与新版本确认；不同项目并行。该发生器会保留计划到达与实际完成的差距，热点单项目吞吐同时受这条有序工作流约束。评估全系统最大TPS时，应另设不同任务并发写入场景并区分业务冲突，结合本组读写混合负载报告。

客户端每 30 秒通过 ETag 核对版本；`OVERVIEW_LOAD_RECONCILE_MS` 可单独调整测量器校对周期，正式比较应保持默认值。结束时以正式 graph/view 重读同一范围，核对节点事实、状态计数和最终游标，再关闭订阅。`phase.json` 标记预热、采样、补齐和完成，便于对齐数据库统计；原始 SSE 帧字节量单列。使用 `OVERVIEW_LOAD_WRITES=0 OVERVIEW_LOAD_PROBE=false OVERVIEW_LOAD_BROWSER=false` 可运行空闲订阅对照，比较同项目 1 与 100 位观看者的共享版本检查次数，并将每位观看者的鉴权工作另列。

负载登录默认采用 `OVERVIEW_LOAD_LOGIN_IP_MODE=distinct`：在 Linux loopback 中用每用户固定的 `127.88.x.x` 来源地址完成正式登录，再使用取得的真实会话，保留生产登录限流。一个身份最多建立三个订阅；200订阅使用约67位读者并替换少量连接为真实Chromium页面，前三个活跃项目分别由独立浏览器上下文观察。`single` 模式用于验证同一IP集中登录的限制。每轮恢复隔离种子或使用新夹具，保留登录失败记录。传输EOF、连接错误、重连与范围恢复分别记录；恢复后的覆盖和最终事实仍须一致。

原生 MySQL 上执行 `ConcurrencyIT` 时，可提供 `WORKHUB_TEST_DIAGNOSTICS_JDBC_URL`、`WORKHUB_TEST_DIAGNOSTICS_USER` 和 `WORKHUB_TEST_DIAGNOSTICS_PASSWORD`，连接具有只读 `performance_schema` 权限的独立观察账号。测试读取真实等待/阻塞连接、表名、索引与锁状态，保持原来的锁等待和最终结果断言。未提供这些变量时沿用 Compose 管理员诊断入口。
