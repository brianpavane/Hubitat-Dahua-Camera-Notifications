import spock.lang.Specification

class DahuaCameraDriverSpec extends Specification {

    def "motion becomes active and then inactive for a configured event type"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaCameraDriver.groovy')
        driver.configureCamera('{"channel":"1","cameraName":"Driveway","enabled":true,"motionDrivingEventTypes":["VideoMotion"],"motionInactiveSeconds":30}')

        when:
        driver.applyDahuaEvent('{"code":"VideoMotion","action":"start","timestamp":"2026-04-05T08:00:00-0400","motionDrivingEventTypes":["VideoMotion"]}')

        then:
        harness.device.currentValue('motion') == 'active'
        harness.device.currentValue('videoMotion') == 'active'
        harness.logsAt('INFO').any { it.contains('VideoMotion start -> motion active') }

        when:
        driver.applyDahuaEvent('{"code":"VideoMotion","action":"stop","timestamp":"2026-04-05T08:00:05-0400","motionDrivingEventTypes":["VideoMotion"]}')

        then:
        harness.device.currentValue('motion') == 'inactive'
        harness.device.currentValue('videoMotion') == 'inactive'
    }

    def "overlapping motion sources do not clear motion until all selected events stop"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaCameraDriver.groovy')
        driver.configureCamera('{"channel":"1","cameraName":"Front Yard","enabled":true,"motionDrivingEventTypes":["SmartMotionHuman","SmartMotionVehicle"],"motionInactiveSeconds":30}')

        when:
        driver.applyDahuaEvent('{"code":"SmartMotionHuman","action":"start","motionDrivingEventTypes":["SmartMotionHuman","SmartMotionVehicle"]}')
        driver.applyDahuaEvent('{"code":"SmartMotionVehicle","action":"start","motionDrivingEventTypes":["SmartMotionHuman","SmartMotionVehicle"]}')
        driver.applyDahuaEvent('{"code":"SmartMotionHuman","action":"stop","motionDrivingEventTypes":["SmartMotionHuman","SmartMotionVehicle"]}')

        then:
        harness.device.currentValue('motion') == 'active'

        when:
        driver.applyDahuaEvent('{"code":"SmartMotionVehicle","action":"stop","motionDrivingEventTypes":["SmartMotionHuman","SmartMotionVehicle"]}')

        then:
        harness.device.currentValue('motion') == 'inactive'
    }

    def "disabled cameras ignore incoming events"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaCameraDriver.groovy')
        driver.configureCamera('{"channel":"2","cameraName":"Garage","enabled":false,"motionDrivingEventTypes":["VideoMotion"],"motionInactiveSeconds":30}')

        when:
        driver.applyDahuaEvent('{"code":"VideoMotion","action":"start","motionDrivingEventTypes":["VideoMotion"]}')

        then:
        harness.device.currentValue('managedStatus') == 'disabled'
        harness.device.currentValue('motion') == 'inactive'
        harness.device.currentValue('lastEventCode') == null
    }

    def "motion timeout clears stale active state when no stop event arrives"() {
        given:
        def harness = new HubitatScriptHarness()
        def driver = harness.loadScript('DahuaCameraDriver.groovy')
        driver.configureCamera('{"channel":"3","cameraName":"Back Door","enabled":true,"motionDrivingEventTypes":["CrossLineDetection"],"motionInactiveSeconds":5}')

        when:
        driver.applyDahuaEvent('{"code":"CrossLineDetection","action":"start","motionDrivingEventTypes":["CrossLineDetection"],"motionInactiveSeconds":5}')
        harness.nowValue += 6000L
        driver.motionTimeoutCheck()

        then:
        harness.device.currentValue('motion') == 'inactive'
        harness.scheduled.any { it.method == 'motionTimeoutCheck' && it.delay == 5 }
    }
}
