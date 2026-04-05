import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.3"

metadata {
    definition(
        name: "Dahua Camera",
        namespace: "bpavane",
        author: "Brian Pavane",
        description: "Child driver for Dahua camera channel events. Version ${DRIVER_VERSION}.",
        importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy"
    ) {
        capability "MotionSensor"
        capability "Refresh"
        capability "Sensor"

        attribute "cameraChannel", "string"
        attribute "cameraNumber", "number"
        attribute "cameraName", "string"
        attribute "managedStatus", "string"
        attribute "online", "string"
        attribute "lastEventCode", "string"
        attribute "lastEventAction", "string"
        attribute "lastEventTime", "string"
        attribute "lastMotionSource", "string"
        attribute "videoMotion", "string"
        attribute "humanDetected", "string"
        attribute "vehicleDetected", "string"
        attribute "lineCrossing", "string"
        attribute "regionIntrusion", "string"
        attribute "faceDetected", "string"
        attribute "videoLoss", "string"
        attribute "videoBlind", "string"

        command "configureCamera", [[name: "json", type: "STRING"]]
        command "applyDahuaEvent", [[name: "json", type: "STRING"]]
        command "setManagedDisabled", [[name: "disabled", type: "BOOLEAN"], [name: "reason", type: "STRING"]]
        command "refresh"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    initializeDefaults()
}

def updated() {
    initializeDefaults()
}

def refresh() {
    debugLog "Refresh requested for ${device.displayName}"
}

def configureCamera(String json) {
    Map config = parseJson(json)
    state.cameraConfig = [
        channel               : config.channel?.toString(),
        cameraName            : config.cameraName ?: device.displayName,
        discoveredName        : config.discoveredName ?: device.displayName,
        enabled               : config.enabled != false,
        motionDrivingEventTypes: (config.motionDrivingEventTypes ?: []) as List<String>,
        motionInactiveSeconds : (config.motionInactiveSeconds ?: 30) as Integer
    ]

    device.updateDataValue("cameraChannel", state.cameraConfig.channel ?: "")
    sendEvent(name: "cameraChannel", value: state.cameraConfig.channel ?: "")
    sendEvent(name: "cameraNumber", value: safeInt(state.cameraConfig.channel))
    sendEvent(name: "cameraName", value: state.cameraConfig.cameraName)
    sendEvent(name: "managedStatus", value: state.cameraConfig.enabled ? "enabled" : "disabled")
    if (state.cameraConfig.enabled == false) {
        sendEvent(name: "motion", value: "inactive")
    }
}

def setManagedDisabled(Boolean disabled, String reason = "") {
    state.cameraConfig = (state.cameraConfig ?: [:]) + [enabled: !disabled]
    sendEvent(name: "managedStatus", value: disabled ? "disabled" : "enabled")
    if (disabled) {
        sendEvent(name: "motion", value: "inactive")
        clearActiveState()
        debugLog "Camera disabled${reason ? " (${reason})" : ""}"
    } else {
        debugLog "Camera re-enabled"
    }
}

def applyDahuaEvent(String json) {
    Map event = parseJson(json)
    if ((state.cameraConfig?.enabled) == false) {
        debugLog "Ignoring event for disabled camera ${device.displayName}: ${event.code} ${event.action}"
        return
    }

    String code = event.code ?: "Unknown"
    String action = normalizeAction(event.action)
    sendEvent(name: "lastEventCode", value: code)
    sendEvent(name: "lastEventAction", value: action)
    sendEvent(name: "lastEventTime", value: event.timestamp ?: nowIso())

    updateSpecificAttribute(code, action)
    updateMotionState(code, action, event)
}

private void updateSpecificAttribute(String code, String action) {
    String value = action == "start" ? "active" : "inactive"
    switch (code) {
        case "VideoMotion":
            sendEvent(name: "videoMotion", value: value)
            break
        case "SmartMotionHuman":
            sendEvent(name: "humanDetected", value: value)
            break
        case "SmartMotionVehicle":
            sendEvent(name: "vehicleDetected", value: value)
            break
        case "CrossLineDetection":
            sendEvent(name: "lineCrossing", value: value)
            break
        case "CrossRegionDetection":
            sendEvent(name: "regionIntrusion", value: value)
            break
        case "FaceDetection":
            sendEvent(name: "faceDetected", value: value)
            break
        case "VideoLoss":
            sendEvent(name: "videoLoss", value: value)
            break
        case "VideoBlind":
            sendEvent(name: "videoBlind", value: value)
            break
        default:
            debugLog "No dedicated attribute mapping yet for ${code}"
            break
    }
}

private void updateMotionState(String code, String action, Map event) {
    List<String> motionTypes = ((event.motionDrivingEventTypes ?: state.cameraConfig?.motionDrivingEventTypes ?: []) as List).collect { it.toString() }
    if (!motionTypes.contains(code)) {
        return
    }

    state.activeMotionSources = (state.activeMotionSources ?: [:]) as Map
    if (action == "start") {
        state.activeMotionSources[code] = now()
        sendEvent(name: "lastMotionSource", value: code)
        if (device.currentValue("motion") != "active") {
            sendEvent(name: "motion", value: "active")
            log.info "${device.displayName}: ${code} start -> motion active"
        } else {
            log.info "${device.displayName}: ${code} start -> motion unchanged"
        }
        scheduleMotionTimeout(event.motionInactiveSeconds ?: state.cameraConfig?.motionInactiveSeconds ?: 30)
        return
    }

    if (action == "stop") {
        state.activeMotionSources.remove(code)
        recalculateMotion("${code} stop")
        return
    }

    debugLog "Treating action ${action} as stateful start for ${code}"
    state.activeMotionSources[code] = now()
    scheduleMotionTimeout(event.motionInactiveSeconds ?: state.cameraConfig?.motionInactiveSeconds ?: 30)
}

def motionTimeoutCheck() {
    Integer timeoutSeconds = (state.cameraConfig?.motionInactiveSeconds ?: 30) as Integer
    Long cutoff = now() - (timeoutSeconds * 1000L)
    state.activeMotionSources = (state.activeMotionSources ?: [:]) as Map
    state.activeMotionSources = state.activeMotionSources.findAll { String code, Object ts ->
        (ts as Long) >= cutoff
    }
    recalculateMotion("timeout")
    if (!state.activeMotionSources.isEmpty()) {
        scheduleMotionTimeout(timeoutSeconds)
    }
}

private void recalculateMotion(String reason) {
    state.activeMotionSources = (state.activeMotionSources ?: [:]) as Map
    boolean active = !state.activeMotionSources.isEmpty()
    String nextValue = active ? "active" : "inactive"
    if (device.currentValue("motion") != nextValue) {
        sendEvent(name: "motion", value: nextValue)
        log.info "${device.displayName}: ${reason} -> motion ${nextValue}"
    } else {
        log.info "${device.displayName}: ${reason} -> motion unchanged"
    }
}

private void clearActiveState() {
    state.activeMotionSources = [:]
    unschedule("motionTimeoutCheck")
}

private void scheduleMotionTimeout(Integer seconds) {
    Integer safe = Math.max(5, Math.min((seconds ?: 30) as Integer, 120))
    runIn(safe, "motionTimeoutCheck")
}

private void initializeDefaults() {
    if (device.currentValue("motion") == null) {
        sendEvent(name: "motion", value: "inactive")
    }
    if (device.currentValue("managedStatus") == null) {
        sendEvent(name: "managedStatus", value: "enabled")
    }
}

private String normalizeAction(Object raw) {
    if (raw == null) {
        return "unknown"
    }
    String value = raw.toString().trim().toLowerCase()
    if (["start", "pulse", "active", "on"].contains(value)) {
        return "start"
    }
    if (["stop", "inactive", "off"].contains(value)) {
        return "stop"
    }
    return value
}

private Map parseJson(String json) {
    return (json ? new JsonSlurper().parseText(json) : [:]) as Map
}

private Integer safeInt(Object value) {
    try {
        return value?.toString()?.toInteger()
    } catch (Exception ignored) {
        return null
    }
}

private String nowIso() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
}

private void debugLog(String message) {
    if (settings.logEnable) {
        log.debug message
    }
}
