# Shipping a version

Do not ship the current reliability work: see `RELIABILITY-WORK-IN-PROGRESS.md`.

Before releasing, complete the tests and security review described there. Configure GitHub Actions signing secrets with a securely maintained signing key only after verifying the key's provenance and that it has not been exposed. Never commit a keystore, password, access token or backend shared secret. A client APK cannot safely keep a shared secret.

The `build.yml` workflow uses a fixed expected signing-certificate fingerprint and refuses to publish a differently signed APK. This workflow and its secrets have not yet been verified in GitHub Actions.
