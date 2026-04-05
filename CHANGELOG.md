# Changelog

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
