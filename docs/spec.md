# File Transfer plugin specification

## Scope

The plugin adds reusable file-transfer controls to Declarative and Scripted Pipeline jobs. It supports the classic Jenkins parameterized-build page only; Freestyle jobs, REST/CLI uploads, upstream file forwarding, and a separate upload service are outside the scope.

## Upload behavior

- A `fileOrFolderUpload` parameter appears alongside normal Pipeline parameters.
- Users select one file or one folder directly in a supported browser. Folder uploads preserve the selected top-level folder and its file hierarchy.
- The browser sends approximately 8 MiB chunks and retries a failed chunk up to three times.
- Every build requires a new upload. Tokens are bound to the authenticated user, job, and parameter.
- Defaults: 1 GiB per parameter, 2 GiB combined per build, 10,000 files, path depth 64, four uploads globally, and two uploads per user.
- Administrator hard limits default to 5 GiB and 50,000 files. A parameter may lower its own size/file limits and may define an extension allowlist. Administrators may define an extension denylist.
- Upload storage defaults to `${java.io.tmpdir}/jenkins-file-transfer`. New sessions are rejected unless the filesystem would retain at least 20 GiB or 10 percent free space, whichever is larger.

## Pipeline behavior

- `hasUploadedFiles(parameter: 'INPUT')` reports whether an optional upload is present.
- `restoreUploadedFiles(parameter: 'INPUT', target: 'input')` restores the upload on the current agent. The target must be absent or empty. A file remains a file under the target; a folder retains its original top-level directory and hierarchy.
- `publishDownloadableResults(path: 'output', name: 'Results')` archives a file or directory. A file is downloaded directly. A directory is exposed through Jenkins artifacts, including Jenkins' ZIP download and individual files.

## Lifecycle

- Incomplete uploads expire after 60 minutes.
- Completed uploads that have not started a build expire after 120 minutes.
- Successful builds delete inputs immediately by default.
- Failed or aborted builds retain inputs for three days by default.
- A Pipeline parameter may override successful and failed retention. The administrator maximum, 30 days by default, always applies. Deleting a build deletes its retained inputs.

## Compatibility and non-goals

- Minimum Jenkins version: 2.440.2 LTS.
- Java bytecode target: Java 11.
- Jenkins may run on a newer supported Java version, such as Java 21.
- The plugin does not provide HTTPS, certificate distribution, client-side automatic extraction after download, a database, Redis, Node.js, Python, or an independent service.
