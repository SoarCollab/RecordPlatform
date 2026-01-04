# Frontend Development Guide

This guide covers development setup for the RecordPlatform frontend application.

## Technology Stack

| Category | Technology | Version |
|----------|------------|---------|
| Framework | Svelte 5 + SvelteKit 2 | 5.46+ / 2.49+ |
| Language | TypeScript | 5.9+ |
| Styling | Tailwind CSS | 4.1+ |
| Build Tool | Vite | 6.0+ |
| Package Manager | pnpm | 10.26+ |
| UI Components | Bits UI + Lucide Icons | - |

## Getting Started

### Prerequisites

- Node.js 18+
- pnpm 10+

### Development Setup

```bash
cd platform-frontend

# Install dependencies
pnpm install

# Start development server
pnpm dev
```

The development server runs at `http://localhost:5173`.

### Available Scripts

| Command | Description |
|---------|-------------|
| `pnpm dev` | Start development server |
| `pnpm build` | Build for production |
| `pnpm preview` | Preview production build |
| `pnpm check` | TypeScript type checking |
| `pnpm lint` | Run ESLint |
| `pnpm format` | Format code with Prettier |
| `pnpm types:gen` | Generate API types from OpenAPI |

## Environment Variables

Create `.env` file in `platform-frontend/`:

| Variable | Description | Example |
|----------|-------------|---------|
| `PUBLIC_API_BASE_URL` | Backend API URL | `http://localhost:8000/record-platform` |
| `PUBLIC_ENV` | Environment name | `development` |
| `PUBLIC_TENANT_ID` | Default tenant ID | `1` |

## Project Structure

```
platform-frontend/
├── src/
│   ├── routes/              # SvelteKit pages
│   │   ├── (app)/           # Authenticated routes
│   │   │   ├── dashboard/   # Dashboard page
│   │   │   ├── files/       # File management
│   │   │   └── admin/       # Admin pages
│   │   ├── (auth)/          # Authentication routes
│   │   │   ├── login/
│   │   │   └── register/
│   │   └── share/           # Public share pages
│   ├── lib/
│   │   ├── api/             # API client
│   │   │   ├── client.ts    # HTTP client wrapper
│   │   │   ├── endpoints/   # API endpoint functions
│   │   │   └── types/       # TypeScript types
│   │   ├── components/      # Reusable components
│   │   │   └── ui/          # Base UI components
│   │   ├── stores/          # Svelte 5 runes stores
│   │   └── utils/           # Utility functions
│   ├── app.css              # Global styles
│   ├── app.html             # HTML template
│   └── app.d.ts             # Global type declarations
├── static/                  # Static assets
└── svelte.config.js         # SvelteKit config
```

## Key Stores

The application uses Svelte 5 runes-based stores:

| Store | File | Purpose |
|-------|------|---------|
| Auth | `auth.svelte.ts` | User authentication state, JWT management |
| SSE | `sse.svelte.ts` | Server-Sent Events connection |
| SSE Leader | `sse-leader.svelte.ts` | Multi-tab leader election |
| Upload | `upload.svelte.ts` | File upload queue with chunking |
| Download | `download.svelte.ts` | File download manager |
| Notifications | `notifications.svelte.ts` | Toast notifications |
| Badges | `badges.svelte.ts` | UI badge counts |

### Store Usage Example

```svelte
<script>
  import { auth } from '$lib/stores/auth.svelte';

  // Reactive access with Svelte 5 runes
  const user = $derived(auth.user);
  const isAuthenticated = $derived(auth.isAuthenticated);
</script>

{#if isAuthenticated}
  <p>Welcome, {user?.username}</p>
{/if}
```

## API Client

### Type Generation

Generate TypeScript types from backend OpenAPI spec:

```bash
# Ensure backend is running at localhost:8000
pnpm types:gen
```

This generates `src/lib/api/types/generated.ts` with full API types.

### Making API Calls

```typescript
import { filesApi } from '$lib/api/endpoints/files';

// List user files
const files = await filesApi.list({ page: 1, size: 20 });

// Upload file
const result = await filesApi.upload(file, {
  onProgress: (progress) => console.log(`${progress}%`)
});
```

## Chunked File Upload

The upload system uses dynamic chunk sizing:

| File Size | Chunk Size |
|-----------|------------|
| < 10MB    | 2MB        |
| < 100MB   | 5MB        |
| < 500MB   | 10MB       |
| < 2GB     | 20MB       |
| >= 2GB    | 50MB       |

Upload process:
1. Calculate optimal chunk size
2. Start upload session (`/file/upload/start`)
3. Upload chunks with progress tracking
4. Merge chunks (`/file/upload/merge`)

## Build for Production

```bash
# Build static site
pnpm build

# Preview build locally
pnpm preview
```

The build output is in `build/` directory, ready for static hosting.

## Code Style

- Use TypeScript strict mode
- Follow existing component patterns
- Use Tailwind CSS for styling
- Keep components small and focused
- Use stores for shared state

### Component Template

```svelte
<script lang="ts">
  import { type ComponentProps } from 'svelte';

  interface Props {
    title: string;
    variant?: 'default' | 'primary';
  }

  let { title, variant = 'default' }: Props = $props();
</script>

<div class="component {variant}">
  <h2>{title}</h2>
  {@render children?.()}
</div>
```
