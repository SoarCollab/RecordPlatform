# Publishing public container images

Release containers must be anonymously pullable before a GitHub Release is created. Making a GHCR package public exposes **all its versions** and cannot be reversed to private. Review historical contents first; never publish deployment credentials, certificates, `.env` files, or private configuration.

GitHub creates new container packages as private by default, even when the source repository is public. Package visibility is a GitHub setting, not a Docker build label or push option. An administrator must perform the one-time conversion in the package's **Package settings → Danger Zone → Change visibility → Public**. Do not add a personal access token or broaden organization permissions to automate this operation. These platform rules are documented by [GitHub: package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility) and [GitHub: Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

## Pulling a release

The five package coordinates are:

| Component | Image |
| --- | --- |
| Backend | `ghcr.io/soarcollab/recordplatform-backend` |
| FISCO | `ghcr.io/soarcollab/recordplatform-fisco` |
| Storage | `ghcr.io/soarcollab/recordplatform-storage` |
| Frontend | `ghcr.io/soarcollab/recordplatform-frontend` |
| Public verifier | `ghcr.io/soarcollab/recordplatform-verifier-web` |

No registry login is required after the package is public:

```bash
docker pull ghcr.io/soarcollab/recordplatform-backend:0.0.3
```

For reproducible deployment, pin the reviewed manifest digest instead of a mutable alias. The current build is a single-platform Linux image on the Ubuntu runner, not a multi-platform publication. Pulling an image does not deploy or start the service.

## Publishing subsequent versions

`.github/workflows/release.yml` remains the sole build/package/push pipeline. A lowercase semantic-version Git tag such as `v0.0.3` produces image aliases `0.0.3`, `0.0`, `sha-0c2cad2`, and `latest` for that stable release. Later releases update the applicable mutable aliases; prerelease metadata follows the pinned Docker metadata action's existing rules.

1. The read-only build job compiles the existing modules and saves five image archives plus their generated `*.tags` files. It has no registry write token.
2. The publish job loads those exact archives and pushes every generated alias using the existing `GITHUB_TOKEN` permissions.
3. `tools/ci/verify_public_images.py` snapshots all local pushed image IDs and repository manifest digests **before any alias is pulled**. It then performs an anonymous Docker pull of every alias, compares the pull's reported manifest digest, and rechecks the local image identity.
4. Only successful verification allows GitHub Release creation. Missing/empty tag files or archives, wrong package names, denied/missing images, ambiguous identities, digest drift, and exhausted retries fail closed.

Verification runs with a fresh temporary `HOME` and explicit `DOCKER_CONFIG`, with only a blank `ghcr.io` auth entry to suppress credential-helper discovery. The child environment contains only `PATH`, `HOME`, `DOCKER_CONFIG`, `LANG`, and `LC_ALL`: no inherited GitHub token, Docker auth configuration, custom authorization headers, or Docker context. It uses the local CI daemon, never a caller-selected remote daemon. Each pull has a 180-second timeout and at most three attempts, five seconds apart; the workflow step has a 30-minute upper bound. Docker may reuse layers already loaded from the archive, so this publication check proves anonymous pull authorization and identity, not a cold download of every layer.

When adding a component, use matching archive and tag-file stems and a `recordplatform-<stem>` package name (the existing `verifier` stem maps to `recordplatform-verifier-web`). Additional tag files are automatically checked, and the existing five remain mandatory. Update the required-component contract, focused tests, and operator documentation when changing the supported service set.

## Recovering a failed public-release check

For a newly created private package, review and explicitly change the whole package to Public through GitHub's supported UI. After visibility propagation, rerun **only the failed publish job**, using the same workflow run and original `release-bundle` artifact. The artifact currently expires after one day. Do not rerun the build merely to repair visibility: dependency/base-image resolution could produce different bytes.

If the artifact has expired, stop and recover the exact previously pushed artifacts with their reviewed digests before deciding how to resume. Do not move an existing Git tag, overwrite historical image bytes, delete package versions, weaken the verification, or create a Release manually to bypass it. A digest mismatch also requires checking whether another release changed a shared alias such as `latest`; do not automatically repush an older version over a newer release.

## Running independent anonymous pull acceptance

The **Verify Public Images** workflow is manual (`workflow_dispatch`) with read-only repository access, no registry login, and no build/push/release steps. It uses the reviewed `tools/ci/fixtures/v0.0.3-public-images.json` snapshot: first it anonymously pulls the five immutable manifests on a fresh runner, then it invokes the same verification helper to check all twenty historical aliases against those identities.

```bash
gh workflow run verify-public-images.yml --ref main
```

Run it only after the workflow has reached the default branch and package conversion is complete. This is a **v0.0.3 historical acceptance snapshot**, not a permanent claim that `latest` or `0.0` still points there. It will intentionally fail after a newer release moves those aliases; future manual acceptance must use a separately reviewed snapshot. Normal future publication derives its inputs from that run's actual image archives and tag files and does not use this fixture. The manual workflow is not a normal PR dependency and does not deploy to any server.

For local regression checks without Docker or registry access:

```bash
python3 -m unittest discover -s tools/ci/tests -p 'test_public_images.py' -v
actionlint .github/workflows/release.yml .github/workflows/verify-public-images.yml
```

These deterministic tests are not live Docker acceptance. Likewise, successful manifest-only HTTP requests do not prove a Docker pull or full layer download; keep those evidence levels separate.
