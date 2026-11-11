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
| `conversation` | `docker compose -f compose.conversation-test.yaml up -d --wait`；使用硬编码的 `23316` 与独立测试凭据 |
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

检查器同时检查本地 Markdown 文件链接和 Compose 文件挂载依赖。CI 对每次 push 与 pull request 检查 Git 索引；本地 `--staged` 也读取索引中的原始内容，未暂存的修改不能掩盖待提交的问题。通过公开范围检查后，CI 还会从源码构建应用镜像；Java 集成测试仍需按环境分组执行。

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
