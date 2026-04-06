import groovy.json.JsonSlurper
import spock.lang.Specification

class DahuaNVRSyncAppSpec extends Specification {

    def "discovery stages cameras without creating child devices until apply"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.enableDebugLogging = true

        stubDiscoveryResponses(harness)

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')

        when:
        app.discoverCameras()

        then:
        harness.state.discoveredCameras.keySet() == ['1', '2'] as Set
        harness.childDevices.keySet() == ['dahua-nvr-12345'] as Set
        harness.logsAt('INFO').any { it.contains('Dahua discovery staged: 2 cameras') }

        when:
        harness.settings.camName_1 = 'Front Door Custom'
        harness.settings.camEnabled_2 = false
        app.applyConfiguredCameras()

        then:
        harness.childDevices.keySet().containsAll(['dahua-nvr-12345', 'dahua-SERIAL1-ch-1'])
        !harness.childDevices.keySet().contains('dahua-SERIAL1-ch-2')
        harness.childDevices['dahua-SERIAL1-ch-1'].label == 'Front Door Custom'
        harness.childDevices.findAll { it.key.contains('-ch-1') }.size() == 1
        harness.logsAt('DEBUG').any { it.contains('Not creating child device for disabled camera') }
    }

    def "apply refreshes parent camera count from staged discovered cameras"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')
        def parent = new DeviceWrapper(deviceNetworkId: 'dahua-nvr-12345', label: 'Dahua NVR')
        parent.currentValues.cameraCount = 1
        harness.childDevices['dahua-nvr-12345'] = parent
        harness.state.nvrSerialNumber = 'SERIAL1'
        harness.state.nvrModel = 'Dahua NVR'
        harness.state.discoveredCameras = [
            '1': [channel: '1', discoveredName: 'Cam 1', enabled: true, stale: false],
            '2': [channel: '2', discoveredName: 'Cam 2', enabled: false, stale: false],
            '3': [channel: '3', discoveredName: 'Cam 3', enabled: true, stale: false]
        ]

        when:
        app.applyConfiguredCameras()

        then:
        parent.commandCalls.find { it.name == 'applyConnectionSettings' } != null
        def payload = new JsonSlurper().parseText(parent.commandCalls.find { it.name == 'applyConnectionSettings' }.json as String) as Map
        payload.cameraCount == 3
        parent.currentValue('cameraCount') == 3
    }

    def "parent raw events are routed to the matching child with motion settings applied"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.globalMotionEvents = ['VideoMotion', 'SmartMotionHuman']

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')
        String parentDni = "dahua-nvr-12345"
        String childDni = "dahua-SERIAL1-ch-1"

        def parent = new DeviceWrapper(deviceNetworkId: parentDni, label: 'Dahua NVR')
        parent.currentValues.serialNumber = 'SERIAL1'
        def child = new DeviceWrapper(deviceNetworkId: childDni, label: 'Driveway')
        harness.childDevices[parentDni] = parent
        harness.childDevices[childDni] = child
        harness.state.discoveredCameras = [
            '1': [channel: '1', discoveredName: 'Driveway', enabled: true]
        ]

        when:
        app.handleParentRawEvent([value: '{"code":"VideoMotion","action":"start","channel":"1","timestamp":"2026-04-05T09:00:00-0400"}'])

        then:
        child.commandCalls.size() == 1
        child.commandCalls[0].name == 'applyDahuaEvent'
        def payload = new JsonSlurper().parseText(child.commandCalls[0].json as String) as Map
        payload.code == 'VideoMotion'
        payload.channel == '1'
        payload.motionDrivingEventTypes == ['VideoMotion', 'SmartMotionHuman']
        payload.motionInactiveSeconds == 30
    }

    def "events for disabled cameras are ignored"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.globalMotionEvents = ['VideoMotion']
        harness.settings.enableDebugLogging = true

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')
        String parentDni = "dahua-nvr-12345"
        String childDni = "dahua-SERIAL1-ch-1"

        def parent = new DeviceWrapper(deviceNetworkId: parentDni, label: 'Dahua NVR')
        parent.currentValues.serialNumber = 'SERIAL1'
        def child = new DeviceWrapper(deviceNetworkId: childDni, label: 'Driveway')
        harness.childDevices[parentDni] = parent
        harness.childDevices[childDni] = child
        harness.state.discoveredCameras = [
            '1': [channel: '1', discoveredName: 'Driveway', enabled: false]
        ]

        when:
        app.handleParentRawEvent([value: '{"code":"VideoMotion","action":"start","channel":"1","timestamp":"2026-04-05T09:00:00-0400"}'])

        then:
        child.commandCalls.isEmpty()
        harness.logsAt('DEBUG').any { it.contains('Ignoring event for disabled camera') }
    }

    def "channel name extraction supports Dahua table-prefixed config keys"() {
        given:
        def harness = new HubitatScriptHarness()
        def app = harness.loadScript('DahuaNVRSyncApp.groovy')

        when:
        Map result = app.invokeMethod('extractChannelNames', [[
            'table.ChannelTitle[1].Name': 'Front Door',
            'table.ChannelTitle[2].Name': 'Driveway',
            'table.VideoWidget[3].Text' : 'Garage'
        ]] as Object[])

        then:
        result == [
            '1': 'Front Door',
            '2': 'Driveway',
            '3': 'Garage'
        ]
    }

    def "zero-based channel names are shifted to one-based camera numbers"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.enableDebugLogging = true
        def app = harness.loadScript('DahuaNVRSyncApp.groovy')

        when:
        Map result = app.invokeMethod('normalizeNameChannels', [[
            '0': 'Cam One',
            '1': 'Cam Two',
            '2': 'Cam Three'
        ]] as Object[])

        then:
        result == [
            '1': 'Cam One',
            '2': 'Cam Two',
            '3': 'Cam Three'
        ]
        harness.logsAt('DEBUG').any { it.contains('Shifted zero-based Dahua channel names') }
    }

    def "discovery detects zero-based channel names and stores channelIndexOffset"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.enableDebugLogging = true

        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/magicBox.cgi?action=getSystemInfo'] =
            HubitatScriptHarness.textResponse(200, 'serialNumber=SERIAL1\ndeviceType=NVR\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/magicBox.cgi?action=getDeviceType'] =
            HubitatScriptHarness.textResponse(200, 'type=Dahua NVR\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle'] =
            HubitatScriptHarness.textResponse(200, 'table.ChannelTitle[0].Name=Front Door\ntable.ChannelTitle[1].Name=Driveway\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=VideoWidget'] =
            HubitatScriptHarness.textResponse(200, '')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=RemoteDevice'] =
            HubitatScriptHarness.textResponse(200, '')

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')

        when:
        app.discoverCameras()

        then:
        harness.state.channelIndexOffset == 1
        harness.state.discoveredCameras.keySet() == ['1', '2'] as Set
        harness.logsAt('DEBUG').any { it.contains('Channel index offset: 1') }
    }

    def "channel index offset routes zero-based event indexes to one-based discovered cameras"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.globalMotionEvents = ['VideoMotion']
        harness.settings.enableDebugLogging = true

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')
        def child = new DeviceWrapper(deviceNetworkId: 'dahua-SERIAL1-ch-1', label: 'Front Door')
        harness.childDevices['dahua-nvr-12345'] = new DeviceWrapper(deviceNetworkId: 'dahua-nvr-12345', label: 'Dahua NVR')
        harness.childDevices['dahua-SERIAL1-ch-1'] = child
        harness.state.nvrSerialNumber = 'SERIAL1'
        harness.state.channelIndexOffset = 1
        harness.state.discoveredCameras = ['1': [channel: '1', discoveredName: 'Front Door', enabled: true]]

        when:
        app.handleParentRawEvent([value: '{"code":"VideoMotion","action":"start","channel":"0","timestamp":"2026-04-05T09:00:00-0400"}'])

        then:
        child.commandCalls.size() == 1
        child.commandCalls[0].name == 'applyDahuaEvent'
    }

    def "duplicate events within 500ms are filtered by channel fingerprint"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.nvrHost = '192.168.1.10'
        harness.settings.nvrPort = 80
        harness.settings.nvrUsername = 'admin'
        harness.settings.nvrPassword = 'secret'
        harness.settings.motionInactiveSeconds = 30
        harness.settings.globalMotionEvents = ['VideoMotion']
        harness.settings.enableDebugLogging = true

        def app = harness.loadScript('DahuaNVRSyncApp.groovy')
        def child = new DeviceWrapper(deviceNetworkId: 'dahua-SERIAL1-ch-1', label: 'Driveway')
        harness.childDevices['dahua-nvr-12345'] = new DeviceWrapper(deviceNetworkId: 'dahua-nvr-12345', label: 'Dahua NVR')
        harness.childDevices['dahua-SERIAL1-ch-1'] = child
        harness.state.nvrSerialNumber = 'SERIAL1'
        harness.state.discoveredCameras = ['1': [channel: '1', discoveredName: 'Driveway', enabled: true]]

        when:
        app.handleParentRawEvent([value: '{"code":"VideoMotion","action":"start","channel":"1","timestamp":"2026-04-05T09:00:00-0400"}'])
        app.handleParentRawEvent([value: '{"code":"VideoMotion","action":"start","channel":"1","timestamp":"2026-04-05T09:00:00-0400"}'])

        then:
        child.commandCalls.size() == 1
        harness.logsAt('DEBUG').any { it.contains('Dropping duplicate event') }
    }

    def "channel zero is discarded when positive camera channels are discovered"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.settings.enableDebugLogging = true
        def app = harness.loadScript('DahuaNVRSyncApp.groovy')

        when:
        Map result = app.invokeMethod('normalizeDiscoveredCameras', [[
            '0': [channel: '0', discoveredName: 'NVR'],
            '1': [channel: '1', discoveredName: 'Cam 1'],
            '2': [channel: '2', discoveredName: 'Cam 2']
        ]] as Object[])

        then:
        result.keySet() == ['1', '2'] as Set
        harness.logsAt('DEBUG').any { it.contains('Discarding channel 0') }
    }

    private static void stubDiscoveryResponses(HubitatScriptHarness harness) {
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/magicBox.cgi?action=getSystemInfo'] =
            HubitatScriptHarness.textResponse(200, 'serialNumber=SERIAL1\ndeviceType=NVR\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/magicBox.cgi?action=getDeviceType'] =
            HubitatScriptHarness.textResponse(200, 'type=Dahua NVR\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle'] =
            HubitatScriptHarness.textResponse(200, 'table.ChannelTitle[1].Name=Front Door\ntable.ChannelTitle[2].Name=Driveway\n')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=VideoWidget'] =
            HubitatScriptHarness.textResponse(200, '')
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/configManager.cgi?action=getConfig&name=RemoteDevice'] =
            HubitatScriptHarness.textResponse(200, '')
    }
}
