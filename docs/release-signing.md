# Release APK signing

The CI workflow at `.github/workflows/android.yml` already knows how to sign
release builds on tag pushes — it just needs the secrets to be set. Currently
they're not, so tagged builds run `:app:assembleRelease` but produce an unsigned
APK and skip the GitHub Release attachment step. Wiring this up is a one-time
operation.

## 1. Create a keystore (one-time, locally)

```bash
keytool -genkeypair \
  -alias narrator \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -keystore narrator-release.jks
```

It prompts for a keystore password and a key password. Use distinct passwords if
you like; the CI workflow handles both. Validity of 100 years matches the F-Droid
recommendation.

**Keep `narrator-release.jks` safe.** Losing it means you can never push an
update to anyone who installed a signed build, because Android refuses to upgrade
an APK signed by a different key.

## 2. Encode the keystore for GitHub Secrets

```bash
base64 -w0 narrator-release.jks > narrator-release.jks.b64
# On Windows PowerShell:
# [Convert]::ToBase64String([IO.File]::ReadAllBytes("narrator-release.jks")) | Set-Content narrator-release.jks.b64
```

## 3. Set the GitHub secrets

In the repo on GitHub, go to `Settings` → `Secrets and variables` → `Actions` →
`New repository secret`. Add four secrets:

| Name                       | Value                                             |
| -------------------------- | ------------------------------------------------- |
| `RELEASE_KEYSTORE_BASE64`  | Contents of `narrator-release.jks.b64`            |
| `RELEASE_KEYSTORE_PASSWORD`| Keystore password from step 1                     |
| `RELEASE_KEY_ALIAS`        | `narrator` (the alias passed to keytool)          |
| `RELEASE_KEY_PASSWORD`     | Key password from step 1                          |

## 4. Verify

Push any `v*` tag. The CI workflow's "Decode signing keystore" step now runs
(it's gated on the tag prefix and the secret being non-empty), `:app:assembleRelease`
produces a signed APK, and the "Attach release APK to GitHub release" step uploads
it to the auto-created GitHub Release for that tag.

Open the tag's Releases page on GitHub — there should be a `.apk` file attached
that you can install directly.

## 5. Local release builds

If you want to build a signed release APK locally (rather than waiting for CI),
create `app/keystore.properties` (gitignored):

```properties
storeFile=../narrator-release.jks
storePassword=...
keyAlias=narrator
keyPassword=...
```

Then `./gradlew :app:assembleRelease`. Without this file the local release build
runs unsigned.

## Notes

- The workflow doesn't fail if the secrets are missing — the keystore decode step
  is gated on `if: startsWith(github.ref, 'refs/tags/v')` AND a non-empty
  `KEYSTORE_BASE64`. Tag pushes without secrets just produce an unsigned APK and
  skip the upload step.
- F-Droid signs builds with their own key; the keystore here is for users who
  install directly from the GitHub Release.
