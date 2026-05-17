#include <Arduino.h>
#include <NimBLEDevice.h>
#include <TFT_eSPI.h>

#define DEVICE_NAME "MotoNotifyDisplay"
#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

TFT_eSPI tft = TFT_eSPI();

NimBLEServer *bleServer = nullptr;
NimBLECharacteristic *rxCharacteristic = nullptr;

unsigned long lastNotificationTime = 0;
bool showingNotification = false;
String currentText = "";
bool scrollingText = false;

int scrollX = 0;
int textWidth = 0;

unsigned long lastScrollUpdate = 0;

bool deviceConnected = false;
void handleNotification(const String &message);
void drawIdleScreen();
void showScrollingOrCenteredText(const String &text, uint16_t color);
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

void showScrollingOrCenteredText(const String &text, uint16_t color)
{

    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(color, TFT_BLACK);

    textWidth = tft.textWidth(text, 4) * 2;

    if (textWidth <= 220)
    {
        scrollingText = false;

        tft.setTextDatum(MC_DATUM);
        tft.drawString(text, 120, 120, 4);
    }
    else
    {
        scrollingText = true;

        currentText = text;
        scrollX = 240;

        tft.setTextDatum(TL_DATUM);
    }
}

void drawIdleScreen()
{
    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(1);

    if (deviceConnected)
    {
        tft.drawString("Connected", 120, 120, 4);
    }
    else
    {
        tft.drawString("Not Connected", 120, 120, 4);
    }
}

void handleNotification(const String &message)
{
    showingNotification = true;
    lastNotificationTime = millis();
    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);
    if (message.startsWith("C:"))
    {
        String callInfo = message.substring(2);
        callInfo.trim();

        Serial.print("Incoming call: ");
        Serial.println(callInfo);

        showScrollingOrCenteredText(callInfo, TFT_RED);
    }
    else if (message.startsWith("WA:"))
    {
        String whatsappInfo = message.substring(3);
        whatsappInfo.trim();

        int separator = whatsappInfo.indexOf(':');

        String sender;

        if (separator != -1)
        {
            sender = whatsappInfo.substring(0, separator);
            sender.trim();
        }
        else
        {
            sender = whatsappInfo;
        }

        Serial.print("WhatsApp Sender: ");
        Serial.println(sender);

        showScrollingOrCenteredText(sender, TFT_GREEN);
    }
    else
    {
        Serial.print("Notification: ");
        Serial.println(message);

        tft.drawString(message, 120, 120, 4);
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
    drawIdleScreen();
}

void loop()
{
    static bool lastConnectionState = false;

    // Return to idle screen after 10 seconds
    if (showingNotification && millis() - lastNotificationTime > 10000)
    {
        showingNotification = false;
        drawIdleScreen();
    }

    // Update idle screen when connection state changes
    if (!showingNotification && lastConnectionState != deviceConnected)
    {
        lastConnectionState = deviceConnected;
        drawIdleScreen();
    }

    if (scrollingText)
    {
        if (millis() - lastScrollUpdate > 25)
        {
            lastScrollUpdate = millis();

            tft.fillRect(0, 80, 240, 80, TFT_BLACK);

            tft.setTextColor(TFT_WHITE, TFT_BLACK);
            tft.setTextDatum(TL_DATUM);
            tft.setTextSize(2);

            tft.drawString(currentText, scrollX, 100, 4);

            scrollX--;

            if (scrollX < -textWidth)
            {
                scrollX = 240;
            }
        }
    }
}