// ESP32 (original / WROOM / WROVER) - Bluetooth Classic SPP
// In the app: pair "ESP32-Terminal" in Android Bluetooth settings first,
// then Connect -> pick it from the paired list.
#include "BluetoothSerial.h"

BluetoothSerial SerialBT;

void setup() {
  Serial.begin(115200);
  SerialBT.begin("ESP32-Terminal");
}

void loop() {
  // Anything you print to SerialBT shows up in the app
  SerialBT.printf("uptime: %lu ms\n", millis());

  // Anything you type in the app arrives here
  while (SerialBT.available()) {
    Serial.write(SerialBT.read());
  }
  delay(100);
}
