<p align="center">
  <img src="gallery_banner.png" alt="Gallery Banner" />
</p>

A private, minimal photo and video gallery for Android.
No network. No cloud. No tracking.

![GitHub all releases](https://img.shields.io/github/downloads/jegly/Gallery/total)

---

## FEATURES

    - Photos and video playback
    - Camera RAW previews (ARW, DNG, NEF, CR2, ORF, RAF, RW2 and more), shown from the JPEG preview embedded in the file
    - Albums organised by folder
    - Search by filename
    - Photo editor powered by Grainhouse:
        light and white balance with Auto, tone curve, colour mixer and grading,
        clarity, texture, dehaze, sharpening, noise reduction, vignette,
        crop, straighten and lens correction,
        153 film stocks, 24 creative looks, camera looks,
        diffusion filters, grain, halation, light leaks, frames,
        gradient and radial local adjustments, presets,
        undo and redo, press and hold to compare
    - Edits RAW files (ARW, DNG, CR2, CR3, NEF, RAF, ORF, RW2 and more), plus HEIC, WebP and AVIF
    - Save over the original, or save a copy as JPEG, PNG or TIFF at any size, with or without photo info
    - Pinch to zoom in viewer
    - Sort by date, modified, size, name
    - Filter by photo or video, and an optional Videos tab
    - Hide the Favourites and Search tabs
    - Picture-in-picture with optional auto-enter when you leave the app
    - Biometric lock
    - EXIF stripped on share
    - Pretty themes and stuff, including Material You

## REQUIREMENTS

    Android 13+
    arm64-v8a device

## BUILD

    Needs JDK 21, the Android SDK, Rust (rustup) with the aarch64-linux-android
    target, cargo-ndk and NDK 30.0.16248370 (Gradle builds the editor engine with them)

    git clone https://github.com/jegly/Gallery.git
    cd Gallery
    ./gradlew assembleDebug

    APK output: app/build/outputs/apk/debug/app-debug.apk

## STACK

    Kotlin + Jetpack Compose (Material 3)
    Hilt, Room, DataStore
    Coil 3, Media3 ExoPlayer
    Rust editor engine: Grainhouse's editing library (native/grainhouse, AGPL-3.0)
        behind a small JNI bridge (native/editor)

## PERMISSIONS

    READ_MEDIA_IMAGES
    READ_MEDIA_VIDEO
    READ_MEDIA_VISUAL_USER_SELECTED
    MANAGE_MEDIA

    No INTERNET or local network permission.

---

## RELEASE SIGNING

Release APKs use hybrid signing (APK Signature Scheme v3.2): the original app key plus an EC P-384 key and an ML-DSA-87 post-quantum key. Android 17 and newer verify the hybrid block, where the ML-DSA-87 key is the signer; earlier Android versions verify the original key as before.

### Checksums

| File | SHA-256 |
|---|---|
| `Gallery-4.2.3-PQC-signed.apk` | *sha256:304c313b043194fb44e771d01acc3b711226a97b28333745c2b502324df0d0db* |

### Signing certificate

Use this fingerprint with [Obtainium AppVerifier](https://github.com/ImranR98/Obtainium) to verify the APK was signed by the correct key before installing:

**Certificate SHA-256:** `98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c`

On Android 17 and later the APK is also verified through its post-quantum APK Signature Scheme v3.2 hybrid block:

| v3.2 signer | Certificate SHA-256 |
|---|---|
| PQC (ML-DSA-87) | `5f99de5b3195753d4fe8ddabebd2fb27356b2109d6fbb2471fcbbdf21a9e76a6` |
| Hybrid classical (EC P-384) | `fa8f4a07e6a4f2b7dc6e5ab31fc05592b1414ab9436d1e2e39b35ce3bb2e33a2` |

Verify a download (`apksigner` from build-tools 37.0.0 on JDK 25 shows all three keys):

```bash
sha256sum Gallery-4.2.3-PQC-signed.apk
apksigner verify --print-certs Gallery-4.2.3-PQC-signed.apk | grep SHA-256
```
