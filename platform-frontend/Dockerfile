# Build context: platform-frontend/
# Usage: docker build -f platform-frontend/Dockerfile -t recordplatform-frontend platform-frontend/

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM node:20-alpine AS build

# Enable Corepack so pnpm is resolved from package.json packageManager.
RUN corepack enable

WORKDIR /app

# Install dependencies first (layer caching: only re-runs on lockfile change)
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack pnpm install --frozen-lockfile

# Copy source and build SPA
COPY . .
RUN corepack pnpm build
# Output: dist/ with precompressed .gz/.br assets (precompress: true in svelte.config.js)

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM nginx:1.27-alpine AS runtime

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
