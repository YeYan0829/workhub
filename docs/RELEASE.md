# 预览版下载与安装

版本：`v0.1.0-preview.1`。镜像平台：`linux/amd64`。许可：MIT。

[版本与下载页面](https://github.com/YeYan0829/workhub/releases/tag/v0.1.0-preview.1)提供已构建的应用镜像与配套源码。安装使用 Docker Compose v2 和 Python 3，应用直接从镜像运行。

## 下载哪些文件

| 附件 | 用途 |
|---|---|
| `workhub-v0.1.0-preview.1-source.tar.gz` | 同版本 Compose、配置、使用指南及完整源码 |
| `workhub-v0.1.0-preview.1-linux-amd64.docker.tar.gz` | 已构建的应用镜像 |
| `release-manifest.json` | 源码 commit、镜像 ID、平台、版本与附件校验信息 |
| `SHA256SUMS` | 下载文件的 SHA-256 校验清单 |
| `installation-verification.json`（选读） | 独立运行器从下载、安装到容器重建的数据保留验收 |

在下载目录中放置这些文件，Linux/WSL 执行：

```bash
sha256sum --check SHA256SUMS
tar -xzf workhub-v0.1.0-preview.1-source.tar.gz
docker load -i workhub-v0.1.0-preview.1-linux-amd64.docker.tar.gz
cd workhub-v0.1.0-preview.1
python3 scripts/init-env.py --image workhub:v0.1.0-preview.1
docker compose --profile app up --no-build -d --wait --wait-timeout 300
```

`init-env.py --image` 创建本地随机密码，并保存选定的镜像标签；已有 `.env` 时只调整镜像选择，原数据库与消息服务密码保留。`--no-build` 使用已加载的应用镜像。

应用镜像对应的平台可通过以下命令查看：

```bash
docker image inspect workhub:v0.1.0-preview.1 --format '{{.Os}}/{{.Architecture}} {{.Id}}'
```

应用镜像的版本和源码 commit 也写入 OCI 标签，可与 `release-manifest.json` 核对。当前附件提供 Linux x86-64 镜像；其他架构使用[源码构建路径](GETTING_STARTED.md#从源码构建)准备自己的镜像。

## 启动后体验什么

打开应用 <http://127.0.0.1:18080> 注册，打开测试邮箱 <http://127.0.0.1:18025> 完成验证。按[首次体验指南](GETTING_STARTED.md)建立报表项目，体验任务依赖、阻塞提示、讨论文档和实时协作。

默认环境启动应用、MySQL、RabbitMQ 和 Mailpit。首次运行仍需取得三个配套服务镜像；它们的引用已固定在 Compose 中。完整离线运行时，额外准备这些依赖镜像。

模型与 GitHub 按需配置。任务协作可先独立体验，再按指南接入团队模型与检索。

## 停止与更新

```bash
docker compose --profile app down
```

该命令保留命名数据卷。更新版本前备份数据库、邮件密钥卷及已配置的模型加密主密钥；下载新版本的源码包与镜像，按照新版本说明选择镜像并启动。

这个预览版本的实测成果与后续验证见[性能与可靠性报告](VALIDATION.md)。
