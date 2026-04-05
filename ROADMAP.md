# Dahua NVR Sync for Hubitat Roadmap

## Version 1.0

Read-only NVR integration focused on discovery, event reliability, and Hubitat automation value.

Planned scope:

- Local Dahua NVR connection with digest authentication
- NVR discovery and camera channel import
- One Hubitat child device per camera
- Global prefix and per-camera custom naming
- Per-camera enable and disable without full re-sync
- Single persistent NVR event stream
- Graceful reconnect when event stream drops
- Normal logging for all motion-related events received
- User-selectable event-to-motion mapping
- Dahua-specific child attributes for major event types
- Parent device health and reconnect diagnostics

## Version 1.1

Stability and supportability improvements after real-world testing.

Targets:

- Better diagnostics page and troubleshooting helpers
- More defensive channel-discovery fallbacks
- Improved unknown-event capture and inspection
- Better stale-camera handling UX
- More robust handling for unusual recorder payloads

## Version 1.2

Operational quality and camera health improvements.

Targets:

- Camera online/offline reporting where supported
- Better distinction between auth failures and transport failures
- Optional lightweight raw-event history on the parent device
- Additional event types exposed as first-class child attributes
- Cleaner import and update summaries

## Version 1.5

Read-heavy media enhancements without full configuration control.

Targets:

- Snapshot support if NVR endpoints are reliable enough
- Optional thumbnail or snapshot URL handling
- Dashboard-friendly image integrations
- Event-triggered snapshot hooks for automations

## Version 2.0

Carefully controlled write support, opt-in only.

Targets:

- Optional write-enabled mode
- Enable or disable selected motion detection features
- Limited arm/disarm operations where recorder support is consistent
- Manual recorder maintenance commands
- Safer permissions and clearer write-mode warnings

## Version 2.5

Richer device modeling and analytics representation.

Targets:

- Optional additional device classes:
  - doorbell
  - recorder health
  - alarm input
- Virtual composite sensors such as:
  - person motion
  - vehicle motion
  - perimeter breach
- More explicit analytics state modeling for Rule Machine

## Version 3.0

Expanded media and multi-recorder support.

Targets:

- Stream and media integration options
- Multiple NVR support patterns
- Better cross-recorder management
- Richer event context for dashboards and notifications

## Directional Guidance

The recommended product path is:

- Keep v1 focused on NVR-first discovery plus event reliability
- Prioritize correct motion behavior over broad feature count
- Use field debug data to normalize Dahua firmware differences
- Add write support only after the read path is proven stable
- Preserve Hubitat-native behavior first, then layer in Dahua-specific richness
