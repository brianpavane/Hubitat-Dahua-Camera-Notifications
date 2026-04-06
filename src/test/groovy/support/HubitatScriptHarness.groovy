import groovy.json.JsonSlurper

class HubitatScriptHarness {
    Map state = [:]
    Map settings = [:]
    Map params = [:]
    Map atomicState = [:]
    List<Map> capturedLog = []
    List<List> subscriptions = []
    List<Map> scheduled = []
    List<List> unscheduled = []
    List<Map> httpGetCalls = []
    Map httpGetResponses = [:]
    MockRawSocket rawSocket = new MockRawSocket()
    DeviceWrapper device = new DeviceWrapper(deviceNetworkId: 'test-device', name: 'Test Device', label: 'Test Device')
    Map<String, DeviceWrapper> childDevices = [:]
    Long nowValue = 1_700_000_000_000L

    Script loadScript(String relativePath) {
        def harness = this
        def binding = new Binding()

        binding.state = state
        binding.settings = settings
        binding.params = params
        binding.atomicState = atomicState
        // Alias device.settings to the same Map as the binding settings so that
        // device.updateSetting() writes are visible to the driver via settings.* —
        // matching real Hubitat where both references are the same underlying object.
        device.settings = settings
        binding.device = device
        binding.location = [
            timeZone: TimeZone.getTimeZone('America/New_York'),
            hub     : [localIP: '192.168.1.2']
        ]
        binding.app = [
            id           : 12345,
            updateSetting: { String name, Map valueMap ->
                settings[name] = valueMap.value
            }
        ]
        binding.interfaces = [rawSocket: rawSocket]
        binding.log = [
            debug: { Object msg -> capturedLog << [level: 'DEBUG', msg: msg?.toString()] },
            info : { Object msg -> capturedLog << [level: 'INFO', msg: msg?.toString()] },
            warn : { Object msg -> capturedLog << [level: 'WARN', msg: msg?.toString()] },
            error: { Object msg -> capturedLog << [level: 'ERROR', msg: msg?.toString()] }
        ]

        binding.definition = { Map ignored -> }
        binding.metadata = { Closure closure -> closure?.call() }
        binding.preferences = { Closure closure -> }
        binding.page = { Map ignored -> }
        binding.dynamicPage = { Map ignored, Closure closure ->
            closure?.call()
            return ignored
        }
        binding.section = { Object... args ->
            Closure closure = args.find { it instanceof Closure } as Closure
            closure?.call()
        }
        binding.input = { Object... ignored -> }
        binding.href = { Object... ignored -> }
        binding.paragraph = { Object... ignored -> }
        binding.command = { Object... ignored -> }
        binding.attribute = { Object... ignored -> }
        binding.capability = { Object... ignored -> }
        binding.mappings = { Closure closure -> }
        binding.render = { Map data -> data }

        binding.subscribe = { Object... args -> subscriptions << (args as List) }
        binding.unsubscribe = { Object... ignored -> }
        binding.updateSetting = { String name, Map valueMap ->
            settings[name] = valueMap.value
        }
        binding.unschedule = { Object... args -> unscheduled << (args as List) }
        binding.schedule = { String cron, String methodName ->
            scheduled << [type: 'schedule', cron: cron, method: methodName]
        }
        binding.runIn = { Integer delaySeconds, String methodName ->
            scheduled << [type: 'runIn', delay: delaySeconds, method: methodName]
        }

        binding.getChildDevice = { String dni -> childDevices[dni] }
        binding.getChildDevices = { -> childDevices.values() as List }
        binding.addChildDevice = { String namespace, String typeName, String dni, Map options = [:] ->
            DeviceWrapper child = new DeviceWrapper(
                deviceNetworkId: dni,
                name: options.name ?: typeName,
                label: options.label ?: typeName
            )
            childDevices[dni] = child
            return child
        }

        binding.sendEvent = { Map evt -> device.sendEvent(evt) }
        binding.now = { -> nowValue }

        binding.httpGet = { Map req, Closure callback ->
            String key = "${req.uri ?: ''}${req.path ?: ''}"
            httpGetCalls << [key: key, request: req]
            def response = httpGetResponses[key]
            if (response instanceof Throwable) {
                throw response
            }
            if (response == null) {
                throw new IllegalStateException("No httpGet response stub for ${key}")
            }
            callback(response)
        }

        GroovyShell shell = new GroovyShell(getClass().classLoader, binding)
        return shell.parse(new File(relativePath))
    }

    List<String> logsAt(String level) {
        capturedLog.findAll { it.level == level }.collect { it.msg }
    }

    static Object textResponse(int status, String text) {
        [
            status: status,
            data  : [
                text    : text,
                toString: { -> text }
            ]
        ]
    }

    static Map jsonMap(String text) {
        new JsonSlurper().parseText(text) as Map
    }

    static class MockRawSocket {
        List<Map> connections = []
        List<String> sentMessages = []
        boolean closed = false

        void connect(String hostPort, Map options) {
            connections << [hostPort: hostPort, options: options]
        }

        void connect(Map options, String host, Integer port) {
            connections << [host: host, port: port, options: options]
        }

        void connect(String host, Integer port, Map options = [:]) {
            connections << [host: host, port: port, options: options]
        }

        void sendMessage(String message) {
            sentMessages << message
        }

        void close() {
            closed = true
        }
    }
}
