# COBUG — GitHub + Main + Personal Workspace architecture

COBUG separates the external repository from the immutable project baseline and from each member's editable Workspace.

```text
GitHub (exact repository)
        |
        | source-only snapshot
        v
Main v1 -> Main v2 -> Main v3 ...
        |
        | member adds source files / initializes a Workspace
        v
Personal Workspace
        |
        | local edits
        v
Review changeset
        |
        | reviewer chooses any Main version for testing
        v
Test environment = selected Main version + review changes
        |
        | start review -> approve -> merge
        v
Atomic GitHub commit -> next Main version
```

## 1. GitHub section

The **GitHub** project section shows the exact connected repository and branch. It is read-only from the COBUG source workflow and is the external repository view.

- Repository connection is stored in PostgreSQL.
- GitHub source is read on demand through the Spring Boot backend.
- GitHub credentials stay backend-only.
- Runtime/build/IDE output is not copied into COBUG Main snapshots.
- Small text/source files are stored as content-addressed PostgreSQL objects.
- Large and binary project assets are stored in local COBUG storage; PostgreSQL keeps only their metadata/reference.

## 2. Main section

**Main is not the live GitHub tree.** Main is an immutable sequence of source snapshots captured from GitHub.

Each `MainVersion` records:

- project
- immutable version number
- GitHub commit SHA
- deterministic source root hash
- creator/time/message
- source file metadata through content-addressed `MainSourceObject` rows
- `DATABASE` storage for small text source and `LOCAL_STORAGE` storage for large/binary assets

Re-snapshotting the same GitHub commit returns the existing Main version instead of creating a duplicate.

## 3. Adding source to Workspace

When a member selects files in the GitHub section and clicks **Add to Workspace**:

1. COBUG snapshots the connected GitHub branch first.
2. That snapshot becomes the immutable Main baseline.
3. The selected source files are added to the member's Workspace references.
4. Every selected Workspace file stores the `baseMainVersionId` and version number.
5. Later GitHub changes therefore do not silently move the member's baseline.

`workspace_github_files` stores references/metadata, not source-code content.

## 4. Personal Workspace

The browser Workspace reads source metadata/content from the pinned Main snapshot. The Desktop Workspace is a real Windows folder. Large/binary Main assets are retained in local COBUG storage and are not treated as editable text files by the current browser editor.

Desktop metadata is stored in `.cobug/workspace.json` and PostgreSQL stores only the Workspace metadata/baseline. Dependencies, build output, IDE folders and caches remain local.

## 5. Review

A review stores only the source changes made by the member:

```text
baseMainVersionId
baseMainVersionNumber
baseCommitSha
path
content
baseSha
deleted
```

Review source-code changes are stored in the review record/local snapshot flow. Large project assets remain in the Main artifact storage layer rather than being copied into PostgreSQL.

## 6. Reviewer test environment

A reviewer can choose **any immutable Main version** from the project and ask COBUG to build:

```text
Selected Main version
        +
Review changeset
        =
Temporary test source tree
```

COBUG checks for source conflicts instead of silently overwriting incompatible files.

The reviewer can:

- validate the combined source tree against the selected Main version;
- in COBUG Desktop, set up the combined tree in a real Windows folder;
- download a ZIP containing the combined source and `.cobug/test-manifest.json`.

This package contains source only. Dependencies/build output are not downloaded or committed.

## 7. Merge

After approval, COBUG creates one atomic GitHub commit using Git blobs -> Git tree -> Git commit -> branch update.

The expected base commit is checked first. If GitHub Main moved, the merge is rejected rather than silently overwriting newer work.

After a successful merge, COBUG records the GitHub commit and captures the resulting branch as the next immutable Main version.

## Backend API highlights

### GitHub

- `POST /api/projects/{projectId}/github`
- `GET /api/projects/{projectId}/github/tree`
- `GET /api/projects/{projectId}/github/file`
- `POST /api/projects/{projectId}/github/workspace`

### Main

- `GET /api/projects/{projectId}/main/versions`
- `GET /api/projects/{projectId}/main/versions/{versionId}`
- `POST /api/projects/{projectId}/main/versions/snapshot-github`

### Workspace baseline

- `GET /api/projects/{projectId}/source/workspace`
- `PUT /api/projects/{projectId}/source/workspace`

### Review test environment

- `POST /api/projects/{projectId}/reviews/{reviewId}/validate` with `{ "mainVersionId": "..." }`
- `GET /api/projects/{projectId}/reviews/{reviewId}/test-package?mainVersionId=...`
- `GET /api/projects/{projectId}/reviews/{reviewId}/test-package/download?mainVersionId=...`

## GitHub credential boundary

The GitHub credential belongs only on the Spring Boot backend. Never put it in React `VITE_*` variables or localStorage.

```powershell
$env:GITHUB_TOKEN="YOUR_GITHUB_TOKEN"
cd backend\cobug-api
.\mvnw.cmd spring-boot:run
```


### GitHub connect transaction
Repository connection and the initial Main snapshot run in one database transaction. If the initial snapshot fails, the repository connection is rolled back so the UI cannot show a connected repository when Main creation failed.
