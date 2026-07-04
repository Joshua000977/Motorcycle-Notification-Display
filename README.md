# Motorcycle Notification & TPMS Display

A compact embedded system that displays incoming calls, selected smartphone notifications, and real-time tire pressure data on a display mounted on a motorcycle.

The system uses an Android app as the central communication hub. The app captures notifications and call information, receives TPMS sensor data via Bluetooth Low Energy (BLE), and forwards the relevant information to an ESP32-C3 Super Mini.

The ESP32 processes the received data and displays it in real time on a TFT display.

## Features

* Display incoming calls

  * Caller name
  * Phone number

* Display selected smartphone notifications

  * WhatsApp
  * Other supported applications

* Real-time TPMS monitoring

  * Front tire pressure
  * Rear tire pressure
  * Front tire temperature
  * Rear tire temperature

* Tire pressure warning states

  * Normal
  * Warning
  * Critical

* BLE communication between Android phone and ESP32

* BLE communication between TPMS sensors and Android app

* Works while the phone is locked

* Real-time data updates

* Automatic BLE reconnection

* Persistent configuration values

## Architecture

The Android app acts as the central bridge between the smartphone, TPMS sensors, and ESP32 display.

```text
                         Android Phone
                ┌────────────────────────────┐
                │                            │
Notifications ─►│ NotificationListenerService│
Calls ─────────►│ Call Information           │
                │                            │
TPMS Sensors ──►│ BLE TPMS Communication     │
                │                            │
                │ Data Processing            │
                │ Filtering                  │
                │ TPMS Value Handling        │
                └─────────────┬──────────────┘
                              │
                              │ BLE
                              ▼
                ┌────────────────────────────┐
                │ ESP32-C3 Super Mini        │
                │                            │
                │ BLE Receiver               │
                │ Message Parsing            │
                │ TPMS Warning Logic         │
                │ Display Logic              │
                └─────────────┬──────────────┘
                              │
                              ▼
                ┌────────────────────────────┐
                │ TFT / OLED Display         │
                │                            │
                │ Calls                      │
                │ Notifications              │
                │ Front Tire Pressure        │
                │ Rear Tire Pressure         │
                │ Tire Temperature           │
                │ Warning Status             │
                └────────────────────────────┘
```

## Data Flow

### Notifications and Calls

```text
Android Notification / Incoming Call
                │
                ▼
NotificationListenerService / Call Handling
                │
                ▼
Android App
                │
                │ BLE
                ▼
ESP32-C3 Super Mini
                │
                ▼
TFT / OLED Display
```

### TPMS Data

The TPMS sensors communicate directly with the Android app via BLE.

The Android app receives the sensor values, processes them, and forwards the relevant data to the ESP32 through the existing BLE connection.

```text
Front TPMS Sensor ─────┐
                       │
                       ▼
                 Android App
                       ▲
                       │
Rear TPMS Sensor ──────┘
                       │
                       │ BLE
                       ▼
             ESP32-C3 Super Mini
                       │
                       ▼
              TFT  Display
```

This architecture allows the Android app to manage the TPMS sensor connections while the ESP32 remains focused on receiving data and controlling the display.

## BLE Communication

The Android app sends data to the ESP32 using a custom BLE message protocol.

### Example Notification Messages

```text
CALL:John Doe
```

```text
NOTIFY:WhatsApp: Alex
```

```text
BAT:58
```

### Example TPMS Messages

Individual values can be transmitted separately:

```text
FRONT:2.30
REAR:2.20
FTEMP:28
RTEMP:31
```


### TPMS Message Fields

| Field | Description            |
| ----- | ---------------------- |
| `F`   | Front tire pressure    |
| `R`   | Rear tire pressure     |
| `FT`  | Front tire temperature |
| `RT`  | Rear tire temperature  |

### Tire Pressure Warning Logic

The pressure state is calculated relative to the configured reference pressure for each tire.

| Status        | Condition                                                                                       |
| ------------- | ----------------------------------------------------------------------------------------------- |
| Normal        | Pressure is within the configured warning limits                                                |
| Warning Low   | Pressure is below the reference pressure by more than `10%`                                     |
| Critical Low  | Front: more than `15%` below reference pressure; Rear: more than `20%` below reference pressure |
| Warning High  | Pressure is above the reference pressure by more than `15%`                                     |
| Critical High | Pressure is above the reference pressure by more than `25%`                                     |
| Critical      | Pressure is `<= 0 bar`                                                                          |

The default reference pressures are:

| Tire  | Reference Pressure |
| ----- | ------------------ |
| Front | `2.5 bar`          |
| Rear  | `2.8 bar`          |

With the default reference pressures, the resulting thresholds are:

| Tire  | Critical Low  | Warning Low  | Normal Range       | Warning High  | Critical High |
| ----- | ------------- | ------------ | ------------------ | ------------- | ------------- |
| Front | `< 2.125 bar` | `< 2.25 bar` | `2.25 - 2.875 bar` | `> 2.875 bar` | `> 3.125 bar` |
| Rear  | `< 2.24 bar`  | `< 2.52 bar` | `2.52 - 3.22 bar`  | `> 3.22 bar`  | `> 3.50 bar`  |

The front and rear reference pressures can be changed through the Android app and are stored persistently on the ESP32.


The display can use different colors for each state:

| Status   | Display Color |
| -------- | ------------- |
| Normal   | White         |
| Warning  | Yellow        |
| Critical | Red           |

Target pressures and warning thresholds can be configured through the Android app and stored persistently on the ESP32.

## Tech Stack

### Embedded System

* ESP32-C3 Super Mini
* Arduino Framework
* C++
* NimBLE
* TFT_eSPI
* TFT display
* Persistent configuration storage
* OTA firmware updates

### Android Application

* Kotlin
* Android BLE API
* `NotificationListenerService`
* Foreground Service
* `BluetoothGatt`
* Notification filtering
* Automatic BLE reconnection
* TPMS sensor communication
* TPMS data forwarding

### Communication

* Bluetooth Low Energy
* Custom BLE message protocol
* Real-time updates

## System Components

### ESP32 Display Unit

Responsible for:

* Receiving BLE messages from the Android app
* Parsing notification data
* Parsing TPMS data
* Evaluating pressure warning thresholds
* Updating the display
* Storing configuration values
* Handling OTA firmware updates

### Android Application

Responsible for:

* Capturing selected notifications
* Detecting incoming calls
* Maintaining the BLE connection to the ESP32
* Connecting to the TPMS sensors
* Reading tire pressure and temperature values
* Forwarding TPMS values to the ESP32
* Running BLE communication while the phone is locked
* Automatically reconnecting after connection loss

### TPMS Sensors

The motorcycle uses separate BLE TPMS sensors for the front and rear tires.

The sensors provide values such as:

* Tire pressure
* Tire temperature
* Sensor status
* Battery level, if available

## Features

* Display incoming calls

  * Caller name
  * Phone number

* Display selected smartphone notifications

  * WhatsApp
  * Other supported applications

* Real-time TPMS monitoring

  * Front tire pressure
  * Rear tire pressure
  * Front tire temperature
  * Rear tire temperature
  * Front TPMS sensor battery level
  * Rear TPMS sensor battery level

* Tire pressure warning states

  * Normal
  * Warning
  * Critical

* Smartphone battery level display

* BLE communication between Android phone and ESP32

* BLE communication between TPMS sensors and Android app

* Works while the phone is locked

* Real-time data updates

* Automatic BLE reconnection

* Persistent configuration values

* Bike power system integration

  * Powered from the motorcycle electrical system
  * Startup together with the bike ignition

* OTA firmware updates over Wi-Fi

## Future Improvements

* Configurable notification filtering
* Per-app notification settings
* Vibration alerts
* External warning LEDs
* Audible critical pressure alerts
* Configurable pressure thresholds in the Android app
* Multiple motorcycle profiles
* GPS navigation information
* TPMS pressure history
* TPMS temperature history
* Ride statistics

