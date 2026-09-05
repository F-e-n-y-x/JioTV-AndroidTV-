# ── Web stage: build the React/Vite SPA → web/dist ──
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web/ ./
RUN npm run build

# ── Server build stage: compile TS + native deps (better-sqlite3) ──
FROM node:22-alpine AS build
WORKDIR /app
RUN apk add --no-cache python3 make g++
COPY package.json package-lock.json* ./
RUN npm install
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
EXPOSE 8080
VOLUME ["/app/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:8080/api/status || exit 1
CMD ["node", "dist/server.js"]
