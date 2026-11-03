# WorkHub

WorkHub 面向研发团队，集中管理任务、依赖、项目资料和 GitHub Issue。项目助手可以只读查询资料，并为待分诊任务生成带引用的建议；维护者编辑、预览并明确批准后，业务服务才会接纳任务。

这是从开发仓库整理出的独立源码基线。后续 Docker 应用镜像、CI 与 UI 改进在本仓库继续开发。导出来源与切换规则见 [SOURCE.md](SOURCE.md)。

## 本地运行

需要 Bash、Python 3、Docker Compose 和 Java 17。已有 JDK 时设置 `JAVA_HOME` 或将 `java` 放到 PATH；Linux x86_64 也可运行 `python3 scripts/bootstrap-jdk.py`，脚本按 [jdk-lock.json](scripts/jdk-lock.json) 下载并校验到 `.tools/jdk`。首次构建需要联网下载 Maven 和依赖。

```bash
python3 scripts/init-env.py
docker compose up -d --wait
scripts/run.sh -DskipTests package
scripts/run-app.sh
```

1. 打开 <http://127.0.0.1:18080>，注册账号，密码至少 12 字符。
2. 打开本地邮件收件箱 <http://127.0.0.1:18025>，通过验证链接启用账号。
3. 登录后创建团队、项目和任务。

`.env.example` 默认选择 `async` profile，启动 MySQL、RabbitMQ 和 Mailpit，并启用后台消息处理以发送验证邮件。所有发布端口和应用监听地址均为本机地址。生成的 `.env`、邮件加密密钥和运行产物不进入版本控制；HTTPS 部署需启用安全 Cookie。`init-env.py` 不覆盖已有 `.env`，已有配置需自行补齐新变量。

应用在宿主机运行，Compose 目前只启动中间件。停止中间件使用 `docker compose down`，命名卷会保留。`.tools/mail.key` 必须与数据库一起保留，丢失后已有的加密待发邮件无法解密。

## 可选能力

| 能力 | 前置条件 |
|---|---|
| BM25 检索 | 将 `COMPOSE_PROFILES` 改为 `async,retrieval`，再次启动 Compose，设置 `WORKHUB_RETRIEVAL_ENABLED=true` 并重启应用 |
| Dense / Hybrid | 在检索基础上配置独立 Embedding 端点、密钥、模型维度和预算，显式开启 Embedding |
| 项目问答 / 分诊建议 | 配置 `WORKHUB_AGENT_*` 与 `WORKHUB_GLM_*`，明确费用授权和预算，再开启助手 |
| 共享模型准入 | 增加 `model-governance` profile，配置独立配额 scope、速率和 burst，使用 `scripts/model-admission.py` 显式初始化专用 Redis |
| GitHub 同步 | 部署者配置 GitHub App 并通过 `scripts/admin.sh` 预授权 installation/repository，显式开启 `WORKHUB_GITHUB_ENABLED`；远端写回有独立开关 |

基础协作不需要模型密钥。开启项目自动分诊不等于获得模型费用授权。助手只读，分诊批准只接纳 WorkHub 本地任务；聊天创建任务尚未实现。配置的实际名称与默认值见 [application.yml](src/main/resources/application.yml) 和 [.env.example](.env.example)。

## 测试

单元测试无需中间件：

```bash
scripts/run.sh test
```

核心集成回归使用 MySQL 和 RabbitMQ，覆盖空库迁移、V6 升级、任务权限、消息发布、幂等执行与进程退出恢复：

```bash
docker compose up -d --wait
scripts/init-test-env.sh
scripts/test-core.sh
```

初始化脚本只创建测试库和 vhost；测试会清理自己的夹具。请使用本地专用测试环境，勿将数据库配置指向共享或生产服务。

分诊的浏览器验收由 Java 测试创建合成账号和随机端口的应用实例，再调用 Playwright；需要 Node.js 20 或以上：

```bash
npm ci
npm run browser:install
# Linux 若缺少 Chromium 系统库，可按 Playwright 的提示安装系统依赖。
scripts/run.sh -Pcontracts -Dworkhub.test.browser-timeout-seconds=240 \
  -Dit.test=TriageProposalIT,AutomaticTriageIT verify
```

完整 `-Pcontracts verify` 还包括检索、专用服务故障和会话测试，不能只启动默认中间件就宣称全量通过。各组前置条件见 [测试说明](docs/TESTING.md)。真实 GitHub / 模型冒烟通过 `external-contracts` 单独执行，需要额外凭据与显式开关；无凭据或未授权时会跳过，不计为通过。测试报告与浏览器截图位于 `target/`。

## 架构与文档

Java 17、Spring Boot、Spring JDBC/Security/Session/AMQP、Flyway，单体应用；具体版本由 [pom.xml](pom.xml) 和 [compose.yaml](compose.yaml) 固定。

| 组件 | 职责 |
|---|---|
| MySQL | 业务事实、权限、后台工作、Outbox、会话与模型费用账本 |
| RabbitMQ | 后台工作运输，发布确认、手动 ACK |
| Elasticsearch | 可重建的正文和向量检索投影 |
| 专用 Redis | 共享模型速率、冷却和熔断状态 |
| Mailpit | 本地 SMTP 与验证邮件收件箱 |

- [架构与主要取舍](docs/ARCHITECTURE.md)
- [产品规则与权限](docs/PRODUCT_AND_BUSINESS.md)
- [代码地图](docs/CODE_MAP.md)
- [工程概念](docs/ENGINEERING_CONCEPTS.md)
- [测试分组与环境](docs/TESTING.md)
- [本次基线验证范围](docs/BASELINE_VALIDATION.md)

前端仍为静态页面，UI 产品化、应用镜像和 CI 尚待完成。仓库保留工程测试，不包含历史评测实验与原始 evidence；工程测试通过不代表真实模型回答质量。
