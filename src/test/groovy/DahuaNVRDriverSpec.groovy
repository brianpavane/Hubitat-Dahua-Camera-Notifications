import spock.lang.Specification

class DahuaNVRDriverSpec extends Specification {

    def "event stream performs digest challenge then authenticates and emits motion events"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.openEventStream()
        driver.socketStatus('status: open')

        then:
        harness.rawSocket.connections.size() == 1
        harness.rawSocket.sentMessages[0].contains('GET /cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5 HTTP/1.1')
        harness.device.currentValue('eventStreamStatus') == 'reconnecting'

        when:
        driver.parse('HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm="Login to Dahua", nonce="abc123", qop="auth"\r\n\r\n')

        then:
        harness.rawSocket.sentMessages.size() == 2
        harness.rawSocket.sentMessages[1].contains('Authorization: Digest username="admin"')

        when:
        driver.parse('HTTP/1.1 200 OK\r\nContent-Type: multipart/mixed\r\n\r\n')
        driver.parse('Code=VideoMotion\r\naction=Start\r\nindex=1\r\n\r\n')

        then:
        harness.device.currentValue('networkStatus') == 'connected'
        harness.device.currentValue('eventStreamStatus') == 'connected'
        harness.device.currentValue('lastEventReceived') != null
        harness.device.currentValue('rawEvent').contains('"code":"VideoMotion"')
        harness.logsAt('INFO').any { it.contains('Motion event received (ch 1): VideoMotion start') }
        !harness.state.connection.containsKey('password')
        harness.settings.nvrPassword == 'secret'
    }

    def "socket closure schedules reconnect with bounded backoff"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.socketStatus('status: closed')

        then:
        harness.device.currentValue('eventStreamStatus') == 'reconnecting'
        harness.device.currentValue('reconnectCount') == 1
        harness.scheduled.any { it.method == 'attemptReconnect' && it.delay == 5 }
    }

    def "oversized event buffer is cleared and reconnect is scheduled"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.parse('x' * 131073)

        then:
        harness.device.currentValue('lastError').contains('Event stream buffer exceeded')
        harness.state.streamBuffer == ''
        harness.scheduled.any { it.method == 'attemptReconnect' && it.delay == 5 }
    }
}
