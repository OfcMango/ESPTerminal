# ESP Terminal

Single-purpose Android Bluetooth terminal for ESP32.

## Get the APK without Android Studio
1. Create a free GitHub repo and upload everything in this folder (keep `.github/`).
2. Open the repo's **Actions** tab > **Build APK** > wait ~3 min.
3. Download the `ESPTerminal-apk` artifact, unzip it, copy `app-debug.apk` to your phone and install it
   (allow "install unknown apps" when asked).

## Build in Android Studio instead
1. Open this folder in Android Studio (Koala or newer, JDK 17). Let Gradle sync.
2. Plug in your phone (USB debugging on) and press Run, or Build > Build APK(s).

## Use
- Flash one of the sketches in `esp32_sketches/`:
  - `classic_spp` - original ESP32 (Bluetooth Classic). Pair it in Android settings first.
  - `ble_nus` - any ESP32 with BLE (S3, C3, etc.). No pairing needed.
- Open the app > Connect > choose the ESP32.
- Flip **Speak terminal output** to hear each line read aloud (uses the phone's own text-to-speech voice).
- Flip **Live mode** for zero-delay output.

## Live mode
- Every received chunk is repainted on the next UI tick (no batching timer).
- BLE: requests high-priority connection interval (shortest latency the phone allows).
- Keeps the screen awake while on.
- Off: output is batched every ~120 ms, which is lighter on the battery for fast streams.

## Speech
- Complete lines are spoken as they arrive; a trailing fragment is spoken after 400 ms.
- Live mode on: speech always jumps to the newest line instead of queueing a backlog.
- Change voice/language/speed in Android Settings > Text-to-speech output.
