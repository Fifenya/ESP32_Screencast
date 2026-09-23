# ESP32 Screen Cast (Android)

Mirrors Android phone screen to ESP32 + ILI9341 display over WiFi.
Uses system MediaProjection API. No third-party libraries.

## Build
1. Push to GitHub
2. Actions tab -> "Build APK" workflow runs automatically
3. Download artifact `ESP32-ScreenCast-APK` -> `app-debug.apk`
4. Install on phone (allow unknown sources)

## Use
1. ESP32 must run Server Monitor firmware (accepts JPEG POST at /frame)
2. Open app, enter ESP32 IP (e.g. 192.168.1.102)
3. START CASTING -> allow screen capture
4. Phone screen appears on TFT display (~10 FPS)
5. Stop via app button or notification shade# ESP32_Screencast
