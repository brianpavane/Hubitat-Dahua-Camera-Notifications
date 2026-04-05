import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest

@Field static final List<Integer> RECONNECT_SCHEDULE_SECONDS = [5, 15, 30, 60]
@Field static final Integer MAX_STREAM_BUFFER_BYTES = 131072
@Field static final List<String> DEFAULT_MOTION_EVENTS = [
    "VideoMotion",
    "SmartMotionHuman",
    "SmartMotionVehicle",
    "CrossLineDetection",
    "CrossRegionDetection"
]

metadata {
    definition(
        name: "Dahua NVR",
        namespace: "bpavane",
        author: "Brian Pavane",
        importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"

        attribute "networkStatus", "string"
        attribute "eventStreamStatus", "string"
        attribute "lastSync", "string"
        attribute "lastEventReceived", "string"
        attribute "cameraCount", "number"
        attribute "model", "string"
        attribute "serialNumber", "string"
        attribute "firmwareVersion", "string"
        attribute "lastDisconnectTime", "string"
        attribute "lastReconnectAttempt", "string"
        attribute "reconnectCount", "number"
        attribute "lastReconnectReason", "string"
        attribute "lastError", "string"
        attribute "rawEvent", "string"

        command "applyConnectionSettings", [[name: "json", type: "STRING"]]
        command "openEventStream"
        command "closeEventStream"
        command "refresh"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    initializeParentState()
}

def updated() {
    initializeParentState()
}

def refresh() {
    updateConnectionStatus("connected", device.currentValue("eventStreamStatus") ?: "disconnected")
}

def applyConnectionSettings(String json) {
    Map config = parseJson(json)
    state.connection = [
        host        : config.host,
        port        : (config.port ?: 80) as Integer,
        username    : config.username,
        password    : config.password,
        serialNumber: config.serialNumber,
        model       : config.model,
        eventCodes  : (config.eventCodes ?: ["All"]) as List<String>,
        debugEnabled: config.debugEnabled == true
    ]

    sendEvent(name: "serialNumber", value: config.serialNumber ?: "")
    sendEvent(name: "model", value: config.model ?: "Dahua NVR")
    sendEvent(name: "cameraCount", value: (config.cameraCount ?: 0) as Integer)
    sendEvent(name: "networkStatus", value: device.currentValue("networkStatus") ?: "disconnected")
    sendEvent(name: "eventStreamStatus", value: device.currentValue("eventStreamStatus") ?: "disconnected")
}

def openEventStream() {
    Map config = state.connection ?: [:]
    if (!config.host || !config.username) {
        updateError("Missing connection settings")
        updateConnectionStatus("disconnected", "stopped")
        return
    }

    unschedule("attemptReconnect")
    state.manualClose = false
    state.streamBuffer = ""
    state.reconnectAttempt = 0
    state.authenticated = false
    state.authAttempted = false
    state.awaitingResponseHeaders = true
    state.lastRequestPath = eventPath()
    connectSocket()
}

def closeEventStream() {
    state.manualClose = true
    try {
        interfaces.rawSocket.close()
    } catch (Exception ignored) {
    }
    updateConnectionStatus("disconnected", "stopped")
}

def initializeParentState() {
    if (device.currentValue("networkStatus") == null) {
        sendEvent(name: "networkStatus", value: "disconnected")
    }
    if (device.currentValue("eventStreamStatus") == null) {
        sendEvent(name: "eventStreamStatus", value: "disconnected")
    }
    if (device.currentValue("reconnectCount") == null) {
        sendEvent(name: "reconnectCount", value: 0)
    }
}

private void connectSocket() {
    Map config = state.connection ?: [:]
    try {
        updateConnectionStatus("reconnecting", "reconnecting")
        interfaces.rawSocket.connect(config.host as String, config.port as Integer, byteInterface: false)
        sendInitialUnauthenticatedRequest()
    } catch (Exception e) {
        updateError("Socket connect failed: ${e.message}")
        scheduleReconnect("socketConnectFailure")
    }
}

private void sendInitialUnauthenticatedRequest() {
    state.awaitingResponseHeaders = true
    state.streamBuffer = ""
    interfaces.rawSocket.sendMessage(buildHttpRequest(eventPath(), null))
}

def parse(String message) {
    if (message == null) {
        return
    }

    state.streamBuffer = (state.streamBuffer ?: "") + message
    if ((state.streamBuffer?.size() ?: 0) > MAX_STREAM_BUFFER_BYTES) {
        updateError("Event stream buffer exceeded ${MAX_STREAM_BUFFER_BYTES} bytes; reconnecting")
        state.streamBuffer = ""
        handleDisconnect("bufferOverflow")
        return
    }

    if (state.awaitingResponseHeaders) {
        int headerTerminator = state.streamBuffer.indexOf("\r\n\r\n")
        if (headerTerminator < 0) {
            return
        }

        String rawHeaders = state.streamBuffer.substring(0, headerTerminator)
        String bodyRemainder = state.streamBuffer.substring(headerTerminator + 4)
        state.streamBuffer = bodyRemainder
        handleHttpHeaders(rawHeaders)
        if (state.awaitingResponseHeaders) {
            return
        }
    }

    processEventBuffer()
}

def socketStatus(String status) {
    debugLog "Socket status: ${status}"
    if (status?.startsWith("receive error:")) {
        updateError(status)
        handleDisconnect("receiveError")
    } else if (status?.startsWith("send error:")) {
        updateError(status)
        handleDisconnect("sendError")
    } else if (status == "status: open") {
        debugLog "Dahua event stream socket opened"
    } else if (status == "status: closed") {
        handleDisconnect("socketClosed")
    }
}

private void handleHttpHeaders(String rawHeaders) {
    List<String> lines = rawHeaders.split(/\r?\n/) as List<String>
    String statusLine = lines ? lines[0] : ""
    Map<String, String> headers = [:]
    lines.drop(1).each { String line ->
        if (line.contains(":")) {
            List<String> parts = line.split(":", 2) as List<String>
            headers[parts[0].trim().toLowerCase()] = parts[1].trim()
        }
    }

    if (statusLine.contains("401")) {
        if (state.authAttempted == true) {
            updateError("Digest authentication failed for Dahua event stream")
            updateConnectionStatus("authFailed", "stopped")
            return
        }
        String challengeHeader = headers["www-authenticate"]
        Map challenge = parseDigestChallengeHeader(challengeHeader)
        if (!challenge) {
            updateError("Received 401 without digest challenge")
            updateConnectionStatus("authFailed", "stopped")
            return
        }
        sendAuthenticatedRequest(challenge)
        return
    }

    if (statusLine.contains("200")) {
        state.awaitingResponseHeaders = false
        state.authenticated = true
        state.authAttempted = false
        state.reconnectAttempt = 0
        updateConnectionStatus("connected", "connected")
        sendEvent(name: "lastError", value: "")
        sendEvent(name: "reconnectCount", value: 0)
        debugLog "Dahua event stream authenticated and active"
        processEventBuffer()
        return
    }

    updateError("Unexpected HTTP response: ${statusLine}")
    handleDisconnect("httpResponse")
}

private void sendAuthenticatedRequest(Map challenge) {
    Map config = state.connection ?: [:]
    String nc = "00000001"
    String cnonce = randomHex(16)
    String authHeader = buildDigestAuthorization(
        "GET",
        eventPath(),
        config.username,
        config.password,
        challenge,
        nc,
        cnonce
    )

    state.awaitingResponseHeaders = true
    state.authAttempted = true
    state.streamBuffer = ""
    interfaces.rawSocket.sendMessage(buildHttpRequest(eventPath(), authHeader))
}

private void processEventBuffer() {
    while (true) {
        String payload = nextEventChunk()
        if (payload == null) {
            return
        }
        Map envelope = parseEventChunk(payload)
        if (!envelope) {
            continue
        }

        if (envelope.code == "Heartbeat") {
            debugLog "Heartbeat received"
            continue
        }

        sendEvent(name: "lastEventReceived", value: nowIso())
        sendEvent(name: "rawEvent", value: JsonOutput.toJson(envelope), isStateChange: true)
        logMotionEventIfNeeded(envelope)
    }
}

private String nextEventChunk() {
    String buffer = state.streamBuffer ?: ""
    int separator = buffer.indexOf("\r\n\r\n")
    if (separator >= 0) {
        String chunk = buffer.substring(0, separator).trim()
        state.streamBuffer = buffer.substring(separator + 4)
        return chunk
    }

    separator = buffer.indexOf("\n\n")
    if (separator >= 0) {
        String chunk = buffer.substring(0, separator).trim()
        state.streamBuffer = buffer.substring(separator + 2)
        return chunk
    }

    if (buffer.contains("Heartbeat")) {
        state.streamBuffer = buffer.replace("Heartbeat", "")
        return "Code=Heartbeat"
    }

    return null
}

private Map parseEventChunk(String payload) {
    Map data = [:]
    payload.split(/\r?\n/).each { String line ->
        if (line.contains("=")) {
            List<String> parts = line.split("=", 2) as List<String>
            data[parts[0].trim()] = parts[1].trim()
        }
    }

    if (!data && payload) {
        debugLog "Unhandled Dahua event chunk shape: ${payload}"
        return [:]
    }

    String code = data.Code ?: data.code ?: data.Event ?: "Unknown"
    String action = normalizeAction(data.action ?: data.Action ?: data.eventAction)
    String channel = normalizeChannel(data.index ?: data.channel ?: data.ChannelID ?: data.channelID)

    Map envelope = [
        code     : code,
        action   : action,
        channel  : channel,
        raw      : data,
        timestamp: nowIso()
    ]

    if (!channel) {
        debugLog "Event code ${code} did not include a clear channel. Raw keys: ${data.keySet()}"
    }

    return envelope
}

private void logMotionEventIfNeeded(Map envelope) {
    String code = envelope.code ?: ""
    if (!isMotionRelated(code)) {
        return
    }

    String action = envelope.action ?: "unknown"
    String channel = envelope.channel ?: "?"
    log.info "Motion event received (ch ${channel}): ${code} ${action}"
}

private boolean isMotionRelated(String code) {
    return DEFAULT_MOTION_EVENTS.contains(code) ||
        [
            "LeftDetection",
            "TakenAwayDetection",
            "WanderDetection",
            "RioterDetection",
            "ParkingDetection",
            "MoveDetection",
            "FaceDetection"
        ].contains(code)
}

private void handleDisconnect(String reason) {
    sendEvent(name: "lastDisconnectTime", value: nowIso())
    sendEvent(name: "lastReconnectReason", value: reason ?: "unknown")

    if (state.manualClose == true) {
        updateConnectionStatus("disconnected", "stopped")
        return
    }

    scheduleReconnect(reason)
}

private void scheduleReconnect(String reason) {
    if (device.currentValue("networkStatus") == "authFailed") {
        return
    }

    Integer nextAttempt = ((state.reconnectAttempt ?: 0) as Integer) + 1
    state.reconnectAttempt = nextAttempt
    Integer delay = nextAttempt <= RECONNECT_SCHEDULE_SECONDS.size() ?
        RECONNECT_SCHEDULE_SECONDS[nextAttempt - 1] :
        300

    sendEvent(name: "reconnectCount", value: nextAttempt)
    sendEvent(name: "lastReconnectAttempt", value: nowIso())
    sendEvent(name: "lastReconnectReason", value: reason ?: "unknown")
    updateConnectionStatus("reconnecting", "reconnecting")

    debugLog "Scheduling reconnect attempt ${nextAttempt} in ${delay}s because ${reason}"
    runIn(delay, "attemptReconnect")
}

def attemptReconnect() {
    if (state.manualClose == true) {
        return
    }
    debugLog "Attempting Dahua event stream reconnect"
    connectSocket()
}

private String buildHttpRequest(String path, String authorizationHeader) {
    Map config = state.connection ?: [:]
    List<String> lines = [
        "GET ${path} HTTP/1.1",
        "Host: ${config.host}:${config.port}",
        "Connection: keep-alive",
        "Accept: */*",
        "User-Agent: Hubitat-Dahua-NVR/1.0"
    ]
    if (authorizationHeader) {
        lines << "Authorization: ${authorizationHeader}"
    }
    return lines.join("\r\n") + "\r\n\r\n"
}

private String eventPath() {
    String codes = (state.connection?.eventCodes ?: ["All"]).join(",")
    return "/cgi-bin/eventManager.cgi?action=attach&codes=[${codes}]&heartbeat=5"
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

private String buildDigestAuthorization(String method, String uriPath, String username, String password, Map challenge, String nc, String cnonce) {
    String realm = challenge.realm ?: ""
    String nonce = challenge.nonce ?: ""
    String qop = (challenge.qop ?: "auth").tokenize(",")[0].trim()
    String ha1 = md5Hex("${username}:${realm}:${password}")
    String ha2 = md5Hex("${method}:${uriPath}")
    String response = md5Hex("${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}")

    return "Digest username=\"${username}\", realm=\"${realm}\", nonce=\"${nonce}\", uri=\"${uriPath}\", algorithm=\"MD5\", response=\"${response}\", qop=${qop}, nc=${nc}, cnonce=\"${cnonce}\""
}

private String md5Hex(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.getBytes("UTF-8"))
    return digest.collect { String.format("%02x", it) }.join()
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

private String normalizeChannel(Object raw) {
    if (raw == null) {
        return null
    }
    String digits = raw.toString().replaceAll(/[^0-9]/, "")
    return digits ? digits : null
}

private String randomHex(Integer len) {
    String chars = "0123456789abcdef"
    Random random = new Random()
    return (0..<len).collect { chars.charAt(random.nextInt(chars.length())) }.join()
}

private void updateConnectionStatus(String networkStatus, String eventStatus) {
    sendEvent(name: "networkStatus", value: networkStatus)
    sendEvent(name: "eventStreamStatus", value: eventStatus)
}

private void updateError(String message) {
    sendEvent(name: "lastError", value: message ?: "")
    log.warn message
}

private Map parseJson(String json) {
    return (json ? new JsonSlurper().parseText(json) : [:]) as Map
}

private String nowIso() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
}

private void debugLog(String message) {
    if (settings.logEnable || state.connection?.debugEnabled) {
        log.debug message
    }
}
