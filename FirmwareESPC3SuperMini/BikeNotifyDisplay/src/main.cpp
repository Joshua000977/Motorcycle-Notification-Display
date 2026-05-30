#include <Arduino.h>
#include <NimBLEDevice.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <ArduinoOTA.h>
#include <Preferences.h>
Preferences prefs;

#define DEVICE_NAME "MotoNotifyDisplay"
#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

TFT_eSPI tft = TFT_eSPI();

NimBLEServer *bleServer = nullptr;
NimBLECharacteristic *rxCharacteristic = nullptr;

// OTA
String wifiSSID = "";
String wifiPassword = "";
/*for platformio.ini
upload_protocol = espota
upload_port = 10.168.122.34
*/

bool wifiConnected = false;

void connectToWiFi();
void setupOTA();

unsigned long lastNotificationTime = 0;
bool showingNotification = false;
String currentText = "";
bool scrollingText = false;

int scrollX = 0;
int textWidth = 0;

unsigned long lastScrollUpdate = 0;
int scrollLoops = 0;
uint16_t currentTextColor = TFT_WHITE;

float frontPressure = 1.9f;
float rearPressure = 1.5f;

int mobileBatLvl = -1;

bool deviceConnected = false;
void handleNotification(const String &message);
void drawIdleScreen();
void showScrollingOrCenteredText(const String &text, uint16_t color);

enum class PressureState
{
    OK,
    WARNING,
    CRITICAL
};

PressureState getPressureState(float pressure)
{
    if (pressure <= 0)
        return PressureState::CRITICAL;

    if (pressure < 1.8f)
        return PressureState::CRITICAL;

    if (pressure < 2.0f)
        return PressureState::WARNING;

    if (pressure > 3.2f)
        return PressureState::CRITICAL;

    if (pressure > 2.9f)
        return PressureState::WARNING;

    return PressureState::OK;
}

uint16_t getPressureColor(float pressure)
{
    switch (getPressureState(pressure))
    {
    case PressureState::CRITICAL:
        return TFT_RED;

    case PressureState::WARNING:
        return TFT_YELLOW;

    default:
        return TFT_WHITE;
    }
}
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
        Serial.println("----- Received Message -----");
        Serial.println(message);
        Serial.println("----------------------------");

        // WIFI:SSID:PASSWORD
        if (message.startsWith("SSID:"))
        {
            wifiSSID = message.substring(5);

            prefs.begin("wifi", false);
            prefs.putString("ssid", wifiSSID);
            prefs.end();
        }

        else if (message.startsWith("P:"))
        {
            wifiPassword = message.substring(2);

            prefs.begin("wifi", false);
            prefs.putString("password", wifiPassword);
            prefs.end();
        }

        else if (message == "WIFI_CONNECT")
        {
            connectToWiFi();
            return;
        }

        // OTA_START
        if (message == "OTA_START")
        {
            Serial.println("Starting OTA mode...");

            // Stop scrolling/notifications
            scrollingText = false;
            showingNotification = false;

            tft.fillScreen(TFT_BLACK);
            tft.setTextDatum(MC_DATUM);
            tft.setTextColor(TFT_WHITE, TFT_BLACK);

            tft.drawString("OTA MODE", 120, 90, 4);
            tft.drawString("Connecting WiFi...", 120, 130, 2);

            // Stop BLE advertising
            NimBLEDevice::stopAdvertising();

            delay(500);

            connectToWiFi();

            return;
        }

        if (message.startsWith("BAT:"))
        {
            mobileBatLvl = message.substring(4).toInt();

            Serial.print("Phone battery: ");
            Serial.print(mobileBatLvl);
            Serial.println("%");

            if (!showingNotification && !scrollingText && !wifiConnected)
            {
                drawIdleScreen();
            }

            return;
        }

        // Normal notifications
        handleNotification(message);
    }
};

void connectToWiFi()
{
    prefs.begin("wifi", true);

    wifiSSID = prefs.getString("ssid", "");
    wifiPassword = prefs.getString("password", "");

    prefs.end();

    if (wifiSSID.isEmpty())
    {
        Serial.println("No WiFi credentials saved");
        return;
    }

    Serial.println("Connecting to WiFi...");
    Serial.print("SSID: ");
    Serial.println(wifiSSID);

    WiFi.disconnect(true);
    delay(500);

    WiFi.mode(WIFI_STA);

    WiFi.begin(wifiSSID.c_str(), wifiPassword.c_str());

    int timeout = 0;

    while (WiFi.status() != WL_CONNECTED && timeout < 30)
    {
        delay(500);
        Serial.print(".");

        timeout++;
    }

    Serial.println();

    if (WiFi.status() == WL_CONNECTED)
    {
        wifiConnected = true;

        Serial.println("WiFi connected");

        String ip = WiFi.localIP().toString();

        Serial.print("IP: ");
        Serial.println(ip);

        tft.fillScreen(TFT_BLACK);

        tft.drawString("OTA READY", 120, 80, 4);
        tft.drawString(ip, 120, 120, 2);

        delay(1000);

        String response = "IP:" + ip;

        rxCharacteristic->setValue(response.c_str());
        rxCharacteristic->notify();

        setupOTA();
    }
    else
    {
        wifiConnected = false;

        Serial.println("WiFi connection failed");

        tft.fillScreen(TFT_BLACK);

        tft.drawString("F", 120, 120, 2);
    }
}

void setupOTA()
{
    ArduinoOTA.setHostname("MotoNotify");

    ArduinoOTA.onStart([]()
                       {
                            Serial.println("OTA Start");
                     
                        scrollingText = false;
                        showingNotification = false; });

    ArduinoOTA.onEnd([]() {});

    ArduinoOTA.onProgress([](unsigned int progress, unsigned int total)
                          { Serial.printf("OTA Progress: %u%%\r", (progress * 100) / total); });

    ArduinoOTA.onError([](ota_error_t error)
                       {
    String err = "OTA:ERROR:" + String(error);
        Serial.println(err.c_str()); });

    ArduinoOTA.begin();

    Serial.println("OTA Ready");
    Serial.println("Hostname: MotoNotify.local");
}

void showScrollingOrCenteredText(const String &text, uint16_t color)
{

    tft.fillScreen(TFT_BLACK);

    currentTextColor = color;
    tft.setTextColor(currentTextColor, TFT_BLACK);
    tft.setTextSize(2);

    textWidth = tft.textWidth(text, 4);

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
        scrollX = 10;
        scrollLoops = 0;

        tft.setTextDatum(TL_DATUM);
    }
}

void drawIdleScreen()
{
    tft.fillScreen(TFT_BLACK);

    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextSize(2);

    String frontText =
        (frontPressure < 0)
            ? "F: --.- bar"
            : "F: " + String(frontPressure, 1) + " bar";

    String rearText =
        (rearPressure < 0)
            ? "R: --.- bar"
            : "R: " + String(rearPressure, 1) + " bar";

    tft.setTextColor(getPressureColor(frontPressure), TFT_BLACK);
    tft.drawString(frontText, 120, 100, 4);

    tft.setTextColor(getPressureColor(rearPressure), TFT_BLACK);
    tft.drawString(rearText, 120, 150, 4);

    String phoneBatText =
        (deviceConnected && mobileBatLvl >= 0)
            ? String(mobileBatLvl) + "%"
            : "--%";
    tft.setTextSize(1);
    tft.setTextColor(TFT_WHITE);
    tft.drawLine(0, 180, 239, 180, TFT_WHITE);
    tft.drawString(phoneBatText, 80, 200, 4);
    // Phone connection indicator
    if (deviceConnected)
    {
        tft.fillCircle(150, 200, 6, TFT_BLUE);
    }
    else
    {
        tft.fillCircle(150, 200, 6, TFT_RED);
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
            NIMBLE_PROPERTY::WRITE_NR |
            NIMBLE_PROPERTY::NOTIFY);
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
    if (wifiConnected)
    {

        ArduinoOTA.handle();
    }

    static bool lastConnectionState = false;
    // Return to idle screen after 10 seconds
    if (showingNotification && millis() - lastNotificationTime > 10000)
    {
        showingNotification = false;
        drawIdleScreen();
    }

    // Update idle screen when connection state changes
    if (!showingNotification && lastConnectionState != deviceConnected && !wifiConnected)
    {
        lastConnectionState = deviceConnected;
        drawIdleScreen();
    }

    if (scrollingText && !wifiConnected)
    {
        if (millis() - lastScrollUpdate > 50)
        {
            lastScrollUpdate = millis();

            tft.fillRect(0, 80, 240, 80, TFT_BLACK);

            tft.setTextColor(currentTextColor, TFT_BLACK);

            tft.setTextDatum(TL_DATUM);

            tft.setTextSize(2);

            tft.drawString(currentText, scrollX, 100, 4);

            scrollX--;

            if (scrollX < -textWidth)
            {
                scrollLoops++;

                if (scrollLoops >= 2)
                {
                    scrollingText = false;
                    showingNotification = false;
                    drawIdleScreen();
                }
                else
                {
                    scrollX = 10;
                }
            }
        }
    }
}