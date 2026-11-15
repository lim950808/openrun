# syntax=docker/dockerfile:1
# openrun web (ADR-0008 §4), Next.js standalone. Build context: apps/web.
#   docker build --build-arg API_BASE_URL=http://api:8080 -t openrun-web apps/web
# Rewrites (/api/* → API_BASE_URL) are resolved at build time, so API_BASE_URL is a build arg.
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
ARG API_BASE_URL=http://api:8080
ENV API_BASE_URL=${API_BASE_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:24-alpine AS runner
WORKDIR /app
ARG API_BASE_URL=http://api:8080
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    API_BASE_URL=${API_BASE_URL} \
    HOSTNAME=0.0.0.0 \
    PORT=3000
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
CMD ["node", "server.js"]
