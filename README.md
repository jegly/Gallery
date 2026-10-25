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
    - Photo editor — crop, rotate, brightness, contrast, saturation
    - Pinch to zoom in viewer
    - Sort by date, modified, size, name
    - Filter by photo or video
    - EXIF stripped on share
    - Pretty themes and stuff
    - PIP 

## REQUIREMENTS

    Android 13+
    arm64-v8a device

## BUILD

    git clone https://github.com/jegly/gal.git
    cd gal
    ./gradlew assembleDebug

    APK output: app/build/outputs/apk/debug/app-debug.apk

## STACK

    Kotlin + Jetpack Compose
    Hilt, Room, DataStore
    Coil 3, Media3 ExoPlayer
    
    

## PERMISSIONS

    READ_MEDIA_IMAGES
    READ_MEDIA_VIDEO
    READ_MEDIA_VISUAL_USER_SELECTED
    MANAGE_MEDIA

    No INTERNET permission.

---

## RELEASE SIGNING

Release APKs use hybrid signing (APK Signature Scheme v3.2): the original app key plus an EC P-384 key and an ML-DSA-87 post-quantum key. Android 17 and newer verify the hybrid block, where the ML-DSA-87 key is the signer; earlier Android versions verify the original key as before.

| File | SHA-256 |
| --- | --- |
| `Gallery-4.2.0-PQC-signed.apk` | `8cc183c195cfda29943ca9ca70a99354f3df77476309516ebd16354db4995c13` |

Verify a download:

```bash
sha256sum Gallery-4.2.0-PQC-signed.apk
apksigner verify -v --print-certs Gallery-4.2.0-PQC-signed.apk   # build-tools 37.0.0 on JDK 25 shows all three keys
```

Signing certificate SHA-256:

- ML-DSA-87 (post-quantum): `5f99de5b3195753d4fe8ddabebd2fb27356b2109d6fbb2471fcbbdf21a9e76a6`
- EC P-384 (hybrid classical): `fa8f4a07e6a4f2b7dc6e5ab31fc05592b1414ab9436d1e2e39b35ce3bb2e33a2`
- Original key: `98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c`
