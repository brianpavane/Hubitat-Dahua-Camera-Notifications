import spock.lang.Specification

class DahuaNVRDriverSpec extends Specification {

    static class StubHttpResponseException extends RuntimeException {
        Integer statusCode
        Map response = [:]
    }

    def "event stream performs digest challenge then authenticates and emits motion events"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5'] =
            new StubHttpResponseException(
                statusCode: 401,
                response: [headers: ['WWW-Authenticate': [value: 'Digest realm="Login to Dahua", nonce="probe123", qop="auth"']]]
            )
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.openEventStream()

        then:
        harness.rawSocket.connections.size() == 1
        harness.device.currentValue('eventStreamStatus') == 'reconnecting'
        harness.device.currentValue('connectionPhase') == 'opening_socket'
        harness.device.currentValue('lastRequestMode') == 'auto'
        harness.device.currentValue('lastProbeStatus') == 'digest_challenge_received'
        harness.device.currentValue('lastProbeHttpStatus') == '401'
        harness.device.currentValue('connectionTrace').contains('Prepared digest auth from HTTP probe challenge')

        when:
        driver.socketStatus('status: open')

        then:
        harness.rawSocket.sentMessages[0].contains('GET /cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5 HTTP/1.1')
        harness.device.currentValue('connectionPhase') == 'sending_preauthenticated_request'
        harness.device.currentValue('lastRequestPreview').contains('GET /cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5 HTTP/1.1')

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
        harness.device.currentValue('lastHeaderSample').contains('HTTP/1.1 200 OK')
        harness.device.currentValue('rawChunkCount') == 3
        harness.device.currentValue('streamBufferBytes') == 0
        harness.logsAt('INFO').any { it.contains('Motion event received (ch 1): VideoMotion start') }
        !harness.state.connection.containsKey('password')
        harness.settings.nvrPassword == 'secret'
        harness.device.currentValue('lastHttpStatusLine') == 'HTTP/1.1 200 OK'
        harness.logsAt('DEBUG').any { it.contains('Raw socket connect invoked using') }
        harness.logsAt('DEBUG').any { it.contains('Prepared digest auth from HTTP probe challenge') }
    }

    def "raw socket HTTP test captures a successful non-stream response and stops cleanly"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/magicBox.cgi?action=getSystemInfo'] =
            new StubHttpResponseException(
                statusCode: 401,
                response: [headers: ['WWW-Authenticate': [value: 'Digest realm="Login to Dahua", nonce="probe123", qop="auth"']]]
            )
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.runRawSocketHttpTest('/cgi-bin/magicBox.cgi?action=getSystemInfo')
        driver.socketStatus('status: open')
        driver.parse('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n')

        then:
        harness.rawSocket.sentMessages.size() == 1
        harness.rawSocket.sentMessages[0].contains('GET /cgi-bin/magicBox.cgi?action=getSystemInfo HTTP/1.1')
        harness.device.currentValue('lastRawSocketTestPath') == '/cgi-bin/magicBox.cgi?action=getSystemInfo'
        harness.device.currentValue('lastRawSocketTestStatus') == 'success_200'
        harness.device.currentValue('lastRawSocketHeaderSample').contains('HTTP/1.1 200 OK')
        harness.device.currentValue('lastAttemptSummary').contains('Raw socket test succeeded')
        harness.device.currentValue('eventStreamStatus') == 'stopped'
    }

    def "request mode changes event-stream path and headers"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true,"requestMode":"http10NoHeartbeat"}')

        when:
        driver.openEventStream()
        driver.forceInitialRequestIfPending()

        then:
        harness.device.currentValue('lastRequestMode') == 'http10NoHeartbeat'
        harness.device.currentValue('lastRequestPath') == '/cgi-bin/eventManager.cgi?action=attach&codes=[All]'
        harness.rawSocket.sentMessages[0].contains('GET /cgi-bin/eventManager.cgi?action=attach&codes=[All] HTTP/1.0')
        harness.rawSocket.sentMessages[0].contains('Connection: close')
    }

    def "refresh does not force connected state and removes legacy password from connection state"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        harness.state.connection = [host: '192.168.1.10', port: 80, username: 'admin', password: 'secret', eventCodes: ['All']]
        harness.device.currentValues.networkStatus = 'connected'
        harness.device.currentValues.eventStreamStatus = 'reconnecting'

        when:
        driver.refresh()

        then:
        !harness.state.connection.containsKey('password')
        harness.device.currentValue('eventStreamStatus') == 'reconnecting'
        harness.rawSocket.connections.size() == 1
    }

    def "parent error and status diagnostics redact plaintext passwords and auth headers"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.socketStatus('send error: Authorization: Digest username="admin", realm="Login", response="xyz", password="secret"')

        then:
        harness.device.currentValue('lastError').contains('[REDACTED]')
        !harness.device.currentValue('lastError').contains('secret')
        !harness.device.currentValue('lastSocketStatus').contains('secret')
        harness.logsAt('WARN').every { !it.contains('secret') }
        harness.logsAt('DEBUG').every { !it.contains('secret') }
        harness.device.currentValue('connectionTrace').contains('[REDACTED]')
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

    def "fallback initial request fires when socket open status is not observed"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.openEventStream()
        driver.forceInitialRequestIfPending()

        then:
        harness.rawSocket.sentMessages.size() == 1
        harness.rawSocket.sentMessages[0].contains('GET /cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5 HTTP/1.1')
        harness.device.currentValue('connectionPhase') == 'sending_initial_request'
        harness.device.currentValue('connectionTrace').contains('forcing initial request')
        harness.scheduled.any { it.method == 'handshakeWatchdog' && it.delay == 25 }
    }

    def "handshake timeout retries with alternate Dahua event path before reconnect backoff"() {
        given:
        def harness = new HubitatScriptHarness()
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5'] =
            new StubHttpResponseException(
                statusCode: 401,
                response: [headers: ['WWW-Authenticate': [value: 'Digest realm="Login to Dahua", nonce="probe123", qop="auth"']]]
            )
        harness.httpGetResponses['http://192.168.1.10:80/cgi-bin/eventManager.cgi?action=attach&codes=[All]'] =
            new StubHttpResponseException(
                statusCode: 401,
                response: [headers: ['WWW-Authenticate': [value: 'Digest realm="Login to Dahua", nonce="probe456", qop="auth"']]]
            )

        def driver = harness.loadScript('DahuaNVRDriver.groovy')
        driver.applyConnectionSettings('{"host":"192.168.1.10","port":80,"username":"admin","password":"secret","serialNumber":"ABC123","model":"Dahua NVR","cameraCount":4,"eventCodes":["All"],"debugEnabled":true}')

        when:
        driver.openEventStream()
        driver.handshakeWatchdog()

        then:
        harness.device.currentValue('lastRequestPath') == '/cgi-bin/eventManager.cgi?action=attach&codes=[All]'
        harness.device.currentValue('connectionPhase') == 'opening_socket'
        harness.rawSocket.connections.size() == 2
        !harness.scheduled.any { it.method == 'attemptReconnect' }
        harness.logsAt('DEBUG').any { it.contains('Retrying alternate event path') }
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
