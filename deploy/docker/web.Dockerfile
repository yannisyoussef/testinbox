# syntax=docker/dockerfile:1.7
#
# The minimal inspection UI (ADR-011). It is a Node server, not a static
# bundle: it terminates the same-origin `/api/backend` proxy that keeps the
# API key out of the browser's URL bar and out of a cross-origin request.
#
#   docker build -f deploy/docker/web.Dockerfile .

ARG NODE_VERSION=22

# --- dependencies ------------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS deps
WORKDIR /src
# `npm ci` from the committed lockfile: deterministic resolution, and it fails
# rather than drifting if the lockfile and manifest disagree.
COPY web/package.json web/package-lock.json ./
RUN npm ci

# --- build -------------------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /src
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /src/node_modules ./node_modules
COPY web ./
RUN npm run build

# --- runtime -----------------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS runtime
ARG GIT_SHA=unknown
ARG BUILD_VERSION=0.1.0

LABEL org.opencontainers.image.source="https://github.com/yannisyoussef/testinbox" \
      org.opencontainers.image.title="testinbox-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.licenses="UNLICENSED"

WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# `standalone` carries only the modules the traced build actually imports —
# no devDependencies, no Next CLI, no source tree.
COPY --from=build --chown=root:root /src/.next/standalone ./
COPY --from=build --chown=root:root /src/.next/static ./.next/static

# The base image bundles the npm CLI (and its own node_modules, which carry
# their own CVEs — tar, brace-expansion, ...). The standalone server never
# runs npm, and ADR-028 §5 says a runtime image contains no build tooling, so
# it goes: the promotion scan blocked a release on findings in a tool the
# container cannot even invoke. Corepack/yarn aliases go with it.
RUN rm -rf /usr/local/lib/node_modules /usr/local/bin/npm /usr/local/bin/npx \
           /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg /opt/yarn-v*

# node:alpine ships an unprivileged `node` user (uid 1000).
USER node

EXPOSE 3000
# Exec form so node is PID 1 and sees SIGTERM.
ENTRYPOINT ["node", "server.js"]
