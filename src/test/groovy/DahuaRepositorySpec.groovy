import spock.lang.Specification

class DahuaRepositorySpec extends Specification {

    def "hubitat source files include importUrl metadata for direct import"() {
        expect:
        new File(path).text.contains(expected)

        where:
        path                     | expected
        'DahuaNVRSyncApp.groovy' | 'importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy"'
        'DahuaNVRDriver.groovy'  | 'importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy"'
        'DahuaCameraDriver.groovy' | 'importUrl: "https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy"'
    }

    def "app is configured as a single Hubitat app instance"() {
        expect:
        new File('DahuaNVRSyncApp.groovy').text.contains('singleInstance: true')
    }

    def "readme includes bulk-file-manager-style import instructions for all Hubitat files"() {
        given:
        String readme = new File('README.md').text

        expect:
        readme.contains('### Option A — Import directly in Hubitat')
        readme.contains('https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRDriver.groovy')
        readme.contains('https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaCameraDriver.groovy')
        readme.contains('https://raw.githubusercontent.com/brianpavane/Hubitat-Dahua-Camera-Notifications/main/DahuaNVRSyncApp.groovy')
    }

    def "repository does not contain obvious hard-coded credentials"() {
        given:
        List<File> files = [
            new File('DahuaNVRSyncApp.groovy'),
            new File('DahuaNVRDriver.groovy'),
            new File('DahuaCameraDriver.groovy'),
            new File('README.md')
        ]

        expect:
        files.every { File file ->
            String text = file.text
            !text.contains('gho_') && !text.contains('-----BEGIN') && !text.contains('password=\"secret\"')
        }
    }
}
