# Dahua NVR Sync for Hubitat Functional Specification

## 1. Product Summary

`Dahua NVR Sync` is a Hubitat integration that connects to a Dahua NVR on the local network, discovers attached camera channels, creates one Hubitat child device per camera, and streams Dahua detection events into Hubitat for automations.

Version 1 is read-only. It does not configure the NVR or cameras, and it does not change Dahua analytics settings.

Primary user outcome:

- A user enters Dahua NVR connection details into Hubitat.
- The app discovers the cameras connected to the NVR.
- The app creates one Hubitat device per camera.
- Dahua motion-related events flow into Hubitat in near real time.
- The user chooses which Dahua event types should drive the standard Hubitat `motion` attribute.

## 2. Goals

### Functional Goals

- Connect to a single Dahua NVR on the local network.
- Discover all camera channels reachable through the NVR.
- Represent each selected camera as a Hubitat child device.
- Support custom camera names or a global prefix.
- Capture Dahua notifications such as motion, human, vehicle, line crossing, and region crossing.
- Map user-selected Dahua event types to Hubitat `motion`.
- Allow per-camera enable or disable without a full re-sync.
- Gracefully reconnect when the event stream drops.
- Include all motion-related events in normal logging.

### Non-Functional Goals

- Local network only.
- Read-only operation in v1.
- Defensive handling of Dahua firmware and NVR differences.
- Stable device identity across re-syncs.
- Minimal setup after credentials are entered.

## 3. Out of Scope for v1

- PTZ control
- Snapshot capture
- Live video streaming
- Siren, light, relay, or output control
- Enabling or disabling Dahua analytics on the recorder or cameras
- Editing IVS rules or camera configuration
- Cloud or remote access

## 4. Architecture

The v1 implementation uses three Hubitat components:

- App: `Dahua NVR Sync`
- Parent driver: `Dahua NVR`
- Child driver: `Dahua Camera`

### App Responsibilities

- Collect user configuration.
- Perform NVR discovery and camera sync.
- Create and update the parent and child devices.
- Store per-camera naming, enablement, and motion-mapping preferences.
- Subscribe to the parent device's parsed event feed.
- Route parsed events to the correct child device.

### Parent Driver Responsibilities

- Represent NVR-level health and metadata.
- Maintain the long-lived Dahua event stream.
- Detect stream drops and perform graceful reconnects.
- Parse Dahua event payloads into normalized event envelopes.
- Publish raw/normalized event data for the app.

### Child Driver Responsibilities

- Represent one NVR camera channel in Hubitat.
- Expose Hubitat `motion`.
- Expose Dahua-specific attributes.
- Maintain per-camera active event state.
- Apply inactivity timeouts so `motion` does not remain stuck active after failures.

## 5. User Experience

### Install and Setup Flow

1. User installs the app.
2. User enters NVR IP/hostname, port, username, and password.
3. User validates the connection.
4. The app discovers channels and proposed names.
5. The user selects which cameras to enable.
6. The user optionally sets a global prefix or per-camera custom names.
7. The user selects which Dahua event types should count as Hubitat motion.
8. The app creates the parent and child devices and starts the event stream.

### Ongoing Management Flow

- User can re-sync camera discovery.
- User can enable or disable an individual camera without a full re-sync.
- User can change motion-mapping selections globally or per camera.
- User can review NVR health, last event time, reconnect history, and last errors.

## 6. Device Model

### Parent Device: `Dahua NVR`

Purpose:

- Represent the recorder connection and integration health.

Required attributes:

- `networkStatus`
- `eventStreamStatus`
- `lastSync`
- `lastEventReceived`
- `cameraCount`
- `model`
- `serialNumber`
- `firmwareVersion`
- `lastDisconnectTime`
- `lastReconnectAttempt`
- `reconnectCount`
- `lastReconnectReason`
- `lastError`

Suggested values:

- `networkStatus`: `connected`, `disconnected`, `reconnecting`, `authFailed`
- `eventStreamStatus`: `connected`, `disconnected`, `reconnecting`, `stopped`

Required commands:

- `refresh`
- `openEventStream`
- `closeEventStream`

### Child Device: `Dahua Camera`

Purpose:

- Represent one discovered Dahua NVR camera channel.

Required capabilities:

- `MotionSensor`
- `Refresh`
- `Sensor`

Required attributes:

- `cameraChannel`
- `cameraNumber`
- `cameraName`
- `managedStatus`
- `online`
- `lastEventCode`
- `lastEventAction`
- `lastEventTime`
- `lastMotionSource`
- `videoMotion`
- `humanDetected`
- `vehicleDetected`
- `lineCrossing`
- `regionIntrusion`
- `faceDetected`
- `videoLoss`
- `videoBlind`

## 7. Device Identity and Naming

### Stable Device Identity

Child devices must use a stable device network ID based on recorder identity plus channel.

Recommended pattern:

- `dahua-{nvrSerial}-ch-{channelId}`

If the serial number is unavailable, the implementation may fall back to a stable hash of the connection address plus channel.

### Naming Rules

Final device label resolution:

1. Per-camera custom override
2. Global prefix + discovered channel name
3. Global prefix + `Camera {channel}`

Requirements:

- Re-sync must not overwrite a user custom name.
- Re-sync may update the stored discovered name.

## 8. NVR Discovery Requirements

The app must attempt to discover:

- Recorder identity
- Device type/model
- Firmware version if available
- Camera channels
- Channel names

### Discovery Behavior

- Discovery should use Dahua local HTTP CGI interfaces with digest authentication.
- Multiple Dahua endpoints may need to be tried because firmware behavior varies.
- Missing optional metadata must not block the integration if channel discovery succeeds.
- Camera channels that cannot be fully identified should still be imported with fallback names.

### Re-Sync Requirements

Re-sync must:

- Add newly discovered cameras.
- Update metadata for existing cameras.
- Preserve user naming and enablement choices.
- Mark missing cameras as stale.
- Avoid deleting stale cameras automatically by default.

## 9. Event Subscription Requirements

### Stream Model

The integration must maintain one persistent event stream per NVR.

Expected Dahua behavior:

- Long-lived HTTP event subscription
- Heartbeat messages
- Event payloads containing at least an event code, action, and channel/index

### Stream Requirements

- Subscribe once at the NVR level, not once per camera.
- Parse all events from the recorder.
- Route events to child devices by discovered channel mapping.
- Unknown events must not crash the integration.

### Supported v1 Event Types

At minimum, v1 should recognize:

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

## 10. Motion Mapping Rules

Each camera must allow a user-selected set of Dahua event types that drive Hubitat `motion`.

Examples:

- Motion only for `VideoMotion`
- Motion only for `SmartMotionHuman` and `SmartMotionVehicle`
- Motion only for perimeter events such as `CrossLineDetection` and `CrossRegionDetection`

### Required Behavior

- If any selected motion-driving event is active, Hubitat `motion` must be `active`.
- `motion` becomes `inactive` only when all selected motion-driving event types are inactive.
- Overlapping event types must not clear each other prematurely.

### Inactivity Timeout

To handle recorder or firmware inconsistencies, the child driver must also maintain a configurable inactivity timeout.

Requirements:

- Default timeout: 30 seconds
- Configurable range: 5 to 120 seconds
- If an event start is received without a matching stop, the timeout may clear the active state

## 11. Per-Camera Enable and Disable

Per-camera enable and disable is required in v1.

Requirements:

- A user can enable or disable any discovered camera without a full re-sync.
- Disabling a camera must immediately stop event routing for that camera.
- Disabling a camera must not restart or reconnect the NVR event stream.
- Disabling a camera must preserve that camera's metadata, custom name, and motion settings.
- Re-enabling a camera must restore event handling using the previously stored configuration.

Recommended default behavior:

- Disable means "managed but inactive"
- Removal is a separate explicit action

## 12. Graceful Reconnect Requirements

Graceful reconnect behavior when the event stream drops is a required v1 feature.

The integration must detect:

- Socket closure
- Heartbeat failure
- HTTP error response from the event stream
- Repeated parse failures that indicate the stream is no longer healthy

Required reconnect behavior:

- Immediately set parent `eventStreamStatus` to `reconnecting`
- Preserve child devices and their configuration
- Retry automatically without user intervention
- Use bounded reconnect backoff:
  - 5 seconds
  - 15 seconds
  - 30 seconds
  - 60 seconds
  - then every 5 minutes
- Reset the reconnect counters after a successful reconnect
- Stop retrying only for credential/authentication failures
- Continue retrying indefinitely for transport or availability failures

Child-state requirement:

- During reconnect, children retain their last known states
- Active motion states must still clear via timeout so sensors do not remain stuck active forever

## 13. Logging Requirements

### Normal Logging

Normal logging must include:

- Auth success/failure
- Sync completion summary
- Event stream connect/disconnect/reconnect status
- All motion-related events received
- All Hubitat `motion` transitions

Motion-related events include:

- Any event code selected by the user to drive `motion`
- At minimum:
  - `VideoMotion`
  - `SmartMotionHuman`
  - `SmartMotionVehicle`
  - `CrossLineDetection`
  - `CrossRegionDetection`

Recommended normal log shape:

- `Front Drive (ch 1): SmartMotionHuman start -> motion active`
- `Garage (ch 2): VideoMotion stop -> motion unchanged`

### Debug Logging

Debug logging should include:

- Raw event payloads
- Unknown event codes
- Channel-discovery internals
- Channel mapping decisions
- Parse failures
- Reconnect reasons and retry counts

## 14. Error Handling Requirements

Authentication failures:

- Parent `networkStatus` becomes `authFailed`
- Event stream is not started
- Existing child devices remain but stop updating

Transport failures:

- Parent `networkStatus` becomes `reconnecting`
- Reconnect process starts automatically

Partial discovery failures:

- If some metadata calls fail, the app should still import channels with fallback names if possible

Unknown payload shape:

- Log in debug mode
- Preserve integration uptime
- Do not crash or clear devices unnecessarily

## 15. Security and Privacy

Requirements:

- Local network only
- No cloud dependency
- Credentials stored only in Hubitat preferences/state
- No logging of clear-text passwords
- No forwarding of camera events outside Hubitat

## 16. Acceptance Criteria

v1 is complete when:

- The user can connect to a Dahua NVR using local credentials.
- The app can discover attached camera channels.
- One child device is created per selected camera.
- The user can apply custom naming or a global prefix.
- The user can enable or disable individual cameras without a full re-sync.
- The parent maintains one persistent event stream and routes events correctly.
- Motion-related Dahua events appear in normal logs.
- User-selected Dahua event types correctly drive Hubitat `motion`.
- Stream drops recover automatically through reconnect behavior.
- Motion states do not remain permanently active solely because the stream dropped.
- The implementation remains read-only.

## 17. Known Risks and Validation Points

- Dahua recorder firmware varies significantly.
- Channel naming and channel numbering may differ by model.
- Some NVRs omit fields like `deviceType` or use alternate endpoints.
- Some event payloads may use unexpected keys or channel indexes.
- Event streaming and digest authentication behavior may vary by device family.

The implementation should therefore include debug logs and safe fallbacks in these areas so field testing can quickly refine compatibility.
