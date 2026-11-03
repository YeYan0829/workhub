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
| `phase4` / `phase4b` / `phase5a` | MySQL、RabbitMQ、Elasticsearch；使用脚本模型/Embedding 替身，不需要付费模型；`RetrievalIT` 的历史数据集实验默认跳过 |
| `phase5b` / `progress` | 部分方法启动 Node.js 和 Chromium，需要 `npm ci` 与 `npm run browser:install`；普通业务方法使用合成模型 |
| `governance/SharedBudgetIT` | 已初始化的 `workhub_phase5a_test`；读取 `.env` 中 MySQL 端口与凭据 |
| `governance/ModelAdmissionIT` | 下方专用 Redis，包含重启与状态丢失测试 |
| `conversation` | `docker compose -f compose.conversation-test.yaml up -d --wait`；使用硬编码的 `23316` 与独立测试凭据 |
| `external/*Smoke` | `-Pexternal-contracts verify`，独立凭据与开关；未配置时跳过 |

Linux 上 Chromium 缺少系统库时，`npx playwright install-deps chromium` 可安装依赖，可能需要系统管理员权限。浏览器脚本使用标准 Playwright 安装位置，也支持 `PLAYWRIGHT_BROWSERS_PATH`；不再依赖开发仓库的 `.tools/browser`。

`TriageProposalIT` 的浏览器子进程看门狗默认 90 秒，可用 `-Dworkhub.test.browser-timeout-seconds=240` 扩展整个页面流程的等待。它与模型运行期限及业务断言分开；自动分诊测试本身有 240 秒进程预算。

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
