# SecureChat

端到端加密即时通讯 — Android 原生客户端 + Node.js 服务端。

> 面向授权团队内部的高保密安全通信工具。支持完全私有化部署，服务端不持有任何明文。

## 特性

- **端到端加密**：AES-256-GCM 信封加密 + RSA-2048（Android Keystore TEE 硬件保护）+ ECDH 前向安全
- **自有推送通道**：WebSocket 前台长连接 + AlarmManager 轮询兜底，不依赖 FCM / Google 服务
- **可私有化部署**：登录 / 注册界面即可手动填写服务端地址（域名或 IP:端口），无需改包
- **富媒体消息**：文本 / 图片 / 文件 / 视频；图片内联预览 + 长按下载，文件发送带进度条
- **语音 / 视频通话**：P2P WebRTC，信令复用现有 WebSocket 通道
- **管理后台（Web Admin Console）**：用户管理、消息保留策略、OTA 版本管理
- **离线优先**：本地 Room 存储 + 气泡级懒解密，弱网 / 无网可读历史

## 技术栈

| 层 | 技术 |
| --- | --- |
| 客户端 | Kotlin · Jetpack Compose · Hilt · Room · Retrofit / OkHttp（minSdk 26） |
| 服务端 | Node.js · Express · ws（TypeScript 单文件 `server.ts`） |
| OTA 分发 | 自托管 APK + `update.json`，破坏缓存的 `/ota/check` `/ota/dl` 路由 |

## 目录结构

```
app/        Android 客户端源码（Kotlin + Compose）
server/     Node.js 服务端（src/server.ts 为权威源码，public/admin 为管理后台 UI；含 Dockerfile / docker-compose.yml 容器化部署）
```

## 快速开始

### 服务端

```bash
cd server
npm install
cp .env.example .env        # 填入 JWT_SECRET / ADMIN_INITIAL_PASSWORD 等
./node_modules/.bin/tsc     # 编译 TypeScript
node dist/server.js         # 启动（建议用看门狗 / systemd 守护）
```

- 配置项见 [`server/.env.example`](server/.env.example)
- 管理后台： `http://<server>:8080/admin`（账号 `admin`，**首次登录强制改密**）
- 健康探测： `GET /health` 返回 200

#### 使用 Docker 部署（推荐一键）

服务端内置 `Dockerfile` 与 `docker-compose.yml`，多阶段构建、仅含生产依赖、内置健康检查：

```bash
cd server
cp .env.example .env        # 编辑 JWT_SECRET / ADMIN_INITIAL_PASSWORD / SERVER_DOMAIN
docker compose up -d        # 构建镜像并在 8080 端口启动
```

- 数据持久化（命名卷，自动规避主机权限问题）：
  - `securechat-data` → `/app/data`（用户 / 消息 / 配置 / 崩溃日志）
  - `securechat-apk`  → `/app/public/apk`（OTA 安装包，如需自动更新）
- 默认监听 `0.0.0.0:8080`，可用 `PORT` 环境变量或 compose 的 `ports` 映射调整
- 查看日志： `docker compose logs -f securechat-server`；停止： `docker compose down`（数据卷保留）

> **⚠️ 网络受限环境（如部分国内网络无法访问 Docker Hub）**：
> 构建时若卡在 `pulling node:20-alpine` 并报 `connection refused / registry-1.docker.io`，
> 需为 Docker 守护进程配置一个可达的 registry 镜像源。例如：
> ```bash
> sudo mkdir -p /etc/docker
> sudo tee /etc/docker/daemon.json <<'EOF'
> { "registry-mirrors": ["https://docker.m.daocloud.io"] }
> EOF
> sudo systemctl restart docker
> ```
> 重启后再执行 `docker compose up -d` 即可。npm 源一般不受影响（registry.npmjs.org 通常可达）。
- **OTA 自动更新（可选）**：让容器内服务直接对外分发 APK（`/ota/dl`、`/ota/check`）时，改用 bind 挂载 `./apk` 与 `./update.json`，详见 `server/docker-compose.yml` 末尾注释

### Android 客户端

```bash
# 需要 Android SDK + Gradle 8.x + JDK 17
./gradlew assembleDebug
```

- 首次打开 App，在登录界面填写你的服务端地址（如 `your-domain.example.com:9999`）
- 代码内默认示例地址为 `securechat.example.com`，正式打包发布前请改为你自己的地址
- 或通过 App 内「设置 → 服务端连接」随时切换

## 安全说明

- 聊天内容端到端加密，服务端仅持久化**密文**与元数据，无法读取明文
- 默认管理员密码、JWT 签名密钥等敏感配置**均已改为环境变量**，请勿硬编码进源码
- 自托管时请务必为 `JWT_SECRET` 设置足够长的随机值，并通过反向代理启用 HTTPS
- 密钥（RSA / ECDH）生成与存储依赖 Android Keystore 硬件隔离，不在客户端源码中落地

## 免责声明

本项目仅供授权团队内部安全通信使用。部署者需自行承担合规、运维与安全责任。
