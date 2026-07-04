# MotoNotify Android App

Android companion application for the **Motorcycle Notification & TPMS Display** project.

The app acts as the central communication bridge between the smartphone, BLE TPMS sensors, and the ESP32-C3 motorcycle display.

It captures selected smartphone events, maintains the BLE connection to the ESP32, reads tire pressure and temperature data from RiDEET Pro TPMS sensors, and forwards relevant information to the motorcycle display.

---

## Features

- BLE communication with the ESP32 display
- Automatic ESP32 discovery
- Automatic BLE connection and reconnection
- Background BLE operation through a foreground service
- Operation while the phone is locked
- WhatsApp notification forwarding
- Incoming call forwarding
- Contact name lookup for incoming calls
- Phone battery level reporting
- RiDEET Pro TPMS sensor communication
- Front and rear tire pressure reading
- Front and rear tire temperature reading
- Periodic TPMS polling
- Automatic TPMS data forwarding to the ESP32
- OTA preparation over BLE and Wi-Fi
- ESP32 IP address reception
- BLE connection state monitoring
- TPMS status monitoring
- Internal application logs
- Manual BLE message sending for testing
- Jetpack Compose user interface

---

## System Architecture

```text
                         Android Phone
                ┌─────────────────────────────┐
                │                             │
WhatsApp ──────►│ NotificationListenerService │
                │                             │
Incoming Call ─►│ CallStateReceiver           │
                │                             │
Phone Battery ─►│ PhoneBatteryMonitor         │
                │                             │
                │ BleManager                  │
                │ ForegroundBleService        │
                └──────────────┬──────────────┘
                               │
                               │ BLE
                               ▼
                    ESP32 Motorcycle Display


Front TPMS Sensor ─────┐
                       │ BLE
                       ▼
                TpmsBleManager
                       ▲
                       │ BLE
Rear TPMS Sensor ──────┘
                       │
                       │ Forward decoded data
                       ▼
                   BleManager
                       │
                       │ BLE
                       ▼
              ESP32 Motorcycle Display
```

The Android app acts as the communication hub.

The ESP32 does not directly connect to the TPMS sensors. Instead, the app reads and decodes the TPMS values and forwards them through the existing phone-to-ESP32 BLE connection.

---

## Tech Stack

### Android

- Kotlin
- Android SDK
- Jetpack Compose
- Material 3
- Kotlin Coroutines
- StateFlow
- Android Foreground Service
- `NotificationListenerService`
- `BroadcastReceiver`

### BLE

- Bluetooth Low Energy
- Kable BLE library
- Custom text-based message protocol

---

## Main Components

```text
com.example.motonotify1
├── MainActivity
│
├── bleManager
│   ├── BleManager
│   ├── BleManagerProvider
│   ├── TpmsBleManager
│   └── TpmsBleManagerProvider
│
├── service
│   ├── battery
│   │   ├── BatteryReporter
│   │   └── PhoneBatteryMonitor
│   │
│   ├── call
│   │   └── CallStateReceiver
│   │
│   ├── foregroundService
│   │   ├── ForegroundBleService
│   │   └── MotoForegroundService
│   │
│   └── notification
│       └── NotificationService
│
└── ui
    └── theme
```

---

## ESP32 BLE Communication

The app communicates with the motorcycle display through a custom BLE service.

### Target Device Name

```text
MotoNotifyDisplay_GL
```

### Service UUID

```text
7e400001-b5a3-f393-e0a9-e50e24dcca9e
```

### Characteristic UUID

```text
7e400002-b5a3-f393-e0a9-e50e24dcca9e
```

The characteristic is used for:

- Sending commands to the ESP32
- Forwarding notifications
- Forwarding incoming calls
- Sending phone battery information
- Forwarding TPMS data
- Receiving ESP32 status messages
- Receiving the ESP32 IP address

---

## BLE Connection Management

`BleManager` handles the primary BLE connection between the Android phone and the ESP32 display.

### Connection States

```text
Disconnected
Scanning
Connecting
Connected
Reconnecting
```

### Connection Flow

```text
Start BLE Operations
        │
        ▼
Scan for ESP32
        │
        ▼
Find MotoNotifyDisplay_GL
        │
        ▼
Connect
        │
        ▼
Start Notification Observer
        │
        ▼
Connected
        │
        ├── Connection remains active
        │
        └── Connection lost
                  │
                  ▼
             Reconnecting
                  │
                  ▼
          Last Known Device
```

The app stores the last known ESP32 device and attempts to reconnect automatically after connection loss.

### Current Reconnect Timing

| Setting | Value |
| --- | --- |
| Connection timeout | `15 seconds` |
| Disconnect debounce | `1.5 seconds` |
| Initial reconnect delay | `2 seconds` |
| Reconnect interval | `8 seconds` |
| Reconnect attempt window | `25 seconds` |
| Reconnect polling interval | `2 seconds` |

A manual disconnect prevents automatic reconnection until BLE operations are started again.

---

## Foreground BLE Service

`ForegroundBleService` manages long-running communication tasks.

It owns the coroutine scope used for:

- ESP32 BLE connection handling
- Automatic reconnection
- Phone battery reporting
- Periodic TPMS reads

The service is started as a foreground service and uses:

```text
START_STICKY
```

This allows BLE operations to continue while:

- The app is in the background
- The phone is locked
- The visible activity is not open

### Foreground Notification States

Examples include:

```text
Starting…
Connecting…
Connected
Disconnected
Reconnecting…
OTA ready
```

---

## WhatsApp Notifications

The app uses Android's `NotificationListenerService` to capture selected notifications.

Currently supported package:

```text
com.whatsapp
```

When a WhatsApp notification is received, the app extracts:

- Sender or notification title
- Message text

The message is forwarded to the ESP32 using:

```text
WA:<sender>:<message>
```

Example:

```text
WA:John Doe:Hello
```

### Duplicate Protection

Identical WhatsApp messages received within approximately:

```text
3 seconds
```

are ignored.

---

## Incoming Calls

Incoming calls are handled through `CallStateReceiver`.

When the phone enters the ringing state, the app:

1. Reads the incoming phone number
2. Searches the Android contacts database
3. Uses the contact name when available
4. Falls back to the phone number
5. Sends the caller information to the ESP32

### Message Format

```text
C:<caller>
```

Example with contact name:

```text
C:John Doe
```

Example without a matching contact:

```text
C:+43123456789
```

### Duplicate Protection

Identical incoming-call messages received within approximately:

```text
5 seconds
```

are ignored.

---

## Phone Battery Monitoring

The app monitors the Android phone battery level and forwards it to the ESP32.

### Message Format

```text
BAT:<percent>
```

Example:

```text
BAT:58
```

Battery information is sent:

- Immediately after BLE connection when available
- When the battery percentage changes
- Periodically while connected

The periodic check interval is:

```text
5 minutes
```

Battery values are limited to:

```text
0 - 100%
```

---

## TPMS Integration

The application communicates directly with two RiDEET Pro BLE TPMS sensors.

The sensors are read sequentially:

```text
Front Sensor
    │
    ▼
Scan
    │
    ▼
Connect
    │
    ▼
Read Pressure and Temperature
    │
    ▼
Disconnect and Clean Up
    │
    ▼
Short Delay
    │
    ▼
Rear Sensor
    │
    ▼
Scan
    │
    ▼
Connect
    │
    ▼
Read Pressure and Temperature
    │
    ▼
Disconnect and Clean Up
```

A TPMS read cycle is only performed when:

- Required BLE permissions are granted
- Bluetooth is enabled and available
- The ESP32 display is connected

---

## TPMS Sensor Configuration

### Front Sensor

```text
08:35:1B:02:43:CC
```

### Rear Sensor

```text
08:35:1B:02:43:73
```

The sensor addresses are currently configured directly in `TpmsBleManager`.

---

## TPMS BLE Configuration

### Service UUID

```text
0000ffd0-0000-1000-8000-00805f9b34fb
```

### Characteristic UUID

```text
0000ffd1-0000-1000-8000-00805f9b34fb
```

The application reads TPMS values from characteristic:

```text
FFD1
```

---

## TPMS Read Strategy

For each sensor, the app performs the following process:

```text
Scan for Sensor
      │
      ▼
Connect
      │
      ▼
Wait for Connection to Settle
      │
      ▼
Read FFD1
      │
      ├── Valid Payload
      │       │
      │       ▼
      │     Decode
      │
      └── Invalid or Failed
               │
               ▼
          Retry READ
               │
               └── Still Failed
                        │
                        ▼
                 Try NOTIFY Fallback
```

The implementation:

- Retries normal characteristic reads
- Ignores invalid payloads
- Uses BLE notifications as a fallback
- Disconnects and cleans up the TPMS GATT session after each sensor
- Keeps TPMS BLE sessions separate from the ESP32 connection

---

## TPMS Polling

A new TPMS read cycle starts approximately every:

```text
30 seconds
```

Each cycle reads:

1. Front sensor
2. Rear sensor

### Current Timing Configuration

| Setting | Value |
| --- | --- |
| Read cycle interval | `30 seconds` |
| Scan timeout | `15 seconds` |
| Connect timeout | `10 seconds` |
| Read timeout | `10 seconds` |
| Complete sensor timeout | `15 seconds` |
| Post-connect delay | `500 ms` |
| Read retry count | `3` |
| Retry delay | `400 ms` |
| Notify fallback timeout | `8 seconds` |
| Delay between sensors | `1 second` |
| Delay after GATT cleanup | `750 ms` |

---

## TPMS Data Decoding

The current RiDEET Pro FFD1 decoder uses the first two bytes of the payload.

### Temperature

```text
temperatureC = unsignedByte0 - 40
```

### Pressure

```text
pressureBar = unsignedByte1 / 40.0
```

Example payload:

```text
3D 4F
```

Decoded result:

```text
Temperature: 21 °C
Pressure:    1.975 bar
```

---

## TPMS Data Forwarding

After decoding the sensor data, the app forwards pressure and temperature separately to the ESP32.

### Front Pressure

```text
TPMSF:2.50
```

### Rear Pressure

```text
TPMSR:2.80
```

### Front Temperature

```text
TPMSFT:28
```

### Rear Temperature

```text
TPMSRT:31
```

Pressure values are formatted using a dot as the decimal separator.

---

## TPMS Status States

Each sensor can report one of the following states:

```text
Idle
Scanning
Connecting
Reading
Success
Failed
Disabled
```

The application UI displays:

- Front pressure
- Front temperature
- Front sensor state
- Front sensor errors
- Rear pressure
- Rear temperature
- Rear sensor state
- Rear sensor errors
- Current TPMS cycle state
- Last update time
- Bluetooth availability
- Permission state

---

## OTA Firmware Update Preparation

The app can prepare the ESP32 for an OTA firmware upload.

### Start OTA Mode

The app sends:

```text
OTA_START
```

The ESP32 then connects to its configured Wi-Fi network and sends its IP address back to the application.

Expected response:

```text
IP:<address>
```

Example:

```text
IP:192.168.1.100
```

### OTA States

```text
Idle
Waiting
Ready
TimedOut
Failed
```

### OTA Flow

```text
User Enters OTA Mode
          │
          ▼
Send OTA_START
          │
          ▼
Wait for ESP32 IP
          │
          ├── IP Received
          │       │
          │       ▼
          │     Ready
          │
          ├── Timeout
          │       │
          │       ▼
          │   TimedOut
          │
          └── Error
                  │
                  ▼
                Failed
```

The current OTA preparation timeout is:

```text
20 seconds
```

While OTA mode is active, normal manual and notification messages are blocked.

---

## ESP32 Responses

The app currently handles the following ESP32 response formats.

### IP Address

```text
IP:192.168.1.100
```

### Wi-Fi Status

```text
WiFi:<status>
```

### OTA Status

```text
OTA:<status>
```

### Wi-Fi Failure

```text
WIFI_FAILED
```

Received messages are also written to the internal application log.

---

## User Interface

The application interface is built with Jetpack Compose and Material 3.

The current UI displays:

- ESP32 connection state
- BLE scan state
- ESP32 IP address
- Wi-Fi status
- TPMS cycle status
- Front tire pressure
- Front tire temperature
- Front TPMS state
- Rear tire pressure
- Rear tire temperature
- Rear TPMS state
- OTA preparation status
- Discovered BLE devices
- Manual BLE message input
- Internal logs

The current interface is primarily intended for development, testing, and monitoring.

---

## Manual BLE Testing

The app includes a manual text input for sending custom messages to the ESP32.

Examples:

```text
TPMSF:2.50
```

```text
C:Test Caller
```

```text
WA:Test User:Hello
```

This is useful for:

- Firmware testing
- Display testing
- BLE protocol development
- Debugging

Manual message sending is disabled while OTA mode is active.

---

## Permissions

The application requests different BLE permissions depending on the Android version.

### Android 12 and Newer

```text
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
READ_PHONE_STATE
READ_CONTACTS
```

### Older Android Versions

```text
ACCESS_FINE_LOCATION
```

Additional Android configuration is required for:

- Notification listener access
- Incoming call handling
- Foreground services
- BLE permissions
- Contact lookup

These components must also be correctly declared in the Android manifest.

---

## Notification Access

WhatsApp forwarding requires notification access to be granted manually by the user.

The application uses:

```text
NotificationListenerService
```

Without notification access, WhatsApp notifications cannot be captured or forwarded.

---

## Background Operation

The main background communication system runs through:

```text
ForegroundBleService
```

The service manages:

- ESP32 BLE communication
- Automatic reconnection
- Phone battery reporting
- Periodic TPMS reads

This allows the system to continue operating when the main activity is not visible.

---

## State Management

The application uses Kotlin `StateFlow` for real-time state updates.

### BLE State

Tracks:

- Connection phase
- Scan state
- Discovered devices
- ESP32 IP address
- Wi-Fi status
- OTA state
- Internal logs

### TPMS State

Tracks:

- Front pressure
- Front temperature
- Front sensor status
- Front sensor errors
- Rear pressure
- Rear temperature
- Rear sensor status
- Rear sensor errors
- Last update time
- Cycle status
- Bluetooth availability
- Permission state

---

## Message Protocol

| Message | Direction | Description |
| --- | --- | --- |
| `WA:<sender>:<text>` | App → ESP32 | WhatsApp notification |
| `C:<caller>` | App → ESP32 | Incoming call |
| `BAT:<percent>` | App → ESP32 | Phone battery level |
| `TPMSF:<bar>` | App → ESP32 | Front tire pressure |
| `TPMSR:<bar>` | App → ESP32 | Rear tire pressure |
| `TPMSFT:<celsius>` | App → ESP32 | Front tire temperature |
| `TPMSRT:<celsius>` | App → ESP32 | Rear tire temperature |
| `OTA_START` | App → ESP32 | Start OTA preparation |
| `IP:<address>` | ESP32 → App | ESP32 IP address |
| `WiFi:<status>` | ESP32 → App | Wi-Fi status |
| `OTA:<status>` | ESP32 → App | OTA status |
| `WIFI_FAILED` | ESP32 → App | Wi-Fi connection failure |

---

## Current Limitations

- WhatsApp is currently the only explicitly filtered notification application
- TPMS sensor MAC addresses are hardcoded
- ESP32 device name is hardcoded
- ESP32 BLE UUIDs are hardcoded
- TPMS UUIDs are hardcoded
- TPMS sensors are read sequentially
- TPMS polling interval is fixed in code
- Notification filtering is not configurable through the UI
- The current Compose interface is development-oriented
- Incoming call functionality depends on Android permissions and platform behavior
- OTA firmware upload is performed externally; the app prepares the ESP32 and provides its IP address

---

## Future Improvements

- Configurable notification application filtering
- Per-app notification settings
- Configurable TPMS sensor addresses
- Configurable TPMS polling interval
- Multiple motorcycle profiles
- Multiple ESP32 display profiles
- User-configurable BLE device selection
- Improved production user interface
- TPMS pressure history
- TPMS temperature history
- Ride statistics
- Stale TPMS data detection
- Sensor timeout warnings
- Configurable notification display rules
- Extended OTA workflow
