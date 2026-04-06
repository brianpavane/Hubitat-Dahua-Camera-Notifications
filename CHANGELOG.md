# Changelog

## [0.4.3] - 2026-04-05

### Fixed

- **Test Connection** now surfaces actionable error messages instead of raw Java exception text.
  Pool-exhaustion errors (`Timeout waiting for connection from pool`) prompt the user to wait 60 s;
  TCP timeout, connection-refused, and network-unreachable conditions each get a distinct hint with
  the NVR address included.
- Driver and camera-driver version constants bumped from `0.4.1` to `0.4.3` to stay in sync with
  the app version.

### Added

- Two new regression tests: pool-saturation guidance and timeout guidance for the Test Connection
  error-classification path.

## [0.4.2] - 2026-04-05

### Changed

- App configuration flow is now credential-first: entering the NVR IP and credentials and pressing
  Done no longer triggers camera discovery automatically.
- `Manage Cameras` page no longer auto-discovers cameras on first visit. Discovery only runs when
  the user explicitly taps `Discover / Re-sync Cameras`.
- Removed the stale guidance paragraph from the Actions section of the main page.

### Added

- **Test Connection** button on the main page. Sends one authenticated probe to the NVR and
  displays the result (model, serial number, or a specific failure reason) inline on the page so
  credentials can be validated before discovery is run.
- Four new regression tests covering connection-test success, network failure, missing-credential
  guard, and the no-auto-discover guarantee on the camera management page.

## [0.4.1] - 2026-04-05

### Added

- README now includes a full parent-driver reference covering commands, preferences, status fields, diagnostic attributes, and troubleshooting guidance

### Fixed

- Replaced Hubitat-incompatible `e.getClass().getName()` usage in the parent driver with a safe exception-class helper so the parent code imports correctly on Hubitat
- Parent diagnostic exception-class capture is preserved without using forbidden Hubitat expression forms

## [0.4.0] - 2026-04-05

### Fixed

- Event-stream routing now works on Dahua NVRs that report 0-based ChannelTitle config keys
  (`ChannelTitle[0]`, `ChannelTitle[1]`…). Discovery detects the 0-based numbering, shifts channel
  names to 1-based for Hubitat devices, and stores a `channelIndexOffset` so incoming event-stream
  `index` values are adjusted to match before lookup. Previously, every event was silently dropped
  as an unknown channel on these NVR models.

### Added

- Event filtering by channel: duplicate `channel:code:action` fingerprints arriving within 500 ms
  are dropped. Prevents double-processing on Dahua firmware that emits the same physical event at
  both the camera-subsystem level and the NVR aggregate level simultaneously.
- Three new regression tests covering 0-based offset detection, offset-adjusted event routing, and
  500 ms fingerprint-based deduplication.

## [0.3.5] - 2026-04-05

### Added

- Expanded parent-device diagnostics with probe status, probe HTTP status, header sample, request preview, raw chunk count, stream buffer size, and rolling connection trace
- Added regression coverage for parent diagnostic redaction and richer event-stream lifecycle tracing

### Fixed

- Parent diagnostics now redact plaintext passwords and digest authorization headers in error and status fields
- Parent `connectionPhase` no longer appears stuck on `probing_auth` once the socket-open phase begins
- Parent `streamBufferBytes` now reflects the remaining buffered data after event chunks are consumed

## [0.3.4] - 2026-04-05

### Added

- Parent event-stream startup now probes Dahua digest authentication over HTTP before opening the raw socket
- Parent event-stream startup now retries an alternate Dahua attach path if the initial request path times out waiting for headers

### Changed

- Parent camera count now reflects the app's full discovered camera inventory during app initialization and apply flows
- Parent event-stream handshake timeout increased to better tolerate slower Dahua responses during attach

### Fixed

- Upgraded installs now republish parent camera count on app initialization so stale values like `1` are corrected after re-import and app `Done`
- Event-stream startup can preauthenticate the raw-socket request instead of waiting on a 401 challenge over the long-lived stream
- Added connection-phase diagnostics for the preauth probe path so stalled Dahua attaches are easier to troubleshoot

## [0.3.3] - 2026-04-05

### Added

- README guidance for safe upgrades, including when to re-import code, when to open the app and press **Done**, and when parent **Refresh** is useful

## [0.3.2] - 2026-04-05

### Added

- Parent-device diagnostics for connection phase, last socket status, last HTTP status line, last request path, and recent raw stream samples
- Fallback initial request path for event-stream startup when Hubitat does not promptly emit raw-socket open status

### Fixed

- Parent connection state now strips legacy plaintext password values during initialize and refresh
- Parent refresh no longer leaves a misleading `Network Status = Connected` while the stream is reconnecting
- Parent camera count is refreshed from staged discovered cameras during apply/update flows on upgraded installs

## [0.3.1] - 2026-04-05

### Fixed

- Disabled cameras staged in Manage Cameras no longer create child devices during apply
- Zero-based Dahua camera-name payloads are remapped to one-based Hubitat-facing channel numbers so Camera 1 can inherit the correct label

## [0.3.0] - 2026-04-05

### Added

- Staged discovery flow so camera naming and enablement can be finalized before child devices are created
- More robust Dahua camera discovery for `table.ChannelTitle[...]` payloads and remote device channel data
- Regression tests covering staged discovery and channel-zero filtering

### Changed

- Parent driver now stores the NVR password in a password setting instead of plaintext `state.connection`
- Parent event-stream startup now waits for socket-open status before sending the HTTP request
- README import and upgrade examples now point back to the `main` branch raw URLs

## [0.2.3] - 2026-04-05

### Fixed

- Added non-empty `iconUrl` and `iconX2Url` entries to the app definition for Hubitat firmware that rejects blank icon metadata

### Added

- Repository-hosted SVG icon asset for the app definition

## [0.2.2] - 2026-04-05

### Added

- Embedded version metadata in the app and both drivers via version constants in the source files
- Explicit driver and app upgrade instructions in the README

### Changed

- Switched README import and upgrade examples to version-pinned raw GitHub URLs for the published release

## [0.2.1] - 2026-04-05

### Fixed

- Removed Hubitat-incompatible `DeviceWrapper` type annotations from the app source so the app imports and compiles correctly in Hubitat Apps Code

## [0.2.0] - 2026-04-05

### Added

- Full local Gradle and Spock test harness for the Hubitat app and drivers
- Automated tests covering child motion state handling, parent reconnect behavior, app event routing, and repository metadata
- Minimal `.gitignore` for Gradle build outputs

### Changed

- Hardened the parent Dahua event stream parser with a maximum buffer size and reconnect on overflow

## [0.1.1] - 2026-04-05

### Changed

- Added Hubitat `importUrl` metadata to the app and both drivers so imports carry cleaner metadata
- Reformatted the README installation section to match the import-link style used by Hubitat Bulk File Manager
- Added explicit copy/paste import blocks for the app, parent driver, and child driver

## [0.1.0] - 2026-04-05

Initial public release.

### Added

- Hubitat app for Dahua NVR configuration and camera sync
- Dahua NVR parent driver
- Dahua camera child driver
- Dahua NVR discovery and child-device creation
- Per-camera enable and disable without full re-sync
- Configurable camera naming with global prefix and custom overrides
- NVR event-stream handling with digest authentication
- Automatic reconnect when event stream drops
- Motion-related normal logging
- Motion mapping from Dahua event types to Hubitat `motion`
- Functional specification
- Roadmap
- GitHub-ready installation and usage documentation
