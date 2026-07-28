# ─────────────────────────────────────────────────────────────
# SecureChat 服务端镜像
# 多阶段构建：builder 阶段用完整依赖编译 TypeScript，
# runtime 阶段只装生产依赖，镜像更小、更干净。
# ─────────────────────────────────────────────────────────────

# ---- Build stage ----
FROM node:20-alpine AS builder

WORKDIR /app

# 先装依赖（利用层缓存；无 lock 文件时 npm install 也能正常工作）
COPY package.json ./
RUN npm install

# 再拷源码并编译
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ---- Runtime stage ----
FROM node:20-alpine

# dumb-init 负责正确回收僵尸进程、转发信号（Ctrl+C / docker stop 能干净退出）
RUN apk add --no-cache dumb-init

WORKDIR /app

# 安装生产依赖（不含 typescript 等 devDependencies）
COPY package.json ./
RUN npm install --omit=dev

# 拷贝编译产物与静态资源（管理后台 UI 在 public/admin）
COPY --from=builder /app/dist ./dist
COPY public ./public

# 预创建运行时目录并以非 root 用户归属，避免挂载卷后无写入权限
RUN mkdir -p /app/data /app/public/apk \
  && chown -R node:node /app/data /app/public/apk

ENV NODE_ENV=production
ENV PORT=8080
ENV HOST=0.0.0.0

EXPOSE 8080

# 健康检查：探测 /health 端点
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/health || exit 1

USER node

ENTRYPOINT ["dumb-init", "--"]
CMD ["node", "dist/server.js"]
