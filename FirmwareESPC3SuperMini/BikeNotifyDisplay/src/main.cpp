#include <Arduino.h>
#include <NimBLEDevice.h>
#include <TFT_eSPI.h>

#define DEVICE_NAME "MotoNotifyDisplay"
#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

TFT_eSPI tft = TFT_eSPI();

NimBLEServer *bleServer = nullptr;
NimBLECharacteristic *rxCharacteristic = nullptr;

bool deviceConnected = false;
void handleNotification(const String &message);

class ServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *server, NimBLEConnInfo &connInfo) override
    {
        deviceConnected = true;
        Serial.println("Phone connected");
    }

    void onDisconnect(NimBLEServer *server, NimBLEConnInfo &connInfo, int reason) override
    {
        deviceConnected = false;
        Serial.println("Phone disconnected");

        NimBLEDevice::startAdvertising();
        Serial.println("Advertising restarted");
    }
};

class NotificationCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &connInfo) override
    {
        std::string value = characteristic->getValue();

        if (value.length() == 0)
        {
            return;
        }

        String message = String(value.c_str());

        Serial.println();
        Serial.println("----- New Notification -----");
        Serial.println(message);
        Serial.println("----------------------------");

        handleNotification(message);
    }
};

void handleNotification(const String &message)
{
    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);

    if (message.startsWith("C:"))
    {
        String callInfo = message.substring(2);
        callInfo.trim();

        Serial.print("Incoming call: ");
        Serial.println(callInfo);

        tft.setTextColor(TFT_RED, TFT_BLACK);

        tft.drawString(callInfo, 1, 1, 5);
    }
    else if (message.startsWith("WA:"))
    {
        String whatsappInfo = message.substring(3);
        whatsappInfo.trim();

        Serial.print("WhatsApp: ");
        Serial.println(whatsappInfo);

        tft.setTextColor(TFT_GREEN, TFT_BLACK);

        tft.drawString("WhatsApp", 120, 70, 4);

        tft.setTextColor(TFT_WHITE, TFT_BLACK);

        tft.drawString(whatsappInfo, 120, 130, 2);
    }
    else
    {
        Serial.print("Notification: ");
        Serial.println(message);

        tft.drawString(message, 120, 120, 2);
    }
}

void setupBLE()
{
    NimBLEDevice::init(DEVICE_NAME);

    Serial.print("BLE MAC: ");
    Serial.println(NimBLEDevice::getAddress().toString().c_str());

    bleServer = NimBLEDevice::createServer();
    bleServer->setCallbacks(new ServerCallbacks());

    NimBLEService *service = bleServer->createService(SERVICE_UUID);

    rxCharacteristic = service->createCharacteristic(
        CHARACTERISTIC_UUID,
        NIMBLE_PROPERTY::READ |
            NIMBLE_PROPERTY::WRITE |
            NIMBLE_PROPERTY::WRITE_NR);

    rxCharacteristic->setCallbacks(new NotificationCallbacks());

    service->start();

    bleServer->start();

    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();

    advertising->setName(DEVICE_NAME);
    advertising->enableScanResponse(true);

    NimBLEDevice::startAdvertising();

    Serial.println("BLE ready");
    Serial.print("Device name: ");
    Serial.println(DEVICE_NAME);
}

void setup()
{

    Serial.begin(115200);
    delay(1000);

    tft.init();
    tft.setRotation(0);

    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);

    tft.drawString("MotoNotify", 120, 100, 4);
    tft.drawString("Starting...", 120, 140, 2);

    Serial.println();
    Serial.println("Moto Notification Display starting...");

    setupBLE();
}

void loop()
{
    static unsigned long lastStatusPrint = 0;

    if (millis() - lastStatusPrint >= 5000)
    {
        tft.fillScreen(TFT_BLACK);
        lastStatusPrint = millis();

        if (deviceConnected)
        {
            Serial.println("Status: phone connected");
            tft.setTextColor(TFT_WHITE, TFT_BLACK);
            tft.setTextDatum(MC_DATUM);

            tft.drawString("Connected!", 120, 100, 4);
        }
        else
        {
            Serial.println("Status: waiting for phone...");
            tft.setTextColor(TFT_WHITE, TFT_BLACK);
            tft.setTextDatum(MC_DATUM);

            tft.drawString("Not Connected!", 120, 100, 4);
        }
    }
}