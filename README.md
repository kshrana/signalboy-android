# Signalboy library for Android
This library provides a simple client for connecting and interfacing with a Signalboy-device.

Some key features are:
* Auto-connection handling
* Clock-synchronization training (handled automatically by the service)

## TODO
Signalboy won't function right now (s. [Android Permissions](#android-permissions)). Its function
will be restored by completing the following tasks:
- [ ] Implement CompanionDeviceManager API as alternative discovery strategy
  - [x] Implement check or take some precautions to ensure for the 
    uses-feature `PackageManager#FEATURE_COMPANION_DEVICE_SETUP` declaration, that 
    the Companion Manager API expects.
  - [ ] Public method for clearing associations made by Signalboy
  - ~~[ ] Review use of deprecated methods in CompanionDeviceManager API implementation~~
- [ ] Convert SignalboyService to unbound-service
  - [ ] Implement communication via BroadcastReceiver
  - [ ] Make sure that SignalboyService is properly kept alive (e.g. when UnityPlayerActivity is destroyed)

## Installation
The latest release is provided as a local Maven repository. Download it from [Releases](https://github.com/kshrana/signalboy-android/releases/latest) and extract the contents to a location accessible as a Maven local repository (e.g. `~/.m2/repository/`). Finally declare the dependency in your `build.gradle`:
```groovy
implementation 'de.kishorrana:signalboy_android:1.0.0'  // Make sure to reference the latest release.
```

## Android Permissions
Initially location permissions (`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`)
were requested to scan for nearby Bluetooth devices. The Meta Quest Store forbids use of
[these permissions](https://developer.oculus.com/resources/vrc-quest-security-2/#prohibited-android-permissions).  
In order to allow depending apps to be published, version `1.0.3` removes these permissions
from the `AndroidManifest`. As such Signalboy will fail to request the Runtime Permissions
required for the operation of its implementation (and would fail to find any Bluetooth devices
during the scan if permission check would be skipped).

## Usage
For your convenience `SignalboyService` (implemented as an
[Android Bound-Service](https://developer.android.com/guide/components/bound-services))
is supplied with an easy-to-use interface enabling you to:
* Signal events

Once `SignalboyService` is started (s. 
[Android docs for binding to a
bound service](https://developer.android.com/guide/components/bound-services#Binding)
) it automatically tries to connect to the Signalboy-device. (The required Bluetooth-connection and 
clock-synchronization training are automatically managed by the service in the background)

Once the service established the Bluetooth-connection it is able to indicate events for emission
by the Signalboy-device.

## Clock-synchronization
To reduce jitter the Signalboy device will delay emitting signals of received events with a
normalization-delay. On the basis of this fixed delay we can ensure that the delta between the
signals emitted by the Signalboy-device match the delta between events as indicated to the
client (at the cost of the introduced constant latency).

For each event indicated to the client, the client will create a timestamp that will indicate the
timing-information to the Signalboy-device. For these timestamps to work the client on the 
handheld device will need to share a synced clock with the Signalboy-device.

This library automatically performs the needed clock-synchronization training on a regular basis
in the background.

Should the synchronization process fail, the client automatically switches to a fallback-method
that will reduce the accuracy of the delta between the events as signaled by the Signalboy-device.

## Sample App
This project contains a sample Android app that demos the capabilities and the usage of
`SignalboyService`.

## Configuration
The following values can be customized on initialization of the service by passing a custom
`SignalboyService.Configuration`-instance when binding the `SignalboyService` (passed as an Extra
on the Android Intent):
| Property                 | Description                                                                                                     | Default value |
|--------------------------|-----------------------------------------------------------------------------------------------------------------|---------------|
|   `normalizationDelay`   | The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed. In milliseconds.    | 100           |
| `isAutoReconnectEnabled` | If `true`, the service will automatically try to reconnect if connection to the Signalboy Device has been lost. | true          |
|                          |                                                                                                                 |               |


### Normalization delay
The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed. In milliseconds.

**Background**: This fixed-delay is utilized to normalize the delay caused by 
network-latency (Bluetooth). In order to produce the actual event-times, the (third-party) receiving
system will have to subtract the specified Normalization-Delay from the timestamps of the received
electronic TTL-events.

## Releasing
1. Bump the library version variable `libraryVersion` of the library's [build.gradle](./signalboy_android/build.gradle) file.
2. Build the release using `./gradlew :signalboy_android:generateRepo` which performs the following:
  * Builds the library .aar (`release`-configuration)
  * Publishes the produced .aar to a local Maven repository (located at `signalboy_android/build/repo`)
  * Produces a zip containing the contents of the local Maven repository (located at `signalboy_android/build/distributions/signalboy_android-maven.zip`)

```bash
./gradlew :signalboy_android:generateRepo
```
