# Stable Android update signing

Starting with the stable-signed v3.3.0-beta2 APK, Android updates must use the same signing certificate.

The private keystore must **never** be committed to this public repository.

GitHub Actions expects one repository secret:

- `RCN1C_ANDROID_DEBUG_KEYSTORE_B64`: base64 of the persistent keystore.

The keystore uses alias `androiddebugkey`; the release workflow restores it to `~/.android/debug.keystore` before Gradle builds the release. If the secret is missing, the release workflow intentionally fails instead of publishing an APK Android would reject as an update.

The app's Updates screen also compares the signer certificate of a downloaded APK with the currently installed app before opening Android's installer.
