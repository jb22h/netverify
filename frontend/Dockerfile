# NetVerify dashboard — Vite build + nginx static serve /api proxy.

# -----------------------------------------------------------------------------
# Stage 1: production frontend build
# -----------------------------------------------------------------------------
FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 2: nginx runtime (no Node)
# -----------------------------------------------------------------------------
FROM nginx:1.28-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
