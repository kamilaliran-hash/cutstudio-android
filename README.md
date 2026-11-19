# CutStudio for Android

**Download the latest APK:** https://github.com/kamilaliran-hash/cutstudio-android/releases/latest/download/CutStudio.apk

A CapCut-style video editor for Android 8.0 and newer. Import videos, photos and music, then trim, split, reorder, add text, filters, transitions and speed changes, and export the result to your phone's gallery (Movies/CutStudio).

## Get the APK, option A: Android Studio (on your computer)

1. Install Android Studio from https://developer.android.com/studio
2. Unzip this folder, then in Android Studio choose **File > Open** and pick the `CutStudio-Android` folder.
3. Wait for "Gradle sync" to finish. The first time, it downloads build tools for a few minutes.
4. Choose **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
5. Click **locate** in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.
   Or plug in your phone with USB debugging on and press the green **Run** button.

## Get the APK, option B: GitHub (nothing to install)

1. Create a new repository on github.com and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build CutStudio APK" job starts by itself. You can also run it with **Run workflow**.
3. After about 5 minutes, open the finished run and download **CutStudio-APK** under *Artifacts*. The APK is inside the zip.

## Installing on your phone

Copy the APK to the phone and open it. Android will ask you to allow installs from that app (Files, Chrome, etc.). Allow it, then tap **Install**.

The APK is signed with a debug key, which is fine for your own phones. To publish on Google Play, create your own key with **Build > Generate Signed Bundle / APK** and upload an `.aab`.

## How it's built

- `app/src/main/assets/index.html` is the whole editor (HTML, CSS and JavaScript). Edit it to change the app.
- `MainActivity.java` hosts it in a WebView and adds the native parts: the media picker, saving exports to the gallery, keeping the screen on while rendering, and the back button.
- Export records the edit in real time, so a 1-minute video takes about 1 minute. Keep the app open while it renders.
