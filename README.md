# Hubitat Dahua Camera Notifications

**Version:** 0.2.1  
**Author:** Brian Pavane  
**Namespace:** `bpavane`  
**Category:** Safety & Security  

Read-only Hubitat integration for Dahua NVRs that discovers recorder-connected cameras, creates one Hubitat child device per camera, and maps Dahua motion-related events into Hubitat automations.

Version `0.2.1` is an early field-test release focused on:

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

## Features in 0.2.1

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
- Include a local automated test harness for the Hubitat codebase
- Add event-stream buffer overflow protection in the parent driver

## Current v1 Scope

Version `0.2.1` is read-only. It does not:

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

### Option A — Import directly in Hubitat

#### Parent driver

1. In the Hubitat UI, navigate to **Drivers Code**.
2. Click **+ New Driver**.
3. Click **Import**.
4. Paste the following URL and click **Import**:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy
   ```

5. Click **Save**.

#### Child driver

1. In the Hubitat UI, navigate to **Drivers Code**.
2. Click **+ New Driver**.
3. Click **Import**.
4. Paste the following URL and click **Import**:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy
   ```

5. Click **Save**.

#### App

1. In the Hubitat UI, navigate to **Apps Code**.
2. Click **+ New App**.
3. Click **Import**.
4. Paste the following URL and click **Import**:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy
   ```

5. Click **Save**.
6. Navigate to **Apps**.
7. Click **+ Add User App**.
8. Select **Dahua NVR Sync**.

### Option B — Manual paste

1. Open [DahuaNVRDriver.groovy](DahuaNVRDriver.groovy) and copy all content into **Drivers Code**.
2. Open [DahuaCameraDriver.groovy](DahuaCameraDriver.groovy) and copy all content into **Drivers Code**.
3. Open [DahuaNVRSyncApp.groovy](DahuaNVRSyncApp.groovy) and copy all content into **Apps Code**.
4. Save each file in Hubitat.
5. Navigate to **Apps → + Add User App → Dahua NVR Sync**.

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

Current release: `0.2.1`
