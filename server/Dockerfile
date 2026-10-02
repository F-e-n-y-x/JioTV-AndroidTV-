# ── Web stage: build the React/Vite SPA → web/dist ──
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── Server build stage: compile TS + native deps (better-sqlite3) ──
FROM node:22-alpine AS build
WORKDIR /app
RUN apk add --no-cache python3 make g++
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ── Runtime stage ──
FROM node:22-alpine AS runtime
WORKDIR /app
RUN apk add --no-cache libstdc++
ENV NODE_ENV=production
ENV DATA_DIR=/app/data
COPY package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=web /web/dist ./web/dist
# Run as the unprivileged `node` user (uid/gid 1000, part of the base image). The data dir is created
# and owned by it here, so a NEW named volume inherits that ownership. A volume created by an older
# (root) image needs a one-time chown — see README "Upgrading: data volume permissions".
RUN mkdir -p /app/data && chown node:node /app/data
USER node
EXPOSE 8080
VOLUME ["/app/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:8080/api/status || exit 1
# Exec form: node is the main process and receives SIGTERM directly (graceful shutdown in server.ts).
# docker-compose adds `init: true` so a tiny init reaps zombies and forwards signals.
CMD ["node", "dist/server.js"]
