# Orchard Notes

An Android app for reading and editing your Apple Notes through your iCloud account,
the same way www.icloud.com/notes does.

> Work in progress. See the sections below as features land.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
