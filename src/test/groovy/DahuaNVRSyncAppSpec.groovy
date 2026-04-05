import groovy.json.JsonSlurper
import spock.lang.Specification

class DahuaNVRSyncAppSpec extends Specification {

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
}
