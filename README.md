<p align="center">
  <img src="gallery_banner.png" alt="Gallery Banner" />
</p>

A private, minimal photo and video gallery for Android.
No network. No cloud. No tracking.

![GitHub all releases](https://img.shields.io/github/downloads/jegly/Gallery/total)

---

## FEATURES

    - Photos and video playback
    - Albums organised by folder
    - Search by filename
    - Photo editor — crop, rotate, brightness, contrast, saturation
    - Pinch to zoom in viewer
    - Sort by date, modified, size, name
    - Filter by photo or video
    - EXIF stripped on share
    - Pretty themes and stuff

## REQUIREMENTS

    Android 11+
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

sha256:ff78c489d5ff3f27cbf40b0f92932127d7257b7b9d5f39102f0fe0889520e45b        - 3.0.1
