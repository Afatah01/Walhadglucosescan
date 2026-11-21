# Reliability upgrade: work in progress — not a production release

This branch is a snapshot for review, not a finished medical monitoring system. Do not distribute an APK from it or rely on it for glucose alarms. Keep the official Libre app and alarms in use.

Known blockers:
- Sync still depends on the existing backup-enabled setting. Do not assume authenticated users automatically sync in every state.
- Monitoring/session and forecast uploads are best-effort; sync can report success when those fail. Monitoring sessions are not yet durably queued and can be lost across restarts. Pending counts and last-copy status are incomplete.
- Local retention and concurrent work require auditing to prove that unsynced records are never lost and updates are not missed. Per-account changes and conflicts need review.
- Real authenticated integration tests, complete RLS verification, migrations, offline/reconnect, boot, duplicate-protection and device-to-Supabase tests have not been completed.
- The merged complete unit test suite did not pass: the Gradle daemon disappeared. CI and release signing have not been validated; signing secrets have not been configured.
- An AI shared secret existed in the public repository history and APKs. Rotate/revoke it on the backend; removing it from this branch does not remove past exposure. AI authentication must be redesigned; no shared secret should ship in a client. A GitHub token was also pasted in chat and must be revoked and replaced.
- The historical release workflow generates unrelated signing keys and has been removed on this branch. Verify the existing signing private key has not been exposed before reusing it. Password rotation alone cannot repair a leaked private key.
- Forecast spread is a model heuristic, not a validated confidence interval; forecasting must not be presented as a measured glucose value.
