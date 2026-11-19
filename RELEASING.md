# Releasing

A stable tag such as `v0.0.7` starts the **Publish a release** workflow automatically. There is no manual workflow trigger. The tagged commit must already be on `main` for the publishing steps to run.

## Prerequisites

- The repository secrets `SONATYPE_CENTRAL_USERNAME` and `SONATYPE_CENTRAL_PASSWORD` must contain a Central Portal user token with publishing access to `app.cash.lninvoice`.
- `GPG_SECRET_KEY` and `GPG_SECRET_PASSPHRASE` must provide the signing key and passphrase. The public key must be available for Maven Central signature verification.
- GitHub Pages must use GitHub Actions and retain the `github-pages` environment.

The Gradle properties `mavenCentralPublishing=true`, `mavenCentralAutomaticPublishing=true`, and `signAllPublications=true` enable Central Portal uploads, automatic publication after validation, and signed artifacts.

## Steps

1. Choose the release and next development versions:

   ```sh
   export RELEASE_VERSION=0.0.7
   export NEXT_VERSION=0.0.8-SNAPSHOT
   ```

2. Start a release preparation branch from current `main`. Include all intended changes before preparing the release.

   ```sh
   git switch main
   git pull --ff-only
   git switch -c release-$RELEASE_VERSION
   ```

3. Add the changes since the previous release to `CHANGELOG.md`. Update any fixed dependency versions in `README.md`, and set `VERSION_NAME` in root `gradle.properties` to the release version.

4. Activate Hermit and verify the release candidate:

   ```sh
   . ./bin/activate-hermit
   gradle clean build dokkaGeneratePublicationHtml :lib:dokkaJavadocJar --no-daemon
   ```

   Confirm that tests and API checks pass, documentation exists at `build/dokka/html`, and the documentation JAR contains the library's Dokka HTML.

5. Commit the preparation changes, push the branch, and open a PR. Wait for checks and review, then merge the PR. Do not tag the preparation branch: a squash merge creates a different commit.

6. Fetch the merged preparation commit and set `RELEASE_COMMIT` to its exact commit ID. Verify its version and reachability, then tag it:

   ```sh
   git fetch origin main
   git merge-base --is-ancestor "$RELEASE_COMMIT" origin/main
   test "$(git show "$RELEASE_COMMIT:gradle.properties" | sed -n 's/^VERSION_NAME=//p')" = "$RELEASE_VERSION"
   git tag -a "v$RELEASE_VERSION" "$RELEASE_COMMIT" -m "Version $RELEASE_VERSION"
   git push origin "refs/tags/v$RELEASE_VERSION"
   ```

   Keep `VERSION_NAME` and the tag version aligned. The current workflow supplies the tag version to Gradle but does not check that they match. If the tag already exists, inspect it and its workflow before taking further action; do not move it.

7. Follow **Publish a release** in GitHub Actions. It uploads signed artifacts to the [Central Portal](https://central.sonatype.com/publishing/deployments), which publishes validated deployments automatically, then builds and deploys documentation.

8. Confirm `app.cash.lninvoice:ln-invoice:$RELEASE_VERSION` is available on Maven Central. Indexing can take time. Create the GitHub release for the existing tag and use the corresponding `CHANGELOG.md` entry as its notes.

9. Start a new branch from updated `main`, set `VERSION_NAME` to `$NEXT_VERSION`, and submit a separate PR for the next development version.

## Troubleshooting

- If a tag workflow skips publishing, verify that the tagged commit is reachable from `main`. Creating the tag before the preparation PR merges does not cause the workflow to retry after the merge.
- Inspect the Central Portal deployment before retrying an upload. A published Maven version is immutable; do not rerun publishing after it succeeds.
- If publication succeeds and documentation fails, repair documentation separately. Rerunning the whole publishing job would attempt to publish the same version again.
- If publishing access or signing credentials are missing, have a maintainer configure the repository secrets. Do not put credentials in the repository or local build files.
