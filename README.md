# Motorcycle Notification Display (ESP32 + BLE)

## Description
A small embedded system that displays incoming calls and selected smartphone notifications (e.g. WhatsApp) on a display mounted on a motorcycle.

The system uses an Android app to capture notifications and sends them via Bluetooth Low Energy (BLE) to an ESP-C3-Super-Mini device, which displays the information in real-time.

## Features
- Display incoming calls (name / number)
- Display selected notifications (WhatsApp, etc.)
- Works while phone is locked
- BLE communication between phone and ESP32
- Real-time updates

## Architecture
Phone (Android App)
→ NotificationListenerService
→ BLE
→ ESP-C3-Super-Mini
→ Display

## Tech Stack
- ESP-C3-Super-Mini (Arduino / NimBLE)
- Android App (Kotlin)
- BLE communication
- OLED/TFT Display

## Future Improvements
- Notification filtering in app
- Auto reconnect BLE
- Vibration / LED alerts
- Connection to bike power system (ignition) 
