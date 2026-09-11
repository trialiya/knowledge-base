# Updating

What an upgrade of Knowledge Base asks of you: breaking changes, deprecations,
migration steps, and anything that can cost downtime. One section per version,
newest first.

Entries are written in the pull request that makes the change, not recalled
from memory at release time — that is the whole point of the file. At release
they are the raw material for the release notes.

This is not the changelog. [`CHANGELOG.md`](CHANGELOG.md) says what is new in a
release and is read by everyone; this file is read by whoever performs the
upgrade, and carries only what demands an action or a decision from them. A
feature that just works after the upgrade belongs there, not here.

**How to write an entry.** A heading naming the change, then two things: what
stops working as before, and what to do about it. Name the config keys, files
and commands involved — the reader is holding a deployment, not a diff.

## Unreleased

Nothing yet.

## 1.0.0-RC1

The first release. Both entries below matter to deployments that were already
running from `main`, not to a fresh install.

### The built JAR is now `backend/build/libs/kb.jar`

`bootJar` no longer puts the version in the artefact name — it was
`backend-<version>.jar`, so every release silently broke whatever referenced
it by path. The version stays available in the manifest
(`Implementation-Version`) and in the Admin panel.

Everything inside the repository — `run/run.sh`, `run/run.bat`, `run/run.ps1`,
`scripts/playwright-smoke.js`, `docker/Dockerfile` and the documentation — is
already updated. **Update your own deploy scripts** if they name the JAR.

### Flyway validates applied migrations

`spring.flyway.validate-on-migrate` is back to Flyway's own default, `true`. A
migration edited after it was applied now fails the start instead of letting
the schema drift away from `db/migration` unnoticed. From 1.0.0 on, schema
compatibility is a promise, and that is what keeps it one.

No migration in this repository has ever been edited after being applied, so a
deployment that only ever ran released code is unaffected. If your database
does carry a checksum mismatch, the start will name the migration: repair it
with `flyway repair`, do not turn the setting back off.
