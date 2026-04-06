import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String DRIVER_VERSION = "0.4.1"
@Field static final List<Integer> RECONNECT_SCHEDULE_SECONDS = [5, 15, 30, 60]
@Field static final Integer MAX_STREAM_BUFFER_BYTES = 131072
@Field static final Integer HANDSHAKE_TIMEOUT_SECONDS = 25
@Field static final Integer MAX_TRACE_ENTRIES = 12
@Field static final List<String> EVENT_PATH_CANDIDATES = [
    "/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5",
    "/cgi-bin/eventManager.cgi?action=attach&codes=[All]"
]
@Field static final Map<String, Map> STREAM_REQUEST_MODES = [
    auto             : [connection: "keep-alive", httpVersion: "HTTP/1.1", includeHeartbeat: true],
    keepAliveHeartbeat: [connection: "keep-alive", httpVersion: "HTTP/1.1", includeHeartbeat: true],
    keepAliveNoHeartbeat: [connection: "keep-alive", httpVersion: "HTTP/1.1", includeHeartbeat: false],
    closeHeartbeat   : [connection: "close", httpVersion: "HTTP/1.1", includeHeartbeat: true],
    closeNoHeartbeat : [connection: "close", httpVersion: "HTTP/1.1", includeHeartbeat: false],
    http10NoHeartbeat: [connection: "close", httpVersion: "HTTP/1.0", includeHeartbeat: false]
]
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
        description: "Parent driver for Dahua NVR event streaming. Version ${DRIVER_VERSION}.",
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
        attribute "lastSocketStatus", "string"
        attribute "lastConnectAttempt", "string"
        attribute "lastHttpStatusLine", "string"
        attribute "connectionPhase", "string"
        attribute "lastRequestPath", "string"
        attribute "lastRawMessageSample", "string"
        attribute "lastProbeStatus", "string"
        attribute "lastProbeHttpStatus", "string"
        attribute "lastHeaderSample", "string"
        attribute "lastRequestPreview", "string"
        attribute "streamBufferBytes", "number"
        attribute "rawChunkCount", "number"
        attribute "connectionTrace", "string"
        attribute "lastAttemptSummary", "string"
        attribute "lastConnectMethod", "string"
        attribute "lastRequestMode", "string"
        attribute "lastProbeErrorClass", "string"
        attribute "lastRawSocketTestPath", "string"
        attribute "lastRawSocketTestStatus", "string"
        attribute "lastRawSocketHeaderSample", "string"

        command "applyConnectionSettings", [[name: "json", type: "STRING"]]
        command "openEventStream"
        command "closeEventStream"
        command "refresh"
        command "runRawSocketHttpTest", [[name: "path", type: "STRING"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "nvrPassword", type: "password", title: "NVR Password", required: false
        input name: "streamRequestMode", type: "enum", title: "Stream request mode", options: STREAM_REQUEST_MODES.keySet() as List, defaultValue: "auto", required: false
    }
}

def installed() {
    initializeParentState()
}

def updated() {
    initializeParentState()
}

def refresh() {
    sanitizeConnectionState()
    traceConnection("Refresh requested", "refresh")
    if (device.currentValue("eventStreamStatus") != "connected") {
        openEventStream()
    }
}

def applyConnectionSettings(String json) {
    Map config = parseJson(json)
    updateSetting("nvrPassword", [value: config.password ?: "", type: "password"])
    state.connection = [
        host        : config.host,
        port        : (config.port ?: 80) as Integer,
        username    : config.username,
        serialNumber: config.serialNumber,
        model       : config.model,
        eventCodes  : (config.eventCodes ?: ["All"]) as List<String>,
        debugEnabled: config.debugEnabled == true,
        requestMode : normalizeRequestMode(config.requestMode ?: settings.streamRequestMode)
    ]
    sanitizeConnectionState()

    sendEvent(name: "serialNumber", value: config.serialNumber ?: "")
    sendEvent(name: "model", value: config.model ?: "Dahua NVR")
    sendEvent(name: "cameraCount", value: (config.cameraCount ?: 0) as Integer)
    sendEvent(name: "lastRequestMode", value: state.connection.requestMode ?: "auto")
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
    unschedule("forceInitialRequestIfPending")
    unschedule("handshakeWatchdog")
    state.manualClose = false
    state.streamBuffer = ""
    state.reconnectAttempt = 0
    state.authenticated = false
    state.authAttempted = false
    state.awaitingResponseHeaders = true
    state.pendingInitialRequest = true
    state.precomputedAuthorization = null
    state.operationMode = "stream"
    state.rawSocketTestPath = null
    state.eventPathIndex = ((state.eventPathIndex ?: 0) as Integer)
    state.lastRequestPath = eventPath()
    state.rawChunkCount = 0
    sendEvent(name: "lastRequestPath", value: state.lastRequestPath)
    sendEvent(name: "lastConnectAttempt", value: nowIso())
    sendEvent(name: "lastRequestMode", value: effectiveRequestMode())
    sendEvent(name: "rawChunkCount", value: 0)
    sendEvent(name: "streamBufferBytes", value: 0)
    sendEvent(name: "lastAttemptSummary", value: "")
    traceConnection("Opening event stream using path ${state.lastRequestPath}", "open_event_stream")
    prepareEventRequest()
    sendEvent(name: "connectionPhase", value: "opening_socket")
    connectSocket()
}

def runRawSocketHttpTest(String path = "/cgi-bin/magicBox.cgi?action=getSystemInfo") {
    Map config = state.connection ?: [:]
    if (!config.host || !config.username) {
        updateError("Missing connection settings")
        return
    }

    unschedule("attemptReconnect")
    unschedule("forceInitialRequestIfPending")
    unschedule("handshakeWatchdog")
    state.manualClose = false
    state.streamBuffer = ""
    state.awaitingResponseHeaders = true
    state.pendingInitialRequest = false
    state.precomputedAuthorization = null
    state.operationMode = "raw_socket_test"
    state.rawSocketTestPath = path ?: "/cgi-bin/magicBox.cgi?action=getSystemInfo"
    state.rawChunkCount = 0
    sendEvent(name: "lastRawSocketTestPath", value: state.rawSocketTestPath)
    sendEvent(name: "lastRawSocketTestStatus", value: "starting")
    sendEvent(name: "lastRequestPath", value: state.rawSocketTestPath)
    sendEvent(name: "lastConnectAttempt", value: nowIso())
    sendEvent(name: "lastRequestMode", value: effectiveRequestMode())
    sendEvent(name: "rawChunkCount", value: 0)
    sendEvent(name: "streamBufferBytes", value: 0)
    sendEvent(name: "lastAttemptSummary", value: "Raw socket test pending for ${state.rawSocketTestPath}")
    traceConnection("Starting raw socket HTTP test for ${state.rawSocketTestPath}", "raw_socket_test")
    prepareTestRequest()
    sendEvent(name: "connectionPhase", value: "opening_socket")
    connectSocket()
}

def closeEventStream() {
    state.manualClose = true
    unschedule("forceInitialRequestIfPending")
    unschedule("handshakeWatchdog")
    try {
        interfaces.rawSocket.close()
    } catch (Exception ignored) {
    }
    sendEvent(name: "connectionPhase", value: "stopped")
    updateConnectionStatus("disconnected", "stopped")
}

def initializeParentState() {
    sanitizeConnectionState()
    if (device.currentValue("networkStatus") == null) {
        sendEvent(name: "networkStatus", value: "disconnected")
    }
    if (device.currentValue("eventStreamStatus") == null) {
        sendEvent(name: "eventStreamStatus", value: "disconnected")
    }
    if (device.currentValue("reconnectCount") == null) {
        sendEvent(name: "reconnectCount", value: 0)
    }
    if (device.currentValue("cameraCount") == null) {
        sendEvent(name: "cameraCount", value: 0)
    }
    if (device.currentValue("streamBufferBytes") == null) {
        sendEvent(name: "streamBufferBytes", value: 0)
    }
    if (device.currentValue("rawChunkCount") == null) {
        sendEvent(name: "rawChunkCount", value: 0)
    }
    if (device.currentValue("lastRequestMode") == null) {
        sendEvent(name: "lastRequestMode", value: normalizeRequestMode(settings.streamRequestMode))
    }
}

private void prepareEventRequest() {
    Map config = state.connection ?: [:]
    if (!config.host || !config.username) {
        return
    }

    sendEvent(name: "connectionPhase", value: "probing_auth")
    sendEvent(name: "lastProbeStatus", value: "starting")
    sendEvent(name: "lastProbeHttpStatus", value: "")
    sendEvent(name: "lastProbeErrorClass", value: "")
    String path = eventPath()
    traceConnection("Probing auth via HTTP for ${path}", "probing_auth")

    try {
        httpGet(uri: "http://${config.host}:${config.port ?: 80}", path: path, timeout: 10) { resp ->
            sendEvent(name: "lastProbeHttpStatus", value: "${resp?.status ?: ''}")
            if (resp?.status == 200) {
                state.precomputedAuthorization = null
                sendEvent(name: "lastProbeStatus", value: "http_200_no_challenge")
                traceConnection("HTTP probe returned 200 without digest challenge", "probing_auth")
            }
        }
        return
    } catch (Exception e) {
        Integer statusCode = extractStatusCode(e)
        sendEvent(name: "lastProbeHttpStatus", value: statusCode != null ? statusCode.toString() : "")
        sendEvent(name: "lastProbeErrorClass", value: exceptionClassName(e))
        if (statusCode == 401) {
            String headerValue = extractHeaderValue(e, "WWW-Authenticate")
            Map challenge = parseDigestChallengeHeader(headerValue)
            if (challenge) {
                String nc = "00000001"
                String cnonce = randomHex(16)
                String password = settings.nvrPassword ?: ""
                state.precomputedAuthorization = buildDigestAuthorization(
                    "GET",
                    path,
                    config.username,
                    password,
                    challenge,
                    nc,
                    cnonce
                )
                sendEvent(name: "lastProbeStatus", value: "digest_challenge_received")
                traceConnection("Prepared digest auth from HTTP probe challenge", "probing_auth")
                return
            }
            sendEvent(name: "lastProbeStatus", value: "http_401_without_digest")
            traceConnection("HTTP probe returned 401 without a usable digest challenge", "probing_auth")
            return
        }

        sendEvent(name: "lastProbeStatus", value: "probe_failed")
        traceConnection("HTTP probe failed for ${path}: ${e.message}", "probing_auth")
    }
}

private void prepareTestRequest() {
    Map config = state.connection ?: [:]
    String path = state.rawSocketTestPath ?: "/cgi-bin/magicBox.cgi?action=getSystemInfo"
    sendEvent(name: "lastProbeStatus", value: "starting")
    sendEvent(name: "lastProbeHttpStatus", value: "")
    sendEvent(name: "lastProbeErrorClass", value: "")
    traceConnection("Probing auth via HTTP for test path ${path}", "raw_socket_test")

    try {
        httpGet(uri: "http://${config.host}:${config.port ?: 80}", path: path, timeout: 10) { resp ->
            sendEvent(name: "lastProbeHttpStatus", value: "${resp?.status ?: ''}")
            if (resp?.status == 200) {
                state.precomputedAuthorization = null
                sendEvent(name: "lastProbeStatus", value: "http_200_no_challenge")
                traceConnection("HTTP probe returned 200 for raw socket test", "raw_socket_test")
            }
        }
        return
    } catch (Exception e) {
        Integer statusCode = extractStatusCode(e)
        sendEvent(name: "lastProbeHttpStatus", value: statusCode != null ? statusCode.toString() : "")
        sendEvent(name: "lastProbeErrorClass", value: exceptionClassName(e))
        if (statusCode == 401) {
            String headerValue = extractHeaderValue(e, "WWW-Authenticate")
            Map challenge = parseDigestChallengeHeader(headerValue)
            if (challenge) {
                String nc = "00000001"
                String cnonce = randomHex(16)
                String password = settings.nvrPassword ?: ""
                state.precomputedAuthorization = buildDigestAuthorization("GET", path, config.username, password, challenge, nc, cnonce)
                sendEvent(name: "lastProbeStatus", value: "digest_challenge_received")
                traceConnection("Prepared digest auth for raw socket test", "raw_socket_test")
                return
            }
        }
        sendEvent(name: "lastProbeStatus", value: "probe_failed")
        traceConnection("HTTP probe failed for raw socket test ${path}: ${e.message}", "raw_socket_test")
    }
}

private void connectSocket() {
    Map config = state.connection ?: [:]
    try {
        updateConnectionStatus("reconnecting", "reconnecting")
        traceConnection("Opening raw socket to ${config.host}:${config.port}", "opening_socket")
        try {
            interfaces.rawSocket.connect("${config.host}:${config.port}", byteInterface: false)
            sendEvent(name: "lastConnectMethod", value: "hostPort")
            traceConnection("Raw socket connect invoked using host:port form", "opening_socket")
        } catch (MissingMethodException ignored) {
            interfaces.rawSocket.connect(config.host as String, config.port as Integer, byteInterface: false)
            sendEvent(name: "lastConnectMethod", value: "host_port")
            traceConnection("Raw socket connect invoked using host,port form", "opening_socket")
        }
        // Some Hubitat environments do not reliably emit `status: open`; this fallback
        // pushes the initial HTTP request if the socket stays pending for too long.
        runIn(1, "forceInitialRequestIfPending")
    } catch (Exception e) {
        updateError("Socket connect failed: ${e.message}")
        scheduleReconnect("socketConnectFailure")
    }
}

private void sendInitialUnauthenticatedRequest() {
    state.awaitingResponseHeaders = true
    state.streamBuffer = ""
    sendEvent(name: "streamBufferBytes", value: 0)
    String authorizationHeader = state.precomputedAuthorization as String
    String phase = authorizationHeader ? "sending_preauthenticated_request" : "sending_initial_request"
    sendEvent(name: "connectionPhase", value: phase)
    String request = buildHttpRequest(eventPath(), authorizationHeader)
    sendEvent(name: "lastRequestPreview", value: abbreviate(redactSensitive(request), 200))
    traceConnection("Sending event-stream request${authorizationHeader ? ' with precomputed digest auth' : ''}", phase)
    sendEvent(name: "lastAttemptSummary", value: "Stream request sent using mode ${effectiveRequestMode()} to ${eventPath()}")
    interfaces.rawSocket.sendMessage(request)
    runIn(HANDSHAKE_TIMEOUT_SECONDS, "handshakeWatchdog")
}

private void sendRawSocketTestRequest() {
    state.awaitingResponseHeaders = true
    state.streamBuffer = ""
    sendEvent(name: "streamBufferBytes", value: 0)
    String path = state.rawSocketTestPath ?: "/cgi-bin/magicBox.cgi?action=getSystemInfo"
    String authorizationHeader = state.precomputedAuthorization as String
    sendEvent(name: "connectionPhase", value: "sending_test_request")
    String request = buildHttpRequest(path, authorizationHeader)
    sendEvent(name: "lastRequestPreview", value: abbreviate(redactSensitive(request), 200))
    sendEvent(name: "lastRawSocketTestPath", value: path)
    sendEvent(name: "lastRawSocketTestStatus", value: "request_sent")
    sendEvent(name: "lastAttemptSummary", value: "Raw socket test request sent using mode ${effectiveRequestMode()} to ${path}")
    traceConnection("Sending raw socket test request${authorizationHeader ? ' with precomputed digest auth' : ''}", "sending_test_request")
    interfaces.rawSocket.sendMessage(request)
    runIn(HANDSHAKE_TIMEOUT_SECONDS, "handshakeWatchdog")
}

def forceInitialRequestIfPending() {
    if (state.pendingInitialRequest == true && state.manualClose != true) {
        traceConnection("Socket open status not observed promptly; forcing initial request", "forcing_initial_request")
        state.pendingInitialRequest = false
        sendInitialUnauthenticatedRequest()
    }
}

def handshakeWatchdog() {
    if (state.awaitingResponseHeaders == true && state.manualClose != true) {
        traceConnection("Timed out waiting for Dahua event-stream HTTP response headers", "handshake_timeout")
        updateError("Timed out waiting for Dahua event-stream HTTP response headers")
        handleDisconnect("handshakeTimeout")
    }
}

def parse(String message) {
    if (message == null) {
        return
    }
    state.rawChunkCount = ((state.rawChunkCount ?: 0) as Integer) + 1
    sendEvent(name: "rawChunkCount", value: state.rawChunkCount)
    sendEvent(name: "lastRawMessageSample", value: abbreviate(redactSensitive(message), 120))

    state.streamBuffer = (state.streamBuffer ?: "") + message
    sendEvent(name: "streamBufferBytes", value: (state.streamBuffer?.size() ?: 0))
    if ((state.streamBuffer?.size() ?: 0) > MAX_STREAM_BUFFER_BYTES) {
        updateError("Event stream buffer exceeded ${MAX_STREAM_BUFFER_BYTES} bytes; reconnecting")
        state.streamBuffer = ""
        sendEvent(name: "streamBufferBytes", value: 0)
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
    String sanitizedStatus = redactSensitive(status)
    traceConnection("Socket status: ${sanitizedStatus}", "socket_status")
    sendEvent(name: "lastSocketStatus", value: sanitizedStatus ?: "")
    if (status?.startsWith("receive error:")) {
        updateError(status)
        handleDisconnect("receiveError")
    } else if (status?.startsWith("send error:")) {
        updateError(status)
        handleDisconnect("sendError")
    } else if (status == "status: open") {
        traceConnection("Dahua event stream socket opened", "socket_open")
        sendEvent(name: "connectionPhase", value: "socket_open")
        if (state.operationMode == "raw_socket_test") {
            sendRawSocketTestRequest()
        } else if (state.pendingInitialRequest == true) {
            state.pendingInitialRequest = false
            sendInitialUnauthenticatedRequest()
        }
    } else if (status == "status: closed") {
        handleDisconnect("socketClosed")
    }
}

private void handleHttpHeaders(String rawHeaders) {
    List<String> lines = rawHeaders.split(/\r?\n/) as List<String>
    String statusLine = lines ? lines[0] : ""
    sendEvent(name: "lastHeaderSample", value: abbreviate(redactSensitive(rawHeaders), 200))
    sendEvent(name: "lastHttpStatusLine", value: statusLine ?: "")
    traceConnection("Received HTTP status line ${statusLine}", "http_headers")
    if (state.operationMode == "raw_socket_test") {
        sendEvent(name: "lastRawSocketTestStatus", value: statusLine ?: "headers_received")
        sendEvent(name: "lastRawSocketHeaderSample", value: abbreviate(redactSensitive(rawHeaders), 200))
        sendEvent(name: "lastAttemptSummary", value: "Raw socket test received ${statusLine ?: 'headers'}")
    }
    Map<String, String> headers = [:]
    lines.drop(1).each { String line ->
        if (line.contains(":")) {
            List<String> parts = line.split(":", 2) as List<String>
            headers[parts[0].trim().toLowerCase()] = parts[1].trim()
        }
    }

    if (statusLine.contains("401")) {
        sendEvent(name: "connectionPhase", value: "auth_challenge")
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
        unschedule("handshakeWatchdog")
        if (state.operationMode == "raw_socket_test") {
            sendEvent(name: "lastRawSocketTestStatus", value: "success_200")
            sendEvent(name: "lastAttemptSummary", value: "Raw socket test succeeded with ${statusLine}")
            traceConnection("Raw socket HTTP test received a successful response", "raw_socket_test_success")
            state.manualClose = true
            closeEventStream()
            return
        }
        updateConnectionStatus("connected", "connected")
        sendEvent(name: "connectionPhase", value: "stream_connected")
        sendEvent(name: "lastError", value: "")
        sendEvent(name: "reconnectCount", value: 0)
        sendEvent(name: "lastAttemptSummary", value: "Stream connected successfully using mode ${effectiveRequestMode()} on ${eventPath()}")
        traceConnection("Dahua event stream authenticated and active", "stream_connected")
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
    String password = settings.nvrPassword ?: ""
    String authHeader = buildDigestAuthorization(
        "GET",
        eventPath(),
        config.username,
        password,
        challenge,
        nc,
        cnonce
    )

    state.awaitingResponseHeaders = true
    state.authAttempted = true
    state.streamBuffer = ""
    sendEvent(name: "streamBufferBytes", value: 0)
    sendEvent(name: "connectionPhase", value: "sending_authenticated_request")
    String request = buildHttpRequest(eventPath(), authHeader)
    sendEvent(name: "lastRequestPreview", value: abbreviate(redactSensitive(request), 200))
    traceConnection("Responding to Dahua digest challenge", "sending_authenticated_request")
    interfaces.rawSocket.sendMessage(request)
    runIn(HANDSHAKE_TIMEOUT_SECONDS, "handshakeWatchdog")
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
            traceConnection("Heartbeat received", "heartbeat")
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
        sendEvent(name: "streamBufferBytes", value: (state.streamBuffer?.size() ?: 0))
        return chunk
    }

    separator = buffer.indexOf("\n\n")
    if (separator >= 0) {
        String chunk = buffer.substring(0, separator).trim()
        state.streamBuffer = buffer.substring(separator + 2)
        sendEvent(name: "streamBufferBytes", value: (state.streamBuffer?.size() ?: 0))
        return chunk
    }

    if (buffer.contains("Heartbeat")) {
        state.streamBuffer = buffer.replace("Heartbeat", "")
        sendEvent(name: "streamBufferBytes", value: (state.streamBuffer?.size() ?: 0))
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
        traceConnection("Unhandled event chunk shape observed", "event_parse")
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
        traceConnection("Event ${code} did not include a clear channel", "event_parse")
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
    traceConnection("Handling disconnect because ${reason}", "disconnect")
    sendEvent(name: "lastDisconnectTime", value: nowIso())
    sendEvent(name: "lastReconnectReason", value: reason ?: "unknown")
    sendEvent(name: "connectionPhase", value: "disconnected_${reason ?: 'unknown'}")
    state.pendingInitialRequest = false
    unschedule("forceInitialRequestIfPending")
    unschedule("handshakeWatchdog")

    if (state.manualClose == true) {
        updateConnectionStatus("disconnected", "stopped")
        return
    }

    if (state.operationMode == "raw_socket_test") {
        sendEvent(name: "lastRawSocketTestStatus", value: "failed_${reason}")
        sendEvent(name: "lastAttemptSummary", value: "Raw socket test failed because ${reason}")
        updateConnectionStatus("disconnected", "stopped")
        return
    }

    if (reason == "handshakeTimeout" && tryAlternateEventPath()) {
        return
    }

    scheduleReconnect(reason)
}

private boolean tryAlternateEventPath() {
    Integer currentIndex = ((state.eventPathIndex ?: 0) as Integer)
    Integer nextIndex = currentIndex + 1
    if (nextIndex >= EVENT_PATH_CANDIDATES.size()) {
        return false
    }

    state.eventPathIndex = nextIndex
    state.precomputedAuthorization = null
    state.pendingInitialRequest = true
    state.awaitingResponseHeaders = false
    state.streamBuffer = ""
    state.authAttempted = false
    state.authenticated = false
    state.lastRequestPath = eventPath()
    sendEvent(name: "lastRequestPath", value: state.lastRequestPath)
    sendEvent(name: "connectionPhase", value: "retrying_alternate_event_path")
    traceConnection("Retrying alternate event path ${state.lastRequestPath}", "alternate_path")
    openEventStream()
    return true
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
    sendEvent(name: "connectionPhase", value: "scheduled_reconnect")
    updateConnectionStatus("reconnecting", "reconnecting")

    traceConnection("Scheduling reconnect attempt ${nextAttempt} in ${delay}s because ${reason}", "scheduled_reconnect")
    runIn(delay, "attemptReconnect")
}

def attemptReconnect() {
    if (state.manualClose == true) {
        return
    }
    traceConnection("Attempting Dahua event stream reconnect", "reconnecting")
    sendEvent(name: "connectionPhase", value: "reconnecting")
    connectSocket()
}

private String buildHttpRequest(String path, String authorizationHeader) {
    Map config = state.connection ?: [:]
    Map mode = currentRequestModeSpec()
    List<String> lines = [
        "GET ${path} ${mode.httpVersion}",
        "Host: ${config.host}:${config.port}",
        "Connection: ${mode.connection}",
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
    List<String> candidates = currentEventPathCandidates(codes)
    Integer index = ((state.eventPathIndex ?: 0) as Integer)
    if (index < candidates.size()) {
        return candidates[index]
    }
    return candidates[0]
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

private String abbreviate(String value, Integer maxLen) {
    if (value == null) {
        return ""
    }
    if (value.length() <= maxLen) {
        return value
    }
    return value.substring(0, maxLen) + "..."
}

private String normalizeRequestMode(Object raw) {
    String mode = raw?.toString()
    return STREAM_REQUEST_MODES.containsKey(mode) ? mode : "auto"
}

private String effectiveRequestMode() {
    return normalizeRequestMode(state.connection?.requestMode ?: settings.streamRequestMode)
}

private Map currentRequestModeSpec() {
    return STREAM_REQUEST_MODES[effectiveRequestMode()] ?: STREAM_REQUEST_MODES.auto
}

private List<String> currentEventPathCandidates(String codes) {
    Map mode = currentRequestModeSpec()
    List<String> candidates = []
    if (mode.includeHeartbeat == true) {
        candidates << "/cgi-bin/eventManager.cgi?action=attach&codes=[${codes}]&heartbeat=5"
    }
    candidates << "/cgi-bin/eventManager.cgi?action=attach&codes=[${codes}]"
    return candidates.unique()
}

private void sanitizeConnectionState() {
    if (state.connection instanceof Map && state.connection.containsKey("password")) {
        state.connection = (state.connection.findAll { String key, Object value ->
            key != "password"
        } as Map)
    }
}

private void updateConnectionStatus(String networkStatus, String eventStatus) {
    sendEvent(name: "networkStatus", value: networkStatus)
    sendEvent(name: "eventStreamStatus", value: eventStatus)
}

private void updateError(String message) {
    String sanitized = redactSensitive(message)
    sendEvent(name: "lastError", value: sanitized ?: "")
    log.warn sanitized
}

private void traceConnection(String message, String phase = null) {
    String sanitized = redactSensitive(message)
    if (phase) {
        sendEvent(name: "connectionPhase", value: phase)
    }
    List<String> entries = (state.connectionTraceEntries ?: []) as List<String>
    entries << "${nowIso()} ${sanitized}"
    if (entries.size() > MAX_TRACE_ENTRIES) {
        entries = entries.takeRight(MAX_TRACE_ENTRIES)
    }
    state.connectionTraceEntries = entries
    sendEvent(name: "connectionTrace", value: entries.join("\n"))
    debugLog sanitized
}

private Map parseJson(String json) {
    return (json ? new JsonSlurper().parseText(json) : [:]) as Map
}

private Integer extractStatusCode(Exception e) {
    try {
        return e?.statusCode as Integer
    } catch (Exception ignored) {
        return null
    }
}

private String extractHeaderValue(Exception e, String headerName) {
    try {
        def headers = e?.response?.headers
        def header = headers?."${headerName}"
        return header?.value ?: header?.toString()
    } catch (Exception ignored) {
        return null
    }
}

private String exceptionClassName(Exception e) {
    try {
        return e?.class?.name ?: ""
    } catch (Exception ignored) {
        return ""
    }
}

private String nowIso() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
}

private void debugLog(String message) {
    if (settings.logEnable || state.connection?.debugEnabled) {
        log.debug redactSensitive(message)
    }
}

private String redactSensitive(String value) {
    if (value == null) {
        return ""
    }

    String sanitized = value
    String password = settings.nvrPassword ?: ""
    if (password) {
        sanitized = sanitized.replace(password, "********")
    }

    sanitized = sanitized.replaceAll(/Authorization:\s*Digest[^\r\n]*/, "Authorization: Digest [REDACTED]")
    sanitized = sanitized.replaceAll(/(["']?password["']?\s*[:=]\s*["'])[^"']*(["'])/, "\$1********\$2")
    sanitized = sanitized.replaceAll(/(["']?nvrPassword["']?\s*[:=]\s*["'])[^"']*(["'])/, "\$1********\$2")

    return sanitized
}
