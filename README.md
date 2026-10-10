# Jelly Buddy

A goofy 3D jelly blob with googly eyes who lives in a little toy tray on your phone. He's drawn in real time with OpenGL ES and reacts to your phone's motion sensors.

| Do this | He does this |
|---|---|
| **Tilt** the phone | Sloshes downhill, leans, and splats against the walls (with a little haptic bump) |
| **Tap** the screen | Jumps, stretches, and lands with a squish |
| **Spin** the phone flat | Twirls around, then gets dizzy — pupils spin in opposite circles |
| **Shake** it | Total chaos |

His pupils are tiny physics objects: they roll downhill, slam into the edge of his eyes on impact, and float up when he's mid-jump. His floppy antenna lags behind everything he does.

## Install

Grab the latest `JellyBuddy-x.y.z.apk` from **Releases**, open it on an Android phone (Android 8.0 or newer), and allow installing from your browser if prompted. A gyroscope makes the spin/dizzy trick work; everything else only needs the accelerometer.

## Build

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and the Android SDK (platform 35). No third-party libraries — just the Android framework and OpenGL ES 2.0.

## Releasing

Bump `versionCode` and `versionName` in `app/build.gradle` and push to `main`. GitHub Actions builds and verifies the APK, and if there's no release for that version yet it creates the `vX.Y.Z` tag and publishes a GitHub Release with the APK attached. Pushing a `v*` tag yourself also publishes a release.

Every push to `main` builds the APK and attaches it to the workflow run as an artifact, even when no new release is made.

## Signing

Builds are signed with `keystore/jellybuddy-test.jks` — a **public test key** committed on purpose (password `android`), like Android's debug key. Because it never changes, each release installs over the previous one. Don't use it for the Play Store; for that, create your own keystore and store it in GitHub Secrets.

## Code tour

- `MainActivity.java` — sets up the GL view, reads the gravity, accelerometer, and gyroscope sensors, and handles taps and shakes.
- `JellyRenderer.java` — the simulation (spring physics for squash & stretch, lean, antenna, pupils, dizziness) and the rendering.
- `Mesh.java` — builds the sphere and quad meshes everything is made from.
