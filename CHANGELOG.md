# Changelog

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
