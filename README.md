# VehicleOfflineVoice

Android MVP for a local/offline vehicle voice assistant.

## Current Scope

M3 keeps the Android foreground-service audio shell and adds sherpa-onnx keyword spotting:

- Kotlin Android app package: `com.company.vehiclevoice`
- Debug `MainActivity` with start, stop, and clear-log buttons
- Runtime permission request for microphone and Android 13 notifications
- `VoiceForegroundService` that starts and stops as a foreground service
- Foreground notification with a stop action
- `AudioRecorder` using `AudioRecord` at 16 kHz, mono, PCM16
- 20 ms PCM frames with live RMS displayed on the debug page
- sherpa-onnx Android CPU AAR packaged for `arm64-v8a`
- KWS model assets copied on first start to `filesDir/models/kws`
- Wake words: `小智小智` and `你好小智`
- Audio frames flow into `KwsEngine`; wake hits switch service state from `IDLE_KWS` to `LISTENING`
- No VAD, ASR, TTS, Redis, Unity integration, or network permission yet

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
