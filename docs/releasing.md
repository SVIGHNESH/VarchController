# Releasing

Releases are automated.
You merge pull requests, and the pipeline works out the version, writes the changelog, and publishes the builds.

## What a release contains

| Artifact | Where it goes |
| --- | --- |
| `varch-controller-X.Y.Z.apk` | GitHub release, signed with the release key. |
| `varchd-X.Y.Z-linux-amd64.tar.gz` and `-arm64` | GitHub release, static binary plus the systemd unit. |
| `SHA256SUMS` | GitHub release, with a build provenance attestation for every file. |
| `varchd` package | The AUR, built from the tagged source, once AUR publishing is turned on. |

## Day to day

1. Open a pull request with a [Conventional Commits](https://www.conventionalcommits.org) title, such as `feat: add wake-on-LAN` or `fix: reconnect after sleep`.
   The `PR title` check rejects anything else.
2. CI builds and tests the daemon, the app and the AUR package.
3. Squash-merge it.
   The title becomes the commit on `main`.

`fix:` raises the patch version and `feat:` raises the minor version.
Other types, such as `docs:`, `ci:`, `build:`, `refactor:` and `test:`, do not cause a release.

## Cutting a release

release-please keeps a pull request named `chore(main): release X.Y.Z` open whenever `main` holds unreleased `feat` or `fix` commits.
It bumps the version in every file that carries it and updates `CHANGELOG.md`.

Merge that pull request to release.
The `Release` workflow then tags the commit, creates the GitHub release, runs CI on the tag, builds and signs the APK, builds the daemon tarballs, attaches everything, and pushes the PKGBUILD to the AUR if `AUR_PUBLISH` is set.

Never edit `CHANGELOG.md` or a version number by hand.
To force a specific version, put `Release-As: 1.0.0` in the body of a commit on `main`.

## Where the version lives

release-please rewrites these, and nothing else should.

- `.release-please-manifest.json`
- `app/build.gradle.kts`, where `versionCode` is computed from the version as `major * 10000 + minor * 100 + patch`
- `daemon/server.go`
- `packaging/aur/PKGBUILD`
- the example in `docs/protocol.md`

## One-time setup

Run the wizard from the repository root.

```sh
scripts/setup-release.sh
```

It creates the Android signing key, stores it as repository secrets, lets GitHub Actions open the release pull request, switches the repository to squash merging, and optionally registers a key for the AUR.

| Name | Kind | Used for |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | secret | The PKCS12 keystore that signs the APK. |
| `RELEASE_KEYSTORE_PASSWORD` | secret | Its password. |
| `RELEASE_KEY_ALIAS` | secret | The key's alias inside the keystore. |
| `AUR_SSH_PRIVATE_KEY` | secret | Pushing to the AUR. |
| `AUR_USERNAME`, `AUR_EMAIL` | variable | The author of the AUR commit. |
| `AUR_PUBLISH` | variable | Set to `true` to turn the AUR job on. |
| `RELEASE_PLEASE_TOKEN` | secret, optional | A fine-grained token with contents and pull request write access. |

The keystore stays in `~/.config/varch-controller/release.p12`.
Back it up with its password.
Android only installs an update signed by the same key, so losing it means every user has to uninstall and reinstall.

Pull requests opened with the default GitHub token do not start workflows, so CI does not run on the release pull request itself.
The release workflow runs the same checks on the tag before it builds anything.
Set `RELEASE_PLEASE_TOKEN` if you want CI on the release pull request as well, for example to require it with branch protection.

## When a release fails

The tag and the GitHub release exist as soon as the release pull request merges, before the builds run.
If a later job fails, fix the cause and choose "Re-run failed jobs" on the workflow run.
Uploads overwrite, so a re-run is safe.

A missing signing secret fails the `Signed APK` job with a message that says so.
The pipeline never publishes an unsigned APK.

## Checking a download

```sh
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify varch-controller-X.Y.Z.apk --repo SVIGHNESH/VarchController
```

## Running the checks locally

```sh
make -C daemon test
make -C daemon dist      # tarballs in dist/
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
docker run --rm -v "$PWD:/repo:ro" archlinux:base-devel /repo/packaging/aur/test.sh
```

To build a signed release APK locally, point Gradle at the keystore.

```sh
RELEASE_KEYSTORE_FILE=~/.config/varch-controller/release.p12 \
RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=release \
./gradlew :app:assembleRelease
```
