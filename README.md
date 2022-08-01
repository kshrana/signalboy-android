# Signalboy library for Android
This library provides a simple client for connecting and interfacing with a Signalboy-device.

Some key features are:
* Auto-connection handling
* Clock-synchronization training (handled automatically by the service)

## Usage
For your convenience `SignalboyFacade` (implemented as an
[Android Bound-Service](https://developer.android.com/guide/components/bound-services))
is supplied with an easy-to-use interface enabling you to:
* Signal events

Once `SignalboyFacade` is started (s. 
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
`SignalboyFacade`.

## Customization
### Normalization delay
The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed.

**Background**: This fixed-delay is utilized to normalize the delay caused by 
network-latency (Bluetooth). In order to produce the actual event-times, the (third-party) receiving
system will have to subtract the specified Normalization-Delay from the timestamps of the received
electronic TTL-events.

**Customization**: S. `SignalboyFacade.Configuration`'s property `normalizationDelay`.