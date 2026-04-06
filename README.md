# Hubitat Dahua Camera Notifications

**Version:** 0.4.4  
**Author:** Brian Pavane  
**Namespace:** `bpavane`  
**Category:** Safety & Security  

Read-only Hubitat integration for Dahua NVRs that discovers recorder-connected cameras, creates one Hubitat child device per camera, and maps Dahua motion-related events into Hubitat automations.

The app is intended to run as a single Hubitat app instance. Under normal operation there should be only one `Dahua NVR` parent device for the installed app instance.

Version `0.4.4` is an early field-test release focused on:

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

## Features in 0.4.4

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
- Include explicit app icon metadata required by Hubitat app import on stricter firmware
- Stage camera discovery before device creation so names can be finalized before child devices are created
- Improve Dahua NVR camera-channel discovery for table-prefixed config keys and remote device listings
- Prevent plaintext passwords from appearing in the visible parent connection state
- Wait for the parent socket to open before sending the event-stream request
- Add parent-device diagnostics for connection phase, socket status, HTTP status, and recent stream samples
- Fall back to sending the initial event-stream request if Hubitat does not emit socket-open promptly
- Refresh parent camera count from staged discovered cameras during apply/update
- Add probe status, request preview, header sample, buffer size, raw chunk count, and rolling connection trace diagnostics on the parent device
- Redact plaintext passwords and digest authorization headers from parent error and diagnostic fields
- Detect and correct channel index offset on NVRs that report 0-based ChannelTitle keys so event-stream routing works on affected firmware
- Filter duplicate events when NVR firmware emits the same physical event at multiple stream levels within 500 ms
- Test Connection button on the main page validates credentials against the NVR before discovery is run
- Camera discovery is explicit — opening Manage Cameras no longer triggers an automatic scan

## Current v1 Scope

Version `0.4.4` is read-only. It does not:

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

### Upgrade Instructions

When upgrading from an older release, update all three code files before opening the installed app instance.

#### Upgrade the parent driver

1. In Hubitat, open **Drivers Code**.
2. Open the existing **Dahua NVR** driver.
3. Click **Import**.
4. Paste:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy
   ```

5. Click **Import**, then **Save**.

#### Upgrade the child driver

1. In Hubitat, open **Drivers Code**.
2. Open the existing **Dahua Camera** driver.
3. Click **Import**.
4. Paste:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy
   ```

5. Click **Import**, then **Save**.

#### Upgrade the app

1. In Hubitat, open **Apps Code**.
2. Open the existing **Dahua NVR Sync** app code.
3. Click **Import**.
4. Paste:

   ```text
   https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy
   ```

5. Click **Import**, then **Save**.

#### Finish the upgrade

1. Open the installed **Dahua NVR Sync** app under **Apps**.
2. Click **Done** once to let Hubitat apply the updated app code.
3. Open one parent device and one child device to confirm the updated metadata/description is visible.
4. If Hubitat shows old compile errors during import, clear the editor contents and import the versioned URL again.

The `main` branch raw URLs track the latest published code. If Hubitat appears to cache an older import, clear the editor and re-import.

### Upgrade Safety and Recommended Steps

For normal upgrades, it is safe to re-import all three code files:

- `Dahua NVR Sync` app
- `Dahua NVR` parent driver
- `Dahua Camera` child driver

You do not normally need to delete and recreate:

- the installed app instance
- the parent device
- the child camera devices

Recommended upgrade flow:

1. Re-import the parent driver.
2. Re-import the child driver.
3. Re-import the app code.
4. Open the installed app under **Apps**.
5. Click **Done** once.

That final **Done** is the important step because it lets the updated app:

- re-apply staged camera configuration
- refresh parent metadata such as camera count
- re-subscribe event routing if needed
- reopen the parent event stream with the latest logic

### Do I Need to Press Refresh Anywhere?

Usually:

- **App:** Yes, open the app and click **Done** after upgrade
- **Parent device:** Optional, but recommended if the event stream does not come back on its own
- **Child devices:** No manual refresh is normally needed

If the parent device still looks stale after upgrade:

1. Open the **Dahua NVR** parent device.
2. Press **Refresh** once.
3. Wait a few seconds and review:
   - `Network Status`
   - `Event Stream Status`
   - `Connection Phase`
   - `Last Socket Status`
   - `Last HTTP Status Line`
   - `Last Error`

Use parent refresh when:

- the parent still shows an older camera count
- the event stream appears stuck in reconnecting
- you want to force the latest stream-opening logic after upgrading

### When Would More Than Re-Import Be Needed?

Usually not needed, but consider deeper cleanup only if:

- the parent device still shows obviously stale state after app **Done** and parent **Refresh**
- child devices were created with incorrect device network IDs by a much older version
- Hubitat appears to be holding cached code that does not match the imported source

In those cases:

- first try app **Done**
- then try parent **Refresh**
- then run **Discover / Re-sync Cameras** from the app

Only if those steps fail should you consider removing and rebuilding devices.

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

## Parent Driver Reference

The `Dahua NVR` parent device is the integration's connection, health, and event-stream control point. It holds the recorder connection metadata, manages the long-lived Dahua stream, exposes troubleshooting fields, and forwards raw Dahua events to the app for child-camera routing.

The app is the source of truth for recorder credentials. The parent device does not expose editable username or password fields. Instead, the app pushes the current host, port, username, and password down to the parent whenever app settings are saved, when connection testing runs, and during discovery/apply flows.

Under normal operation there should only be one parent device for the installed app. If you ever see multiple parent devices for the same recorder IP, that usually means multiple app instances were installed before the app was switched to single-instance behavior.

### Parent driver commands

- `Refresh`
  Refreshes the parent connection state and attempts to reopen the Dahua event stream when it is not currently connected.
- `openEventStream`
  Starts a new Dahua event-stream attach attempt immediately.
- `closeEventStream`
  Closes the current event-stream socket and marks the parent stopped.
- `applyConnectionSettings`
  Internal command used by the app to push connection metadata, camera count, and debug settings to the parent device.
- `runRawSocketHttpTest(path)`
  Diagnostic command that opens a raw socket and performs a normal HTTP GET against the provided path instead of the Dahua event stream. This is useful for proving whether Hubitat can receive any HTTP response from the NVR over the raw-socket path.

### Parent driver preferences

- `Enable debug logging`
  Enables verbose parent-driver logging and richer connection trace entries.
- `Stream request mode`
  Selects how the parent formats the event-stream request. Available modes are:
- `auto`
- `keepAliveHeartbeat`
- `keepAliveNoHeartbeat`
- `closeHeartbeat`
- `closeNoHeartbeat`
- `http10NoHeartbeat`

### Parent status attributes

- `networkStatus`
  Overall parent connectivity state such as `connected`, `reconnecting`, `disconnected`, or `authFailed`.
- `eventStreamStatus`
  Stream-specific state such as `connected`, `reconnecting`, or `stopped`.
- `cameraCount`
  Total discovered camera count currently known by the app and pushed to the parent.
- `model`
  Dahua model or device type when discovery found one.
- `serialNumber`
  Recorder serial number when available.
- `lastSync`
  Last app discovery/sync timestamp pushed into the parent.
- `lastEventReceived`
  Timestamp of the last successfully parsed Dahua event.
- `reconnectCount`
  Current reconnect-attempt counter for the active recovery cycle.
- `lastReconnectAttempt`
  Timestamp of the most recent scheduled reconnect attempt.
- `lastReconnectReason`
  Reason for the most recent reconnect schedule or disconnect.
- `lastDisconnectTime`
  Timestamp when the last disconnect was handled.
- `lastError`
  Most recent error message after redaction.

### Parent diagnostic attributes

- `connectionPhase`
  Current internal connection phase. Common values include:
- `refresh`
- `open_event_stream`
- `probing_auth`
- `opening_socket`
- `socket_open`
- `sending_initial_request`
- `sending_preauthenticated_request`
- `sending_authenticated_request`
- `http_headers`
- `stream_connected`
- `handshake_timeout`
- `scheduled_reconnect`
- `reconnecting`
- `raw_socket_test`
- `sending_test_request`
- `raw_socket_test_success`
- `stopped`
- `lastRequestPath`
  The Dahua path the parent most recently attempted to open.
- `lastRequestMode`
  The currently selected request mode used when building stream or test requests.
- `lastRequestPreview`
  Sanitized preview of the last outbound raw-socket HTTP request.
- `lastConnectMethod`
  Which raw-socket connect signature Hubitat accepted, such as `hostPort` or `host_port`.
- `lastSocketStatus`
  Most recent raw-socket status callback text after redaction.
- `lastHttpStatusLine`
  Most recent HTTP status line received from the NVR over the raw socket.
- `lastHeaderSample`
  Sanitized preview of the last HTTP response headers seen by the parent.
- `lastRawMessageSample`
  Sanitized preview of the most recent raw chunk received from the socket.
- `streamBufferBytes`
  Current size of the in-memory socket buffer waiting to be parsed.
- `rawChunkCount`
  Count of raw socket chunks received during the current attempt.
- `lastProbeStatus`
  Result of the parent’s preliminary HTTP auth probe. Common values include `starting`, `digest_challenge_received`, `http_200_no_challenge`, `http_401_without_digest`, and `probe_failed`.
- `lastProbeHttpStatus`
  HTTP status observed during the auth probe when one was available.
- `lastProbeErrorClass`
  Java or Groovy exception class name from the probe path when the probe fails before a normal HTTP status is available.
- `connectionTrace`
  Rolling multi-line trace of the most recent connection lifecycle steps, suitable for copy/paste into troubleshooting discussions.
- `lastAttemptSummary`
  Short single-line summary of the most recent stream or raw-socket test attempt.
- `lastRawSocketTestPath`
  Path used by the most recent `runRawSocketHttpTest(...)` call.
- `lastRawSocketTestStatus`
  Status of the most recent raw-socket HTTP test attempt.
- `lastRawSocketHeaderSample`
  Sanitized preview of the HTTP headers returned by the raw-socket HTTP test.

### How to use the parent diagnostics

- Use `Refresh` first when the parent appears stale after an app upgrade or settings change.
- Remember that credential changes happen in the app, not the parent device. After changing app credentials, click **Done** in the app; the parent will receive the new host, port, username, and password automatically.
- Look at `connectionPhase`, `lastAttemptSummary`, and `connectionTrace` first. Those three fields usually tell the story fastest.
- If `lastProbeStatus` is `digest_challenge_received` but the stream never reaches `stream_connected`, the event-stream transport is the likely problem rather than basic auth.
- If `lastSocketStatus` never reports an open socket and `lastHttpStatusLine` stays blank, the raw-socket path is probably failing before the NVR returns HTTP headers.
- If `streamBufferBytes` increases but `lastHttpStatusLine` stays blank, bytes are arriving but the current parser is not yet recognizing a full header boundary.
- Use `runRawSocketHttpTest('/cgi-bin/magicBox.cgi?action=getSystemInfo')` to verify that Hubitat can receive any normal HTTP response from the NVR over the raw-socket path.
- If the raw-socket test succeeds but the event stream still fails, try a different `Stream request mode` before changing app/device configuration.
- If `lastRawSocketTestStatus` fails the same way as the event stream, the issue is likely below Dahua event parsing and closer to raw socket or recorder HTTP behavior.

## Logging

Normal logs include:

- discovery summary
- auth success and failure
- event stream connect and reconnect state
- motion-related Dahua events received
- motion transitions on child devices

Debug logs include:

- Dahua discovery payload details
- app-to-parent credential sync activity
- per-event routing and channel-offset adjustments
- unknown event shapes
- channel mapping behavior
- request mode, raw-socket operation mode, chunk counts, probe behavior, and reconnect troubleshooting

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
- NVRs that use 0-based ChannelTitle keys are handled by automatic offset detection; NVRs with
  1-based ChannelTitle keys but 0-based event-stream indexes are not yet auto-corrected
- Some NVRs may omit expected fields such as device type
- Some event payloads may have different key names than expected
- This release has not been validated against a broad matrix of Dahua firmware families

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
- If debug logs show "Dropping event for unknown channel 0", the NVR may use a 1-based
  ChannelTitle with 0-based event indexes: re-run discovery so the offset can be detected, or
  confirm that channels appear in `Manage Cameras` before events are expected

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

Current release: `0.4.3`
