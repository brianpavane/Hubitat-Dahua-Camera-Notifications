import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String APP_VERSION = "0.4.1"
@Field static final List<String> DEFAULT_MOTION_EVENTS = [
    "VideoMotion",
    "SmartMotionHuman",
    "SmartMotionVehicle",
    "CrossLineDetection",
    "CrossRegionDetection"
]

definition(
    name: "Dahua NVR Sync",
    namespace: "bpavane",
    author: "Brian Pavane",
    description: "Read-only Dahua NVR integration for Hubitat with per-camera child devices. Version ${APP_VERSION}.",
    category: "Safety & Security",
    iconUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/assets/dahua-nvr-sync-icon.svg",
    iconX2Url: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/assets/dahua-nvr-sync-icon.svg",
    importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy",
    singleInstance: false,
    installOnOpen: false
)

preferences {
    page(name: "mainPage")
    page(name: "cameraPage")
    page(name: "motionPage")
    page(name: "statusPage")
}

def installed() {
    log.info "Dahua NVR Sync installed"
    initialize()
}

def updated() {
    log.info "Dahua NVR Sync updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    ensureParentDevice()
    subscribeToParent()
    if (settings.autoSyncDaily) {
        schedule("0 0 3 * * ?", "discoverAndApplyConfiguredCameras")
    }
    if (state.discoveredCameras) {
        updateParentMetadata([
            serialNumber: state.nvrSerialNumber ?: digestFallbackId(),
            model       : state.nvrModel ?: "Dahua NVR"
        ])
    }
    if (settings.nvrHost && settings.nvrUsername && settings.nvrPassword && state.discoveredCameras) {
        applyConfiguredCameras()
        refreshParentConnection()
    }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Dahua NVR Sync", install: true, uninstall: true) {
        section("Connection") {
            input "nvrHost", "text", title: "NVR IP or hostname", required: true
            input "nvrPort", "number", title: "HTTP port", required: true, defaultValue: 80
            input "nvrUsername", "text", title: "Username", required: true
            input "nvrPassword", "password", title: "Password", required: true
            input "cameraNamePrefix", "text", title: "Global camera name prefix", required: false
        }

        section("Options") {
            input "motionInactiveSeconds", "number", title: "Motion inactive timeout seconds", required: true, defaultValue: 30, range: "5..120"
            input "autoSyncDaily", "bool", title: "Auto-sync daily at 3:00 AM", required: false, defaultValue: false
            input "enableDebugLogging", "bool", title: "Enable debug logging", required: false, defaultValue: true
        }

        section("Actions") {
            href "cameraPage", title: "Manage Cameras", description: "Discover, enable, and rename cameras"
            href "motionPage", title: "Motion Mapping", description: "Choose which Dahua events drive Hubitat motion"
            href "statusPage", title: "Status", description: "Review sync, stream, and reconnect status"
            paragraph "Use the Hubitat app button after saving to run discovery and create or update child devices."
        }
    }
}

def cameraPage() {
    if (!state.discoveredCameras) {
        discoverCameras()
    }

    dynamicPage(name: "cameraPage", title: "Manage Cameras", install: false, uninstall: false) {
        section("Discovery") {
            paragraph "Discovered cameras: ${(state.discoveredCameras ?: [:]).size()}"
            input "runImmediateSync", "button", title: "Discover / Re-sync Cameras"
        }

        Map cameras = state.discoveredCameras ?: [:]
        if (cameras) {
            cameras.keySet().sort { a, b -> a.toInteger() <=> b.toInteger() }.each { String channel ->
                Map cam = cameras[channel] as Map
                section("Channel ${channel}") {
                    input "camEnabled_${channel}", "bool", title: "Enabled", defaultValue: cam.enabled != false, required: false
                    input "camName_${channel}", "text", title: "Custom name", defaultValue: cam.customName ?: "", required: false
                    paragraph "Discovered name: ${cam.discoveredName ?: "Camera ${channel}"}"
                    paragraph "Current label: ${buildCameraLabel(channel, cam)}"
                }
            }
        } else {
            section("Discovery") {
                paragraph "No cameras discovered yet. Save the app and tap Discover / Re-sync Cameras."
            }
        }
    }
}

def motionPage() {
    dynamicPage(name: "motionPage", title: "Motion Mapping", install: false, uninstall: false) {
        section("Global Defaults") {
            input "globalMotionEvents", "enum",
                title: "Events that should drive Hubitat motion by default",
                options: supportedMotionEventOptions(),
                multiple: true,
                required: false,
                defaultValue: DEFAULT_MOTION_EVENTS
        }

        Map cameras = state.discoveredCameras ?: [:]
        cameras.keySet().sort { a, b -> a.toInteger() <=> b.toInteger() }.each { String channel ->
            Map cam = cameras[channel] as Map
            section(buildCameraLabel(channel, cam)) {
                input "camMotionOverride_${channel}", "enum",
                    title: "Override motion-driving event types",
                    options: supportedMotionEventOptions(),
                    multiple: true,
                    required: false
            }
        }
    }
}

def statusPage() {
    dynamicPage(name: "statusPage", title: "Status", install: false, uninstall: false) {
        def parent = getParentDevice()
        section("Sync") {
            paragraph "Last sync: ${state.lastSync ?: "Never"}"
            paragraph "Discovered cameras: ${(state.discoveredCameras ?: [:]).size()}"
        }
        section("Parent Device") {
            paragraph "Network status: ${parent?.currentValue("networkStatus") ?: "unknown"}"
            paragraph "Stream status: ${parent?.currentValue("eventStreamStatus") ?: "unknown"}"
            paragraph "Last event: ${parent?.currentValue("lastEventReceived") ?: "Never"}"
            paragraph "Reconnect count: ${parent?.currentValue("reconnectCount") ?: "0"}"
            paragraph "Last error: ${parent?.currentValue("lastError") ?: ""}"
        }
    }
}

def appButtonHandler(String buttonName) {
    if (buttonName == "runImmediateSync") {
        discoverCameras()
    }
}

def discoverAndApplyConfiguredCameras() {
    discoverCameras()
    applyConfiguredCameras()
    refreshParentConnection()
}

def discoverCameras() {
    Map discovery = performDiscovery()
    state.lastSync = nowIso()
    state.nvrSerialNumber = discovery.serialNumber ?: digestFallbackId()
    state.nvrModel = discovery.model ?: "Dahua NVR"
    state.channelIndexOffset = (discovery.channelIndexOffset ?: 0) as Integer
    state.discoveredCameras = mergeCameraPreferences(discovery.cameras ?: [:])
    ensureParentDevice()
    updateParentMetadata(discovery)
    log.info "Dahua discovery staged: ${(state.discoveredCameras ?: [:]).size()} cameras"
}

def applyConfiguredCameras() {
    if (!state.discoveredCameras) {
        debugLog "No discovered cameras are staged yet; skipping child-device apply"
        return
    }
    updateParentMetadata([
        serialNumber: state.nvrSerialNumber ?: digestFallbackId(),
        model       : state.nvrModel ?: "Dahua NVR"
    ])
    reapplyConfiguredCameras()
    log.info "Applied configured Dahua cameras: ${(state.discoveredCameras ?: [:]).findAll { k, v -> v.stale != true }.size()} channels"
}

private Map performDiscovery() {
    Map result = [
        systemInfo        : [:],
        deviceType        : null,
        firmware          : null,
        serialNumber      : null,
        model             : null,
        cameras           : [:],
        channelIndexOffset: 0
    ]

    Map systemInfo = dahuaGetAsMap("/cgi-bin/magicBox.cgi?action=getSystemInfo")
    result.systemInfo = systemInfo
    result.serialNumber = systemInfo.serialNumber ?: systemInfo.sn ?: digestFallbackId()
    result.model = systemInfo.deviceType ?: systemInfo.updateSerial ?: systemInfo.processor ?: "Dahua NVR"

    Map deviceType = dahuaGetAsMap("/cgi-bin/magicBox.cgi?action=getDeviceType", false)
    if (deviceType?.type) {
        result.deviceType = deviceType.type
        result.model = deviceType.type
    }

    Map channelTitles = dahuaGetAsMap("/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle", false)
    Map videoWidget = dahuaGetAsMap("/cgi-bin/configManager.cgi?action=getConfig&name=VideoWidget", false)
    Map remoteDevices = dahuaGetAsMap("/cgi-bin/configManager.cgi?action=getConfig&name=RemoteDevice", false)

    Map<String, String> rawChannelTitleNames = extractChannelNames(channelTitles)
    Map<String, String> channelTitleNames = normalizeNameChannels(rawChannelTitleNames)
    Map<String, String> rawVideoWidgetNames = extractChannelNames(videoWidget)
    Map<String, String> videoWidgetNames = normalizeNameChannels(rawVideoWidgetNames)

    Map<String, Map> cameras = [:]
    channelTitleNames.each { String channel, String name ->
        cameras[channel] = [
            channel       : channel,
            discoveredName: name ?: "Camera ${channel}",
            enabled       : true
        ]
    }

    if (!cameras) {
        videoWidgetNames.each { String channel, String name ->
            cameras[channel] = [
                channel       : channel,
                discoveredName: name ?: "Camera ${channel}",
                enabled       : true
            ]
        }
    }

    if (!cameras) {
        extractRemoteDeviceChannels(remoteDevices).each { String channel, Map remote ->
            cameras[channel] = [
                channel       : channel,
                discoveredName: remote.name ?: "Camera ${channel}",
                enabled       : true
            ]
        }
    }

    if (!cameras) {
        Map cameraState = dahuaGetAsMap("/cgi-bin/devVideoInput.cgi?action=getCollect", false)
        Set<String> channels = extractChannelIds(cameraState)
        if (!channels) {
            channels = ["0"] as Set
            log.warn "Falling back to a single default channel because Dahua channel discovery did not return a known shape"
        }
        channels.each { String channel ->
            cameras[channel] = [
                channel       : channel,
                discoveredName: "Camera ${channel}",
                enabled       : true
            ]
        }
    }

    cameras = normalizeDiscoveredCameras(cameras)
    result.cameras = cameras

    // Detect whether the NVR reported 0-based channel indexes (e.g., ChannelTitle[0], ChannelTitle[1]).
    // When true, the event stream will also use 0-based index fields, which must be shifted +1 before
    // matching against the 1-based discoveredCameras keys that normalizeNameChannels produced.
    Map<String, String> rawPrimaryNames = channelTitleNames ? rawChannelTitleNames : rawVideoWidgetNames
    List<Integer> rawNums = rawPrimaryNames?.keySet()
        ?.findAll { it?.isInteger() }
        ?.collect { it.toInteger() } ?: []
    result.channelIndexOffset = (rawNums && rawNums.min() == 0) ? 1 : 0
    debugLog "Channel index offset: ${result.channelIndexOffset} (raw channel keys: ${rawNums.sort()})"
    debugLog "Discovery result: ${JsonOutput.toJson(result)}"
    return result
}

private Map mergeCameraPreferences(Map discovered) {
    Map existing = state.discoveredCameras ?: [:]
    Map merged = [:]

    discovered.each { String channel, Map cam ->
        Map previous = existing[channel] ?: [:]
        merged[channel] = [
            channel       : channel,
            discoveredName: cam.discoveredName ?: previous.discoveredName ?: "Camera ${channel}",
            customName    : settings["camName_${channel}"] ?: previous.customName,
            enabled       : settings["camEnabled_${channel}"] != null ? settings["camEnabled_${channel}"] : (previous.enabled != false),
            stale         : false
        ]
    }

    existing.each { String channel, Map cam ->
        if (!merged.containsKey(channel)) {
            merged[channel] = cam + [stale: true]
        }
    }

    return merged
}

private void syncChildDevices() {
    Map cameras = state.discoveredCameras ?: [:]
    cameras.each { String channel, Map cam ->
        String dni = cameraDni(channel)
        def child = getChildDevice(dni)
        if (cam.enabled == false && !child) {
            debugLog "Not creating child device for disabled camera ${buildCameraLabel(channel, cam)}"
            return
        }

        if (!child) {
            child = addChildDevice("bpavane", "Dahua Camera", dni, [
                name : "Dahua Camera ${channel}",
                label: buildCameraLabel(channel, cam)
            ])
        }

        child.setLabel(buildCameraLabel(channel, cam))
        child.updateSetting("logEnable", [value: settings.enableDebugLogging ? "true" : "false", type: "bool"])
        child.configureCamera(JsonOutput.toJson([
            channel                : channel,
            cameraName             : buildCameraLabel(channel, cam),
            discoveredName         : cam.discoveredName,
            enabled                : cam.enabled != false,
            motionDrivingEventTypes: motionEventsForChannel(channel),
            motionInactiveSeconds  : safeMotionInactiveSeconds()
        ]))
    }

    List existingChildren = (getChildDevices() ?: []).findAll { child ->
        child.deviceNetworkId != parentDni()
    }
    existingChildren.each { child ->
        String channel = child.getDataValue("cameraChannel")
        Map cam = cameras[channel]
        if (!cam) {
            child.setManagedDisabled(true, "stale")
        } else if (cam.enabled == false) {
            child.setManagedDisabled(true, "disabled")
        }
    }
}

private void reapplyConfiguredCameras() {
    if (!state.discoveredCameras) {
        return
    }
    state.discoveredCameras = mergeCameraPreferences(state.discoveredCameras as Map)
    syncChildDevices()
}

private void updateParentMetadata(Map discovery) {
    def parent = ensureParentDevice()
    Integer cameraCount = (state.discoveredCameras ?: [:]).size()
    parent.applyConnectionSettings(JsonOutput.toJson([
        host             : settings.nvrHost,
        port             : (settings.nvrPort ?: 80) as Integer,
        username         : settings.nvrUsername,
        password         : settings.nvrPassword,
        debugEnabled     : settings.enableDebugLogging == true,
        serialNumber     : discovery.serialNumber ?: state.nvrSerialNumber ?: digestFallbackId(),
        model            : discovery.model ?: state.nvrModel ?: "Dahua NVR",
        cameraCount      : cameraCount,
        eventCodes       : ["All"]
    ]))
    parent.sendEvent(name: "cameraCount", value: cameraCount)
    parent.sendEvent(name: "lastSync", value: state.lastSync)
}

private void refreshParentConnection() {
    def parent = ensureParentDevice()
    if (parent) {
        parent.openEventStream()
    }
}

private def ensureParentDevice() {
    String dni = parentDni()
    def existing = getChildDevice(dni)
    if (existing) {
        return existing
    }
    return addChildDevice("bpavane", "Dahua NVR", dni, [
        isComponent: false,
        name       : "Dahua NVR",
        label      : "Dahua NVR ${settings.nvrHost ?: app.id}"
    ])
}

private void subscribeToParent() {
    def parent = getParentDevice()
    if (parent) {
        subscribe(parent, "rawEvent", "handleParentRawEvent")
        subscribe(parent, "eventStreamStatus", "handleParentStatusEvent")
    }
}

def handleParentRawEvent(evt) {
    if (!evt?.value) {
        return
    }

    Map envelope
    try {
        envelope = new JsonSlurper().parseText(evt.value as String) as Map
    } catch (Exception e) {
        log.warn "Unable to parse parent raw event JSON: ${e.message}"
        return
    }

    String channel = normalizeChannel(envelope.channel ?: envelope.index ?: envelope.channelId)
    if (!channel) {
        debugLog "Dropping event without a usable channel: ${evt.value}"
        return
    }

    // Apply the channel index offset detected during discovery.  When the NVR reported 0-based
    // ChannelTitle keys (ChannelTitle[0], ChannelTitle[1]...), discovery shifted them to 1-based
    // camera keys, but the event stream still emits 0-based index values.  Adding the offset here
    // realigns the incoming index with the stored camera key.
    Integer channelOffset = (state.channelIndexOffset ?: 0) as Integer
    if (channelOffset != 0 && channel.isInteger()) {
        channel = (channel.toInteger() + channelOffset).toString()
    }

    // Event filtering by channel: drop duplicate channel+code+action combos within 500 ms.
    // Some Dahua firmware emits the same physical event twice in rapid succession — once at the
    // camera subsystem level and once at the NVR aggregate level — both with the same channel index.
    String fingerprint = "${channel}:${envelope.code}:${envelope.action}"
    Long nowMs = new Date().time
    Map fingerprints = (state.recentEventFingerprints ?: [:]) as Map
    Long lastSeen = fingerprints[fingerprint] as Long
    if (lastSeen && (nowMs - lastSeen) < 500L) {
        debugLog "Dropping duplicate event ${fingerprint} (${nowMs - lastSeen}ms ago)"
        return
    }
    fingerprints[fingerprint] = nowMs
    state.recentEventFingerprints = fingerprints.findAll { String k, Object ts -> (nowMs - (ts as Long)) < 10000L }

    Map cam = (state.discoveredCameras ?: [:])[channel]
    if (!cam) {
        debugLog "Dropping event for unknown channel ${channel}: ${evt.value}"
        return
    }

    if (cam.enabled == false) {
        debugLog "Ignoring event for disabled camera ${buildCameraLabel(channel, cam)}: ${envelope.code} ${envelope.action}"
        return
    }

    def child = getChildDevice(cameraDni(channel))
    if (!child) {
        log.warn "No child device found for channel ${channel}; event ${envelope.code} will be ignored"
        return
    }

    envelope.motionDrivingEventTypes = motionEventsForChannel(channel)
    envelope.motionInactiveSeconds = safeMotionInactiveSeconds()
    child.applyDahuaEvent(JsonOutput.toJson(envelope))
}

def handleParentStatusEvent(evt) {
    debugLog "Parent stream status changed to ${evt?.value}"
}

private List<String> motionEventsForChannel(String channel) {
    def overrideValue = settings["camMotionOverride_${channel}"]
    if (overrideValue instanceof Collection && !overrideValue.isEmpty()) {
        return overrideValue.collect { it.toString() }
    }
    if (overrideValue instanceof String && overrideValue) {
        return [overrideValue]
    }

    def global = settings.globalMotionEvents
    if (global instanceof Collection && !global.isEmpty()) {
        return global.collect { it.toString() }
    }
    if (global instanceof String && global) {
        return [global]
    }

    return DEFAULT_MOTION_EVENTS
}

private Map<String, String> extractChannelNames(Map source) {
    Map<String, String> result = [:]
    source?.each { String key, Object value ->
        def matcher = (key =~ /(?:^|.*\.)(?:ChannelTitle|VideoWidget)\[(\d+)\]\.(?:Name|ChannelTitle(?:\.Text)?|ChannelName|Title|Text|EncodeBlend)/)
        if (matcher.matches()) {
            String channel = matcher.group(1)
            String text = value?.toString()?.trim()
            if (text && !text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                result[channel] = text
            }
        }
    }
    return result
}

private Map<String, String> normalizeNameChannels(Map<String, String> names) {
    if (!names) {
        return names
    }

    List<Integer> numeric = names.keySet()
        .findAll { it?.isInteger() }
        .collect { it.toInteger() }
        .sort()

    if (numeric && numeric.first() == 0 && numeric == (0..<numeric.size()).toList()) {
        Map<String, String> shifted = [:]
        names.each { String key, String value ->
            shifted[(key.toInteger() + 1).toString()] = value
        }
        debugLog "Shifted zero-based Dahua channel names to one-based channels: ${shifted.keySet().sort()}"
        return shifted
    }

    return names
}

private Map<String, Map> extractRemoteDeviceChannels(Map source) {
    Map<String, Map> byIndex = [:]
    source?.each { String key, Object value ->
        def localNoMatcher = (key =~ /(?:^|.*\.)RemoteDevice\[(\d+)\]\.(?:LocalNo|Channel|ChannelID)/)
        if (localNoMatcher.matches()) {
            String remoteIndex = localNoMatcher.group(1)
            byIndex[remoteIndex] = (byIndex[remoteIndex] ?: [:]) + [channel: normalizeChannel(value)]
        }

        def nameMatcher = (key =~ /(?:^|.*\.)RemoteDevice\[(\d+)\]\.(?:Name|DevName|UserName)/)
        if (nameMatcher.matches()) {
            String remoteIndex = nameMatcher.group(1)
            String text = value?.toString()?.trim()
            if (text) {
                byIndex[remoteIndex] = (byIndex[remoteIndex] ?: [:]) + [name: text]
            }
        }
    }

    Map<String, Map> result = [:]
    byIndex.values().each { Map remote ->
        String channel = remote.channel
        if (channel != null && channel != "") {
            result[channel] = remote
        }
    }
    return result
}

private Set<String> extractChannelIds(Map source) {
    Set<String> ids = [] as Set
    source?.keySet()?.each { String key ->
        def matcher = (key =~ /\[(\d+)\]/)
        if (matcher.find()) {
            ids << matcher.group(1)
        }
    }
    return ids
}

private Map<String, Map> normalizeDiscoveredCameras(Map<String, Map> cameras) {
    if (!cameras) {
        return cameras
    }

    Set<String> positiveChannels = cameras.keySet().findAll { String key ->
        key?.isInteger() && key.toInteger() > 0
    } as Set<String>

    if (positiveChannels) {
        Map<String, Map> filtered = cameras.findAll { String key, Map value ->
            positiveChannels.contains(key)
        } as Map<String, Map>
        debugLog "Discarding channel 0 because positive camera channels were discovered: ${positiveChannels.sort()}"
        return filtered
    }

    return cameras
}

private Map dahuaGetAsMap(String path, boolean logErrors = true) {
    try {
        return dahuaDigestGet(path)
    } catch (Exception e) {
        if (logErrors) {
            log.warn "Dahua GET failed for ${path}: ${e.message}"
        } else {
            debugLog "Optional Dahua GET failed for ${path}: ${e.message}"
        }
        return [:]
    }
}

private Map dahuaDigestGet(String path) {
    String baseUri = "http://${settings.nvrHost}:${settings.nvrPort ?: 80}"
    Map challenge = [:]
    Map parsed = [:]

    try {
        httpGet(uri: baseUri, path: path, timeout: 10) { resp ->
            if (resp?.status == 200) {
                parsed = parseKeyValueBody(resp.data?.text ?: resp.data?.toString())
            }
        }
        if (parsed) {
            return parsed
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode != 401) {
            throw e
        }
        challenge = parseDigestChallengeHeader(e.response?.headers?."WWW-Authenticate"?.value ?: e.response?.headers?."WWW-Authenticate"?.toString())
    }

    if (!challenge) {
        throw new IllegalStateException("Did not receive a Dahua digest authentication challenge for ${path}")
    }

    String nc = "00000001"
    String cnonce = randomHex(16)
    String authorization = buildDigestAuthorization("GET", path, challenge, nc, cnonce)
    parsed = [:]

    httpGet(
        uri: baseUri,
        path: path,
        timeout: 15,
        headers: [Authorization: authorization]
    ) { resp ->
        String rawText = resp.data?.text ?: resp.data?.toString()
        parsed = parseKeyValueBody(rawText)
    }

    debugLog "GET ${path} returned keys: ${parsed.keySet()}"
    return parsed
}

private Map parseDigestChallengeHeader(String headerValue) {
    if (!headerValue?.toLowerCase()?.startsWith("digest ")) {
        return [:]
    }
    String remainder = headerValue.substring(7)
    Map challenge = [:]
    remainder.split(",").each { String token ->
        List<String> parts = token.split("=", 2) as List<String>
        if (parts.size() == 2) {
            String key = parts[0].trim()
            String value = parts[1].trim().replaceAll(/^"|"$/, "")
            challenge[key] = value
        }
    }
    return challenge
}

private String buildDigestAuthorization(String method, String uriPath, Map challenge, String nc, String cnonce) {
    String realm = challenge.realm ?: ""
    String nonce = challenge.nonce ?: ""
    String qop = (challenge.qop ?: "auth").tokenize(",")[0].trim()
    String ha1 = md5Hex("${settings.nvrUsername}:${realm}:${settings.nvrPassword}")
    String ha2 = md5Hex("${method}:${uriPath}")
    String response = md5Hex("${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}")

    return "Digest username=\"${settings.nvrUsername}\", realm=\"${realm}\", nonce=\"${nonce}\", uri=\"${uriPath}\", algorithm=\"MD5\", response=\"${response}\", qop=${qop}, nc=${nc}, cnonce=\"${cnonce}\""
}

private Map parseKeyValueBody(String raw) {
    Map result = [:]
    raw?.split(/\r?\n/)?.each { String line ->
        if (line.contains("=")) {
            List<String> parts = line.split("=", 2) as List<String>
            result[parts[0].trim()] = parts[1].trim()
        }
    }
    return result
}

private String buildCameraLabel(String channel, Map cam) {
    String custom = settings["camName_${channel}"] ?: cam.customName
    if (custom) {
        return custom
    }
    String base = cam.discoveredName ?: "Camera ${channel}"
    String prefix = settings.cameraNamePrefix ?: ""
    return "${prefix}${base}".trim()
}

private String normalizeChannel(Object raw) {
    if (raw == null) {
        return null
    }
    return raw.toString().replaceAll(/[^0-9]/, "")
}

private Integer safeMotionInactiveSeconds() {
    Integer raw = (settings.motionInactiveSeconds ?: 30) as Integer
    return Math.max(5, Math.min(raw, 120))
}

private Map<String, String> supportedMotionEventOptions() {
    [
        "VideoMotion"        : "VideoMotion",
        "SmartMotionHuman"   : "SmartMotionHuman",
        "SmartMotionVehicle" : "SmartMotionVehicle",
        "CrossLineDetection" : "CrossLineDetection",
        "CrossRegionDetection": "CrossRegionDetection",
        "LeftDetection"      : "LeftDetection",
        "TakenAwayDetection" : "TakenAwayDetection",
        "WanderDetection"    : "WanderDetection",
        "RioterDetection"    : "RioterDetection",
        "ParkingDetection"   : "ParkingDetection",
        "MoveDetection"      : "MoveDetection",
        "FaceDetection"      : "FaceDetection"
    ]
}

private def getParentDevice() {
    return getChildDevice(parentDni())
}

private String parentDni() {
    return "dahua-nvr-${app.id}"
}

private String cameraDni(String channel) {
    String serial = state.nvrSerialNumber ?: getParentDevice()?.currentValue("serialNumber") ?: digestFallbackId()
    return "dahua-${serial}-ch-${channel}"
}

private String digestFallbackId() {
    return md5Hex("${settings.nvrHost ?: app.id}:${settings.nvrPort ?: 80}:${settings.nvrUsername ?: ""}")
}

private String md5Hex(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.getBytes("UTF-8"))
    return digest.collect { String.format("%02x", it) }.join()
}

private String randomHex(Integer len) {
    String chars = "0123456789abcdef"
    Random random = new Random()
    return (0..<len).collect { chars.charAt(random.nextInt(chars.length())) }.join()
}

private String nowIso() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
}

private void debugLog(String message) {
    if (settings.enableDebugLogging) {
        log.debug message
    }
}
