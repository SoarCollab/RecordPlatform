# Platform Administrator Bootstrap

The platform identity foundation is disabled by default. Complete the `V1.21.0` Flyway migration and verify MySQL and Redis availability before enabling it.

## One-time procedure

1. Stop all but one backend replica.
2. Create a local password file outside the repository. Restrict it to the backend operator and write one password of at least 16 characters containing lower-case, upper-case, digit, and symbol characters.
3. Configure `PLATFORM_BOOTSTRAP_USERNAME`, `PLATFORM_BOOTSTRAP_EMAIL`, and `PLATFORM_BOOTSTRAP_PASSWORD_FILE` with no password value in environment or Nacos.
4. Set `PLATFORM_BOOTSTRAP_ENABLED=true` and start the backend once. Startup fails if configuration is incomplete, the file is unavailable, or any current or soft-deleted platform administrator row already exists.
5. Confirm the sanitized completion message. Stop the backend, set `PLATFORM_BOOTSTRAP_ENABLED=false`, and move the password file to the operating system trash.
6. Set `PLATFORM_IDENTITY_ENABLED=true`, restart the normal replica set, and require a new login. Tokens issued before this release do not contain `scope` and `authVersion` and are intentionally rejected.

Platform requests always send `X-Tenant-ID: 0`. A target business tenant must never be supplied through that identity header. The system tenant `0` may still contain legacy `user`, `admin`, or `monitor` accounts; only the `platform_admin` role with `scope=platform` has platform authority.

## Rotation and recovery

Password or account-state changes increment `auth_version`, invalidate authorization cache entries, delete outstanding SSE short tokens, and close current SSE connections. The one-time bootstrap does not bypass an existing historical platform-administrator row; lost-access recovery therefore requires an explicitly reviewed operator database procedure. There is no public recovery endpoint.

Do not commit bootstrap identity values, password files, password hashes, or generated tokens. Treat source deployment as incomplete until a real login, fixed tenant header, cross-route denial, Redis failure, and disable/restore drill have been verified on the target host.
