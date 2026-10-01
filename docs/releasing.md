# Releasing

Releases are built and published by GitHub Actions (`.github/workflows/build.yml`): pushing a tag like `v0.2.0` runs the tests, builds the minified release APK, signs it with the project's release key and publishes it as a GitHub Release with the APK attached.

Every push to a branch runs the same build, signed with a throwaway key instead, so a broken build or signing setup shows up long before a release.

## One-time setup: the release key

Android only installs an update over an existing install if both are signed with the same key. Every release must therefore be signed with one key, kept for the life of the app. **If the key is lost, nobody can update: they'd have to uninstall (losing their sign-in and cached notes) and install again.**

1. Create the key on your own computer (any machine with a JDK, which includes Android Studio's). Pick a strong password and use it for both prompts if asked:

   ```bash
   keytool -genkeypair -v -keystore orchard-release.jks -alias orchard \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Orchard Notes"
   ```

2. Back up `orchard-release.jks` and its password somewhere safe and private, such as a password manager. Never commit either to the repository.

3. Turn the key into text that fits in a GitHub secret:

   ```bash
   base64 -w0 orchard-release.jks > orchard-release.b64     # Linux
   base64 -i orchard-release.jks -o orchard-release.b64     # macOS
   ```

   On Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("orchard-release.jks")) | Out-File -NoNewline orchard-release.b64`

4. On GitHub, open the repository's **Settings > Secrets and variables > Actions** and add four **repository secrets**:

   | Name | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | the whole contents of `orchard-release.b64` |
   | `RELEASE_KEYSTORE_PASSWORD` | the password |
   | `RELEASE_KEY_ALIAS` | `orchard` |
   | `RELEASE_KEY_PASSWORD` | the password again (keytool's keystores use one password for both) |

5. Delete `orchard-release.b64`; the backup of the `.jks` file is what you keep.

Without these secrets, a release tag fails with an error instead of publishing an APK signed with some other key.

## Publishing a release

Release from `main`, once the commit you want to ship has passed its build:

```bash
git checkout main && git pull
git tag v0.2.0
git push origin v0.2.0
```

Then watch the **Actions** tab. After about ten minutes the release appears under **Releases**, with `orchard-notes-v0.2.0.apk`, notes generated from the pull requests merged since the previous release, and the SHA-256 of the APK and of the signing certificate.

Tags must look like `vMAJOR.MINOR.PATCH`, each part at most three digits. The tag sets the app's version: `versionName` is `0.2.0` and `versionCode` is `MAJOR * 1000000 + MINOR * 1000 + PATCH` (`2000` here). Android refuses to install a lower version code over a higher one, so each tag has to be higher than the last.

If a release build fails, fix the problem on `main`, then delete the tag and push it again:

```bash
git tag -d v0.2.0 && git push origin :refs/tags/v0.2.0
git tag v0.2.0 && git push origin v0.2.0
```

If a release was already published under that tag, delete it on the Releases page first, or use the next version number instead.

## Building a release-signed APK locally (optional)

To build exactly what a release would be, without publishing, add the key to `~/.gradle/gradle.properties` (your user-wide Gradle settings, never the project's):

```properties
orchardKeystoreFile=/absolute/path/to/orchard-release.jks
orchardKeystorePassword=...
orchardKeyAlias=orchard
orchardKeyPassword=...
```

Then `./gradlew assembleRelease -PorchardVersion=0.2.0` writes `app/build/outputs/apk/release/app-release.apk`. Without those properties, `assembleRelease` signs with the local debug key, as before.

## Installs signed with another key

Builds installed with `adb install` from `assembleDebug`, or from `assembleRelease` without the release key, are signed with that computer's debug key. Android won't install a release over them: uninstall once (this signs you out and clears the cache), then install the release. After that, every release installs over the previous one.
