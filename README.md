# VehicleOfflineVoice

Android MVP for a local/offline vehicle voice assistant.

## M1 Scope

This milestone intentionally implements only the Android shell:

- Kotlin Android app package: `com.company.vehiclevoice`
- Debug `MainActivity` with start, stop, and clear-log buttons
- Runtime permission request for microphone and Android 13 notifications
- `VoiceForegroundService` that starts and stops as a foreground service
- Foreground notification with a stop action
- No KWS, VAD, ASR, TTS, Redis, Unity integration, or network permission yet

## Build

Open this folder in Android Studio and use:

```text
Build -> Build APK(s)
```

Or build from PowerShell with the checked-in Gradle Wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

The project deliberately does not request `INTERNET` permission for the phone mock stage.
