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
NimBLEAdvertisedDevice *findTpmsDevice(const char *targetMac);

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

struct TpmsData
{
    float pressureBar = 0.0f;
    float temperatureC = 0.0f;
    int batteryPercent = -1;
    bool connected = false;
};

float frontPressure = -1.0f;
float rearPressure = -1.0f;
TpmsData frontTpms;
TpmsData rearTpms;

static NimBLEClient *frontClient = nullptr;
static NimBLEClient *rearClient = nullptr;
const char *FRONT_SENSOR_MAC = "08:35:1B:02:43:CC";
const char *REAR_SENSOR_MAC = "08:35:1B:02:43:73";

int mobileBatLvl = -1;

bool deviceConnected = false;
void handleNotification(const String &message);
void drawIdleScreen();
void showScrollingOrCenteredText(const String &text, uint16_t color);
void handleTpmsPacket(uint8_t *data, size_t len, TpmsData &tpms);
void handleBatteryPacket(uint8_t *data, size_t len, TpmsData &tpms);
void tpmsNotifyCallback(
    NimBLERemoteCharacteristic *characteristic,
    uint8_t *data,
    size_t length,
    bool isNotify);
bool connectTpmsSensor(
    const char *mac,
    NimBLEClient *&client);
NimBLEAdvertisedDevice *findTpmsDevice(const char *targetMac)
{
    Serial.println();
    Serial.println("========== TPMS SCAN START ==========");
    Serial.print("Target MAC: ");
    Serial.println(targetMac);
    Serial.print("Phone connected: ");
    Serial.println(deviceConnected ? "YES" : "NO");

    bool wasConnectedToPhone = deviceConnected;

    if (!wasConnectedToPhone)
    {
        Serial.println("Stopping ESP32 advertising before scan...");
        NimBLEDevice::stopAdvertising();
        delay(1000);
    }
    else
    {
        Serial.println("Phone is connected. Scan may be weaker.");
    }

    NimBLEScan *scan = NimBLEDevice::getScan();

    scan->setActiveScan(true);
    scan->setInterval(160);
    scan->setWindow(150);

    Serial.println("Scan settings:");
    Serial.println("Active scan: true");
    Serial.println("Interval: 160");
    Serial.println("Window: 150");
    Serial.println("Total scan time: up to 60 seconds");

    tft.fillScreen(TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.drawString("TPMS SCAN", 120, 45, 4);
    tft.drawString(targetMac, 120, 85, 2);
    tft.drawString("MOVE SENSOR", 120, 125, 2);

    for (int round = 1; round <= 6; round++)
    {
        Serial.println();
        Serial.print("----- Scan round ");
        Serial.print(round);
        Serial.println("/6 -----");

        tft.fillScreen(TFT_BLACK);
        tft.setTextDatum(MC_DATUM);
        tft.setTextColor(TFT_WHITE, TFT_BLACK);
        tft.drawString("SCAN " + String(round) + "/6", 120, 50, 4);
        tft.drawString("WAIT 10 SEC", 120, 95, 2);
        tft.drawString("MOVE TPMS", 120, 130, 2);

        NimBLEScanResults results = scan->getResults(10, false);

        int count = results.getCount();

        Serial.print("BLE devices found this round: ");
        Serial.println(count);

        tft.fillScreen(TFT_BLACK);
        tft.setTextDatum(MC_DATUM);
        tft.setTextColor(TFT_WHITE, TFT_BLACK);
        tft.drawString("ROUND " + String(round), 120, 40, 4);
        tft.drawString("FOUND: " + String(count), 120, 85, 4);

        delay(1000);

        for (int i = 0; i < count; i++)
        {
            const NimBLEAdvertisedDevice *device = results.getDevice(i);

            String foundMac = device->getAddress().toString().c_str();
            String name = device->getName().c_str();

            bool macMatch = foundMac.equalsIgnoreCase(targetMac);
            bool nameMatch = false;

            if (String(targetMac).endsWith(":CC"))
            {
                nameMatch = name.indexOf("0243CC") >= 0 || name.indexOf("43CC") >= 0 || name.indexOf("RT") >= 0;
            }
            else if (String(targetMac).endsWith(":73"))
            {
                nameMatch = name.indexOf("024373") >= 0 || name.indexOf("4373") >= 0 || name.indexOf("RT") >= 0;
            }

            Serial.println();
            Serial.println("---------- BLE DEVICE ----------");
            Serial.print("Round: ");
            Serial.println(round);

            Serial.print("Index: ");
            Serial.print(i + 1);
            Serial.print("/");
            Serial.println(count);

            Serial.print("MAC: ");
            Serial.println(foundMac);

            Serial.print("Target MAC match: ");
            Serial.println(macMatch ? "YES" : "NO");

            Serial.print("Name match: ");
            Serial.println(nameMatch ? "YES" : "NO");

            Serial.print("RSSI: ");
            Serial.println(device->getRSSI());

            Serial.print("Name: ");
            if (name.length() > 0)
            {
                Serial.println(name);
            }
            else
            {
                Serial.println("<no name>");
            }

            Serial.print("Address type: ");
            Serial.println((int)device->getAddress().getType());

            Serial.print("Has service UUID: ");
            Serial.println(device->haveServiceUUID() ? "YES" : "NO");

            if (device->haveServiceUUID())
            {
                Serial.print("Service UUID: ");
                Serial.println(device->getServiceUUID().toString().c_str());
            }

            Serial.print("Has manufacturer data: ");
            Serial.println(device->haveManufacturerData() ? "YES" : "NO");

            if (device->haveManufacturerData())
            {
                std::string manufacturerData = device->getManufacturerData();

                Serial.print("Manufacturer data length: ");
                Serial.println(manufacturerData.length());

                Serial.print("Manufacturer HEX: ");

                for (size_t j = 0; j < manufacturerData.length(); j++)
                {
                    uint8_t b = (uint8_t)manufacturerData[j];

                    if (b < 16)
                    {
                        Serial.print("0");
                    }

                    Serial.print(b, HEX);
                    Serial.print(" ");
                }

                Serial.println();
            }

            Serial.print("Has service data: ");
            Serial.println(device->haveServiceData() ? "YES" : "NO");

            if (device->haveServiceData())
            {
                std::string serviceData = device->getServiceData();

                Serial.print("Service data length: ");
                Serial.println(serviceData.length());

                Serial.print("Service data HEX: ");

                for (size_t j = 0; j < serviceData.length(); j++)
                {
                    uint8_t b = (uint8_t)serviceData[j];

                    if (b < 16)
                    {
                        Serial.print("0");
                    }

                    Serial.print(b, HEX);
                    Serial.print(" ");
                }

                Serial.println();
            }

            tft.fillScreen(TFT_BLACK);
            tft.setTextDatum(MC_DATUM);
            tft.setTextColor((macMatch || nameMatch) ? TFT_GREEN : TFT_YELLOW, TFT_BLACK);

            if (macMatch)
            {
                tft.drawString("MAC MATCH", 120, 25, 4);
            }
            else if (nameMatch)
            {
                tft.drawString("NAME MATCH", 120, 25, 4);
            }
            else
            {
                tft.drawString("NO MATCH", 120, 25, 4);
            }

            tft.setTextColor(TFT_WHITE, TFT_BLACK);
            tft.drawString(foundMac, 120, 70, 2);
            tft.drawString("RSSI " + String(device->getRSSI()), 120, 105, 2);

            if (name.length() > 0)
            {
                tft.drawString(name, 120, 140, 2);
            }
            else
            {
                tft.drawString("NO NAME", 120, 140, 2);
            }

            delay(1500);

            if (macMatch || nameMatch)
            {
                Serial.println();
                Serial.println("Target TPMS found.");
                Serial.print("Found by: ");
                Serial.println(macMatch ? "MAC" : "NAME");
                Serial.println("Returning advertised device copy for connection...");

                tft.fillScreen(TFT_BLACK);
                tft.setTextDatum(MC_DATUM);
                tft.setTextColor(TFT_GREEN, TFT_BLACK);
                tft.drawString("TPMS FOUND", 120, 80, 4);
                tft.drawString(foundMac, 120, 120, 2);

                NimBLEAdvertisedDevice *copy = new NimBLEAdvertisedDevice(*device);

                scan->clearResults();

                Serial.println("========== TPMS SCAN END: FOUND ==========");
                return copy;
            }
        }

        scan->clearResults();

        Serial.println("Target not found in this round.");

        tft.fillScreen(TFT_BLACK);
        tft.setTextDatum(MC_DATUM);
        tft.setTextColor(TFT_YELLOW, TFT_BLACK);
        tft.drawString("NO TPMS", 120, 60, 4);
        tft.drawString("ROUND " + String(round) + "/6", 120, 105, 2);

        delay(1000);
    }

    if (!wasConnectedToPhone)
    {
        Serial.println("Restarting ESP32 advertising after scan...");
        NimBLEDevice::startAdvertising();
    }

    Serial.println();
    Serial.println("Target TPMS was NOT found after 60 seconds.");
    Serial.println("========== TPMS SCAN END: NOT FOUND ==========");

    tft.fillScreen(TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(TFT_RED, TFT_BLACK);
    tft.drawString("NOT FOUND", 120, 60, 4);
    tft.drawString(targetMac, 120, 105, 2);
    tft.drawString("60 SEC FAIL", 120, 140, 2);

    delay(3000);

    return nullptr;
}

void tpmsNotifyCallback(
    NimBLERemoteCharacteristic *characteristic,
    uint8_t *data,
    size_t length,
    bool isNotify)
{
    Serial.println();
    Serial.println("========== TPMS NOTIFY ==========");

    Serial.print("Notify: ");
    Serial.println(isNotify ? "YES" : "NO");

    Serial.print("Length: ");
    Serial.println(length);

    Serial.print("Raw HEX: ");

    for (size_t i = 0; i < length; i++)
    {
        if (data[i] < 16)
        {
            Serial.print("0");
        }

        Serial.print(data[i], HEX);
        Serial.print(" ");
    }

    Serial.println();

    if (length < 2)
    {
        Serial.println("Packet too short. Ignoring.");
        Serial.println("========== TPMS NOTIFY END ==========");
        return;
    }

    float temperature = data[0] - 40.0f;
    float pressure = data[1] / 40.0f;

    String mac =
        characteristic->getRemoteService()
            ->getClient()
            ->getPeerAddress()
            .toString()
            .c_str();

    Serial.print("Peer MAC: ");
    Serial.println(mac);

    Serial.print("Temperature: ");
    Serial.print(temperature);
    Serial.println(" C");

    Serial.print("Pressure: ");
    Serial.print(pressure);
    Serial.println(" bar");

    if (mac.equalsIgnoreCase(FRONT_SENSOR_MAC))
    {
        Serial.println("Sensor: FRONT");

        frontPressure = pressure;
        frontTpms.temperatureC = temperature;
        frontTpms.pressureBar = pressure;
        frontTpms.connected = true;
    }
    else if (mac.equalsIgnoreCase(REAR_SENSOR_MAC))
    {
        Serial.println("Sensor: REAR");

        rearPressure = pressure;
        rearTpms.temperatureC = temperature;
        rearTpms.pressureBar = pressure;
        rearTpms.connected = true;
    }
    else
    {
        Serial.println("Sensor: UNKNOWN MAC");
    }

    Serial.println("========== TPMS NOTIFY END ==========");

    if (!showingNotification && !wifiConnected)
    {
        drawIdleScreen();
    }
}
bool connectTpmsSensor(
    const char *mac,
    NimBLEClient *&client)
{
    Serial.println();
    Serial.println("========== TPMS CONNECT START ==========");
    Serial.print("Target MAC: ");
    Serial.println(mac);

    tft.fillScreen(TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.drawString("CONNECTING", 120, 80, 2);
    tft.drawString(mac, 120, 120, 2);

    NimBLEAdvertisedDevice *tpmsDevice = findTpmsDevice(mac);

    if (tpmsDevice == nullptr)
    {
        Serial.println("Result: target was not found during scan.");
        Serial.println("========== TPMS CONNECT END: NOT FOUND ==========");

        tft.fillScreen(TFT_BLACK);
        tft.setTextDatum(MC_DATUM);
        tft.setTextColor(TFT_RED, TFT_BLACK);
        tft.drawString("NOT FOUND", 120, 80, 4);
        tft.drawString(mac, 120, 120, 2);

        delay(3000);

        return false;
    }

    Serial.println("Creating BLE client...");
    client = NimBLEDevice::createClient();
    client->setConnectTimeout(10);

    Serial.println("Client created.");
    Serial.println("Client connect timeout: 10 seconds");

    Serial.print("Connecting to discovered TPMS: ");
    Serial.println(mac);

    bool connected = client->connect(tpmsDevice);

    Serial.print("Connect result: ");
    Serial.println(connected ? "SUCCESS" : "FAILED");

    if (!connected)
    {
        Serial.println("Connection failed.");
        Serial.print("Client still connected: ");
        Serial.println(client->isConnected() ? "YES" : "NO");

        delete tpmsDevice;

        if (client)
        {
            Serial.println("Deleting failed BLE client...");
            NimBLEDevice::deleteClient(client);
            client = nullptr;
        }

        NimBLEDevice::startAdvertising();

        tft.fillScreen(TFT_BLACK);
        tft.setTextDatum(MC_DATUM);
        tft.setTextColor(TFT_RED, TFT_BLACK);
        tft.drawString("CONNECT FAIL", 120, 55, 4);
        tft.drawString(mac, 120, 95, 2);
        tft.drawString("SEE SERIAL", 120, 135, 2);

        delay(3000);

        Serial.println("========== TPMS CONNECT END: FAILED ==========");

        return false;
    }

    delete tpmsDevice;

    Serial.println("Connected to TPMS.");

    tft.fillScreen(TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(TFT_GREEN, TFT_BLACK);
    tft.drawString("CONNECTED", 120, 80, 4);
    tft.drawString(mac, 120, 120, 2);

    delay(1000);

    Serial.println("Searching service FFD0...");
    auto service = client->getService("FFD0");

    if (!service)
    {
        Serial.println("Service FFD0 not found.");
        Serial.println("Trying full UUID 0000ffd0-0000-1000-8000-00805f9b34fb...");

        service = client->getService("0000ffd0-0000-1000-8000-00805f9b34fb");
    }

    if (!service)
    {
        Serial.println("Service FFD0 not found with both formats.");

        tft.fillScreen(TFT_BLACK);
        tft.setTextColor(TFT_RED, TFT_BLACK);
        tft.drawString("NO FFD0", 120, 120, 4);

        delay(3000);

        Serial.println("========== TPMS CONNECT END: NO SERVICE ==========");

        return false;
    }

    Serial.println("Service FFD0 found.");

    Serial.println("Searching characteristic FFD1...");
    auto characteristic = service->getCharacteristic("FFD1");

    if (!characteristic)
    {
        Serial.println("Characteristic FFD1 not found.");
        Serial.println("Trying full UUID 0000ffd1-0000-1000-8000-00805f9b34fb...");

        characteristic = service->getCharacteristic("0000ffd1-0000-1000-8000-00805f9b34fb");
    }

    if (!characteristic)
    {
        Serial.println("Characteristic FFD1 not found with both formats.");

        tft.fillScreen(TFT_BLACK);
        tft.setTextColor(TFT_RED, TFT_BLACK);
        tft.drawString("NO FFD1", 120, 120, 4);

        delay(3000);

        Serial.println("========== TPMS CONNECT END: NO CHARACTERISTIC ==========");

        return false;
    }

    Serial.println("Characteristic FFD1 found.");

    Serial.print("FFD1 canRead: ");
    Serial.println(characteristic->canRead() ? "YES" : "NO");

    Serial.print("FFD1 canNotify: ");
    Serial.println(characteristic->canNotify() ? "YES" : "NO");

    Serial.print("FFD1 canIndicate: ");
    Serial.println(characteristic->canIndicate() ? "YES" : "NO");

    Serial.print("FFD1 canWrite: ");
    Serial.println(characteristic->canWrite() ? "YES" : "NO");

    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_GREEN, TFT_BLACK);
    tft.drawString("FFD1 FOUND", 120, 120, 4);

    delay(1000);

    if (characteristic->canNotify())
    {
        Serial.println("Subscribing to FFD1 notifications...");

        bool subOk = characteristic->subscribe(true, tpmsNotifyCallback);

        Serial.print("Subscribe result: ");
        Serial.println(subOk ? "SUCCESS" : "FAILED");

        tft.fillScreen(TFT_BLACK);
        tft.setTextColor(subOk ? TFT_GREEN : TFT_RED, TFT_BLACK);
        tft.drawString(subOk ? "SUBSCRIBED" : "SUB FAIL", 120, 120, 4);

        delay(1000);
    }
    else
    {
        Serial.println("FFD1 does not support notify.");
    }

    if (characteristic->canRead())
    {
        Serial.println("Reading FFD1 value...");

        auto value = characteristic->readValue();

        Serial.print("Read length: ");
        Serial.println(value.size());

        Serial.print("Read HEX: ");

        for (size_t i = 0; i < value.size(); i++)
        {
            uint8_t b = (uint8_t)value[i];

            if (b < 16)
            {
                Serial.print("0");
            }

            Serial.print(b, HEX);
            Serial.print(" ");
        }

        Serial.println();

        tft.fillScreen(TFT_BLACK);
        tft.setTextColor(TFT_WHITE, TFT_BLACK);

        if (value.size() >= 2)
        {
            uint8_t tempRaw = (uint8_t)value[0];
            uint8_t pressureRaw = (uint8_t)value[1];

            float temperature = tempRaw - 40.0f;
            float pressure = pressureRaw / 40.0f;

            Serial.print("Temp raw: ");
            Serial.println(tempRaw);

            Serial.print("Pressure raw: ");
            Serial.println(pressureRaw);

            Serial.print("Temperature decoded: ");
            Serial.print(temperature);
            Serial.println(" C");

            Serial.print("Pressure decoded: ");
            Serial.print(pressure);
            Serial.println(" bar");

            if (String(mac).equalsIgnoreCase(FRONT_SENSOR_MAC))
            {
                frontPressure = pressure;
                frontTpms.temperatureC = temperature;
                frontTpms.pressureBar = pressure;
                frontTpms.connected = true;
            }
            else if (String(mac).equalsIgnoreCase(REAR_SENSOR_MAC))
            {
                rearPressure = pressure;
                rearTpms.temperatureC = temperature;
                rearTpms.pressureBar = pressure;
                rearTpms.connected = true;
            }

            tft.drawString("PRESSURE", 120, 90, 2);
            tft.drawString(String(pressure, 1) + " bar", 120, 130, 4);

            delay(3000);
        }
        else
        {
            Serial.println("Read value too short or empty.");

            tft.drawString("NO DATA", 120, 120, 4);

            delay(3000);
        }
    }
    else
    {
        Serial.println("FFD1 does not support read.");
    }

    NimBLEDevice::startAdvertising();

    Serial.println("========== TPMS CONNECT END: SUCCESS ==========");

    return true;
}

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

void handleTpmsPacket(uint8_t *data, size_t len, TpmsData &tpms)
{
    if (len < 2)
        return;

    tpms.temperatureC = data[0] - 40.0f;
    tpms.pressureBar = data[1] / 40.0f;
}

void handleBatteryPacket(uint8_t *data, size_t len, TpmsData &tpms)
{
    if (len < 1)
        return;

    tpms.batteryPercent = data[0];
}

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
    tft.drawLine(0, 60, 239, 60, TFT_WHITE);
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
    delay(5000);

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
    Serial.println();
    Serial.println("Setting BLE security...");
    NimBLEDevice::setSecurityAuth(true, false, true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
    Serial.println("BLE security set.");

    bool frontOk = connectTpmsSensor(FRONT_SENSOR_MAC, frontClient);

    Serial.print("Front TPMS result: ");
    Serial.println(frontOk ? "OK" : "FAILED");

    // Test rear later
    // bool rearOk = connectTpmsSensor(REAR_SENSOR_MAC, rearClient);
    // Serial.print("Rear TPMS result: ");
    // Serial.println(rearOk ? "OK" : "FAILED");

    NimBLEDevice::startAdvertising();

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