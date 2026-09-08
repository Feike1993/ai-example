# pnpm@11.4.0 要求 Node >=22.13（依赖 node:sqlite）；Node 20 会在 install 期直接崩溃
FROM node:22.13-alpine AS builder

ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm install --global pnpm@11.4.0 --registry="${NPM_REGISTRY}"
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/.npmrc ./
RUN pnpm config set registry "${NPM_REGISTRY}" \
    && pnpm install --frozen-lockfile

COPY frontend/ ./
RUN pnpm build

FROM nginx:1.27-alpine

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/frontend/dist /usr/share/nginx/html

EXPOSE 80
