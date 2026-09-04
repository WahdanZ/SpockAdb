# Developer Guide

## Table of Contents
- [Setup](#setup)
- [Making Changes](#making-changes)
- [Release Notes (CHANGELOG)](#release-notes-changelog)
- [Versioning](#versioning)
- [Publishing a Release](#publishing-a-release)
- [CI/CD Workflows](#cicd-workflows)
- [Required Secrets](#required-secrets)

---

## Setup

1. Clone the repo and open in Android Studio or IntelliJ IDEA
2. Make sure you have **JDK 17** installed
3. Run once to verify everything builds:
   ```bash
   ./gradlew buildPlugin
   ```
4. To run a sandboxed Android Studio with the plugin installed:
   ```bash
   ./gradlew runIde
   ```

---

## Making Changes

### Branch strategy
- All changes go through a **pull request** — never commit directly to `master`
- Branch naming: `feature/my-feature`, `fix/my-bug`, `chore/my-task`

### Workflow
```
1. Create a branch from master
2. Make your changes
3. Update CHANGELOG.md under [Unreleased]
4. Open a PR → CI builds automatically
5. Merge to master when approved
```

### Key files to know

| File | Purpose |
|---|---|
| `gradle.properties` | Plugin version, Android Studio target version |
| `src/main/kotlin/spock/adb/SpockAdbViewer.kt` | Main tool window UI logic |
| `src/main/kotlin/spock/adb/SpockAdbViewer.form` | UI layout (IntelliJ form designer) |
| `src/main/kotlin/spock/adb/AdbController.kt` | Interface for all ADB actions |
| `src/main/kotlin/spock/adb/AdbControllerImp.kt` | ADB action implementations |
| `src/main/kotlin/spock/adb/command/` | Individual ADB shell commands |
| `src/main/kotlin/spock/adb/AppSettingService.kt` | Persisted settings + `SpockAction` enum |
| `src/main/resources/META-INF/plugin.xml` | Plugin manifest |
| `CHANGELOG.md` | Release history (used by CI for release notes) |

### Adding a new action

1. Add a shell command in `command/` implementing `Command<P, R>` or `NoInputCommand<R>`
2. Declare the method in `AdbController.kt`
3. Implement it in `AdbControllerImp.kt`
4. Add a new entry to the `SpockAction` enum in `AppSettingService.kt`
5. Add a button/field to `SpockAdbViewer.form` (use IntelliJ's form designer)
6. Wire the button listener in `SpockAdbViewer.kt` → `initPlugin()`
7. Handle visibility in `SpockAdbViewer.kt` → `updateUi()`

### Threading rules
- **ADB shell commands are blocking** — never call them on the EDT
- Use `ApplicationManager.getApplication().executeOnPooledThread {}` to run ADB reads
- Use `ApplicationManager.getApplication().invokeLater {}` to update UI components
- Always call `removeDeveloperOptionsListeners()` before setting combo box / checkbox values to avoid stale listener triggers

---

## Release Notes (CHANGELOG)

The file `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com) format and is **read by CI** to populate GitHub Release notes automatically.

### Structure

```markdown
## [Unreleased]

## [3.0.0]
### Added
- ...
### Fixed
- ...
### Changed
- ...
### Removed
- ...
```

### Rules
- **Always** update `[Unreleased]` when you make a user-facing change
- Use these section headers: `Added`, `Fixed`, `Changed`, `Removed`, `Deprecated`
- Keep entries concise — one line per change
- The CI `release.yml` workflow moves `[Unreleased]` into the versioned section automatically when you publish

### Example entry
```markdown
## [Unreleased]
### Fixed
- Activity detection on Android 13+: fallback to topResumedActivity when mResumedActivity is empty
```

---

## Versioning

This project uses **Semantic Versioning** (`MAJOR.MINOR.PATCH`):

| Change type | Version bump | Example |
|---|---|---|
| Breaking change or major new feature set | MAJOR | `2.x.x` → `3.0.0` |
| New feature, backward compatible | MINOR | `3.0.x` → `3.1.0` |
| Bug fix | PATCH | `3.1.x` → `3.1.1` |

### Bump the version

**Option A — Manual workflow (recommended):**
1. Go to **GitHub → Actions → Bump Version → Run workflow**
2. Enter the new version (e.g. `3.1.0`)
3. The workflow creates a PR that updates `gradle.properties` and adds a new `[Unreleased]` section to `CHANGELOG.md`
4. Merge the PR

**Option B — Manually:**
```bash
# In gradle.properties
pluginVersion=3.1.0

# In CHANGELOG.md — add under [Unreleased]:
## [Unreleased]

## [3.1.0]    ← add this line after bumping
```

---

## Publishing a Release

> **Prerequisites:** The version in `gradle.properties` must match what you intend to release, and `CHANGELOG.md` must have entries under `[Unreleased]`.

### Step-by-step

**1. Merge all changes to `master`**
The CI (`build.yml`) will automatically:
- Build the plugin
- Create a **draft GitHub Release** tagged `v3.0.0` with the `.zip` attached

**2. Review the draft release**
- Go to **GitHub → Releases**
- Open the draft — it will contain the `[Unreleased]` changelog entries as the body
- Edit the notes if needed
- Click **Publish release**

**3. CI publishes automatically**
The `release.yml` workflow triggers and:
- Checks the built descriptor with `scripts/verify-marketplace-descriptor.sh` — plugin id,
  no `until-build`, `since-build`, and version-matches-tag — **before** publishing
- Signs the plugin with your certificates
- Publishes to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/11591-spockadb)
- Uploads the signed `.zip` to the GitHub Release
- Opens a PR that:
  - Moves `[Unreleased]` → `[3.0.0]` in `CHANGELOG.md`
  - Bumps `pluginVersion` to the next patch (`3.0.1`) for the next dev cycle

**4. Merge the post-release PR**

**5. Confirm the version is actually being served**

A green `release.yml` means *uploaded*, not *live*. The new version goes through Marketplace
approval and a compatibility-index rebuild, during which the plugin page can already show the
new description while the IDE's Install button still offers the previous version. Check
<https://plugins.jetbrains.com/plugin/11591/versions> for the version's state, then confirm in
a real IDE (**Settings → Plugins → Marketplace**, after *Check for Updates*).

If an IDE offers an *older* version than the one you published, that is a descriptor problem,
not a caching problem — read
[Why the Marketplace served 1.0.2 for four years](docs/COMPATIBILITY.md#why-the-marketplace-served-102-for-four-years).

---

## CI/CD Workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `build.yml` | Push to `master` or any PR | Builds plugin, verifies the Marketplace descriptor, creates/updates draft GitHub Release with `.zip` |
| `release.yml` | GitHub Release published | Verifies the descriptor, signs & publishes to Marketplace, opens post-release PR |
| `bump-version.yml` | Manual (`workflow_dispatch`) | Bumps version in `gradle.properties` + `CHANGELOG.md`, opens PR |

---

## Required Secrets

Configure these in **GitHub → Settings → Secrets → Actions**:

| Secret | Required for | How to get it |
|---|---|---|
| `PUBLISH_TOKEN` | Publishing to Marketplace | [JetBrains Marketplace → My Tokens](https://plugins.jetbrains.com/author/me/tokens) |
| `CERTIFICATE_CHAIN` | Plugin signing | [IntelliJ Plugin Signing docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PRIVATE_KEY` | Plugin signing | Same as above |
| `PRIVATE_KEY_PASSWORD` | Plugin signing | Same as above |

`GITHUB_TOKEN` is provided automatically by GitHub Actions — no setup needed.
