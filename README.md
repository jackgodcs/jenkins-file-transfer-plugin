# Jenkins File Transfer Plugin

Reusable Pipeline controls for uploading one file or one folder from the classic parameterized-build page, restoring it into an agent workspace, and publishing downloadable results. The plugin requires Jenkins 2.440.2 or newer and emits Java 11 bytecode.

## Pipeline example

```groovy
pipeline {
  agent any

  parameters {
    fileOrFolderUpload(
      name: 'INPUT',
      description: 'Select a file or folder',
      required: true,
      maxBytes: 1073741824,
      maxFiles: 10000,
      allowedExtensions: ['txt', 'csv'],
      successRetentionDays: 0,
      failureRetentionDays: 3
    )
  }

  stages {
    stage('Prepare') {
      steps {
        script {
          if (hasUploadedFiles(parameter: 'INPUT')) {
            restoreUploadedFiles parameter: 'INPUT', target: 'input'
          }
        }
      }
    }
    stage('Publish') {
      steps {
        publishDownloadableResults path: 'output', name: 'Results'
      }
    }
  }
}
```

`required` defaults to `true`. `maxBytes` and `maxFiles` default to the administrator values. Retention values of `-1` inherit the administrator policy. A folder is restored with its selected top-level directory intact. The restore target must not exist or must be empty.

## User workflow

1. Open the job's classic **Build with Parameters** page.
2. Select a file or folder and wait until the control reports that the upload is complete.
3. Fill in any other Pipeline parameters and start the build.
4. Open **Downloadable Results** on the completed build. A published file downloads directly; a published directory supports Jenkins' artifact ZIP and individual-file downloads.

The upload control is intentionally limited to the classic browser page. REST, CLI, Freestyle, and upstream file transfer are not supported. Every new build needs a new upload.

## Global configuration

Configure **Manage Jenkins → System → File transfer**. Defaults are:

| Setting | Default |
| --- | ---: |
| Storage root | `${java.io.tmpdir}/jenkins-file-transfer` |
| Maximum bytes per parameter | 1 GiB |
| Hard maximum bytes | 5 GiB |
| Combined bytes per build | 2 GiB |
| Maximum files per parameter | 10,000 |
| Hard maximum files | 50,000 |
| Maximum path depth | 64 |
| Concurrent uploads | 4 global / 2 per user |
| Incomplete upload expiry | 60 minutes |
| Completed pre-build expiry | 120 minutes |
| Successful/failed retention | 0 / 3 days |
| Maximum retention | 30 days |
| Reserved free disk | max(20 GiB, 10%) |

The administrator may also configure a comma-separated extension denylist. Parameter allowlists cannot override it. Upload metadata and files are stored on the controller; restoration and artifact publication work with agent workspaces.

## Install

Download `file-transfer.hpi` from the [latest release](https://github.com/jackgodcs/jenkins-file-transfer-plugin/releases/latest), then upload it from **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin** and restart Jenkins when requested. To build from source, run `mvn verify` and use `target/file-transfer.hpi`. Configure the job through Pipeline syntax; ordinary users do not configure the control for each build.

## Continuous integration and releases

GitHub Actions runs `mvn verify` for every branch push and pull request. Successful CI runs retain `file-transfer.hpi` as a workflow artifact for 14 days.

To publish automatically, update the project version in `pom.xml`, merge that change, and push a matching semantic-version tag such as `0.2.0`. The release workflow verifies the version, runs the complete test suite, and publishes the HPI as a GitHub Release asset.

To publish manually, open **Actions → Release → Run workflow**, enter the version and the branch, commit, or existing tag to release, and optionally mark it as a prerelease. The requested version must already match `pom.xml`. If the tag does not exist, the workflow creates it after verification.

See [docs/spec.md](docs/spec.md) for the agreed behavior, limits, lifecycle, and non-goals.
