# ESP32 Motorcycle Notification & TPMS Display Firmware

Firmware for an ESP32-C3 Super Mini used as the display controller for a motorcycle notification and TPMS system.

The ESP32 receives data from an Android application via Bluetooth Low Energy (BLE), processes the received messages, and displays relevant information on a TFT screen.

The Android app acts as the communication bridge between the smartphone, TPMS sensors, and ESP32 display.

## Features

- BLE communication with Android application
- Incoming call display
- WhatsApp sender display
- Automatic text scrolling for long names
- Front tire pressure display
- Rear tire pressure display
- Percentage-based TPMS warning logic
- Configurable front and rear reference pressures
- Persistent TPMS settings
- Phone battery level display
- BLE connection status indicator
- Automatic BLE advertising restart after disconnect
- Wi-Fi credential storage
- OTA firmware updates over Wi-Fi
- TFT display output
- TPMS temperature data reception
- TPMS sensor battery data reception

## Hardware

- ESP32-C3 Super Mini
- TFT display
- Motorcycle power supply
- Android smartphone
- BLE TPMS sensors

## Software Stack

- C++
- Arduino Framework
- NimBLE
- TFT_eSPI
- WiFi
- ArduinoOTA
- Preferences

## System Architecture

```text
Front TPMS Sensor ─────┐
                       │ BLE
Rear TPMS Sensor ──────┤
                       ▼
                 Android App
                       │
                       │ BLE
                       ▼
             ESP32-C3 Super Mini
                       │
                       ▼
                  TFT Display
