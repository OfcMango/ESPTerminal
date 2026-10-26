// ESP32 / ESP32-S3 / ESP32-C3 - BLE with Nordic UART Service
// In the app: Connect -> pick "ESP32-Terminal" from the BLE scan results.
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHAR_RX_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // phone -> ESP32
#define CHAR_TX_UUID "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // ESP32 -> phone

BLECharacteristic *txChar;
bool deviceConnected = false;

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *s) { deviceConnected = true; }
  void onDisconnect(BLEServer *s) {
    deviceConnected = false;
    s->getAdvertising()->start();
  }
};

class RxCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) {
    auto v = c->getValue();
    Serial.println(v.c_str());  // text typed in the app
  }
};

// Use this instead of Serial.print when you want output in the app
void btPrint(const char *msg) {
  if (!deviceConnected) return;
  txChar->setValue((uint8_t *)msg, strlen(msg));
  txChar->notify();
}

void setup() {
  Serial.begin(115200);
  BLEDevice::init("ESP32-Terminal");
  BLEDevice::setMTU(247);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *svc = server->createService(SERVICE_UUID);

  txChar = svc->createCharacteristic(CHAR_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  BLECharacteristic *rxChar = svc->createCharacteristic(
      CHAR_RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rxChar->setCallbacks(new RxCb());

  svc->start();
  server->getAdvertising()->addServiceUUID(SERVICE_UUID);
  server->getAdvertising()->start();
}

void loop() {
  char line[64];
  snprintf(line, sizeof(line), "uptime: %lu ms\n", millis());
  btPrint(line);
  delay(100);
}
