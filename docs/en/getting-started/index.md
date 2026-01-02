# Getting Started

This guide will help you set up and run RecordPlatform locally.

## Contents

- [Prerequisites](prerequisites.md) - Required services and dependencies
- [Installation](installation) - Build and run the platform
- [Configuration](configuration) - Environment variables and settings

## Quick Overview

RecordPlatform consists of four main components:

| Component | Type | Port | Description |
|-----------|------|------|-------------|
| platform-storage | Dubbo Provider | 8092 | Distributed storage service |
| platform-fisco | Dubbo Provider | 8091 | Blockchain integration service |
| platform-backend | Dubbo Consumer | 8000 | REST API gateway |
| platform-frontend | SvelteKit | 5173 | Web application |

## Startup Order

Services must be started in the following order due to Dubbo RPC dependencies:

```
1. platform-storage  →  2. platform-fisco  →  3. platform-backend  →  4. platform-frontend
```

## Next Steps

1. Check [Prerequisites](prerequisites.md) to ensure all dependencies are ready
2. Follow [Installation](installation) to build and run
3. Review [Configuration](configuration) for environment setup

