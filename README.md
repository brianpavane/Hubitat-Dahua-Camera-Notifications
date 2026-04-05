# Hubitat Dahua Camera Notifications

Read-only Hubitat integration for Dahua NVRs that discovers recorder-connected cameras, creates one Hubitat child device per camera, and maps Dahua motion-related events into Hubitat automations.

Version `0.1.0` is an initial field-test release focused on:

- Dahua NVR discovery
- one child device per discovered camera channel
- event-stream based notifications
- user-selectable motion mapping
- per-camera enable and disable
- graceful reconnect after event stream drops
- normal logging of motion-related Dahua events

## Status

This is an early release intended for real-world testing against Dahua NVRs on a local network. The integration was designed from Dahua/Home Assistant behavior and Hubitat patterns, but it has not yet been validated against a broad matrix of Dahua firmware families.

Expect some model-specific differences during early testing. Extra debug logging is intentionally included in the riskiest areas so compatibility can be improved quickly.

## Repository Contents

- [DahuaNVRSyncApp.groovy](DahuaNVRSyncApp.groovy): Hubitat app
- [DahuaNVRDriver.groovy](DahuaNVRDriver.groovy): parent NVR driver
- [DahuaCameraDriver.groovy](DahuaCameraDriver.groovy): child camera driver
- [FUNCSPEC.md](FUNCSPEC.md): functional specification
- [ROADMAP.md](ROADMAP.md): roadmap
- [CHANGELOG.md](CHANGELOG.md): release history

## Features in 0.1.0

- Connect to a Dahua NVR over the local network
- Discover attached camera channels
- Create one Hubitat child device per selected camera
- Apply a global prefix or per-camera custom names
- Enable or disable cameras without a full re-sync
- Subscribe to the NVR event stream using Dahua HTTP CGI endpoints
- Route Dahua event notifications to the correct child device
- Map selected Dahua events to Hubitat `motion`
- Clear motion by stop event or inactivity timeout
- Reconnect automatically when the event stream drops
- Log motion-related events in normal logs

## Current v1 Scope

Version `0.1.0` is read-only. It does not:

- change camera or NVR settings
- enable or disable Dahua analytics
- control PTZ
- provide live streaming
- provide snapshots

## Dahua Event Types

The initial implementation is intended to recognize Dahua event types such as:

- `VideoMotion`
- `SmartMotionHuman`
- `SmartMotionVehicle`
- `CrossLineDetection`
- `CrossRegionDetection`
- `LeftDetection`
- `TakenAwayDetection`
- `WanderDetection`
- `RioterDetection`
- `ParkingDetection`
- `MoveDetection`
- `FaceDetection`
- `AlarmLocal`
- `VideoLoss`
- `VideoBlind`

Not every Dahua NVR or camera will emit all of these through the recorder.

## Installation

### Prerequisites

- A working Hubitat hub
- A Dahua NVR reachable from the Hubitat hub over the local network
- Valid NVR credentials
- Cameras connected through the NVR

### Direct Import URLs

Copy and paste these raw GitHub URLs into Hubitat when importing code manually.

- App: `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy`
- Parent driver: `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy`
- Child driver: `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy`

### Install the Drivers and App

1. In Hubitat, go to `Drivers Code`.
2. Create a new driver and paste in the contents of [DahuaNVRDriver.groovy](DahuaNVRDriver.groovy), or paste this URL into Hubitat's import flow:
   `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy`
3. Save the driver.
4. Create another new driver and paste in the contents of [DahuaCameraDriver.groovy](DahuaCameraDriver.groovy), or paste this URL into Hubitat's import flow:
   `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy`
5. Save the driver.
6. In Hubitat, go to `Apps Code`.
7. Create a new app and paste in the contents of [DahuaNVRSyncApp.groovy](DahuaNVRSyncApp.groovy), or paste this URL into Hubitat's import flow:
   `https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy`
8. Save the app.
9. Go to `Apps`.
10. Add the `Dahua NVR Sync` app.

### Initial Configuration

1. Enter the NVR IP or hostname.
2. Enter the HTTP port used by the NVR.
3. Enter the username and password.
4. Optionally set a global camera-name prefix.
5. Set the motion inactive timeout.
6. Save the app.
7. Open `Manage Cameras`.
8. Run `Discover / Re-sync Cameras`.
9. Review discovered channels.
10. Enable or disable individual cameras.
11. Apply custom names if desired.
12. Open `Motion Mapping`.
13. Choose which Dahua event types should drive Hubitat motion.

## Typical Usage

Once configured, the app creates:

- one parent NVR device for health and event-stream status
- one child device per enabled camera channel

The child devices can then be used in:

- Rule Machine
- dashboards
- notifications
- motion-based automations

## Logging

Normal logs include:

- discovery summary
- auth success and failure
- event stream connect and reconnect state
- motion-related Dahua events received
- motion transitions on child devices

Debug logs include:

- Dahua discovery payload details
- unknown event shapes
- channel mapping behavior
- reconnect troubleshooting

For early testing, keeping debug logs enabled is recommended until your NVR model is behaving correctly.

## Motion Mapping Behavior

Each camera can derive Hubitat `motion` from a user-selected set of Dahua event types.

Examples:

- only `VideoMotion`
- only `SmartMotionHuman` and `SmartMotionVehicle`
- only `CrossLineDetection` and `CrossRegionDetection`

The child driver keeps track of overlapping active event sources so one event stopping does not incorrectly clear motion while another motion-driving event is still active.

## Reconnect Behavior

If the Dahua event stream drops, the parent driver attempts to reconnect automatically with bounded backoff.

Current reconnect schedule:

- 5 seconds
- 15 seconds
- 30 seconds
- 60 seconds
- then every 5 minutes

If authentication fails, the integration stops retrying until credentials are corrected.

## Known Limitations

- Dahua channel numbering and naming may vary across recorder models and firmware
- Some NVRs may omit expected fields such as device type
- Some event payloads may have different key names than expected
- This release has not yet been broadly validated against multiple recorder families

## Troubleshooting

### No Cameras Found

- Confirm the NVR host, port, and credentials
- Confirm the Hubitat hub can reach the NVR over the local network
- Turn on debug logging and re-run discovery
- Check Hubitat logs for unexpected Dahua endpoint responses

### Cameras Exist but Motion Does Not Update

- Confirm the event type you care about is enabled on the Dahua side
- Confirm that event type is selected as a motion-driving type in the app
- Check normal logs for the motion event receipt
- Check debug logs for unknown channel or unknown event payload issues

### Motion Stays Active Too Long

- Lower the motion inactive timeout
- Review whether the Dahua recorder is missing stop events
- Confirm that overlapping selected event types are expected

### Event Stream Reconnects Repeatedly

- Confirm the NVR allows long-lived local HTTP connections
- Verify credentials
- Review network stability between Hubitat and the recorder
- Review parent-device reconnect diagnostics

## Development Notes

The code intentionally includes extra debug around:

- digest authentication handshake behavior
- event stream parsing
- Dahua channel discovery fallback logic
- event-to-channel routing

These are the most likely areas to need adaptation as real devices are tested.

## Documentation

- Functional spec: [FUNCSPEC.md](FUNCSPEC.md)
- Roadmap: [ROADMAP.md](ROADMAP.md)

## Release

Current release: `0.1.0`
