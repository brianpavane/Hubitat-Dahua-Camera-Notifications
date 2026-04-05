class DeviceWrapper {
    String deviceNetworkId
    String name
    String label
    Map<String, Object> currentValues = [:]
    Map<String, String> dataValues = [:]
    Map<String, Object> settings = [:]
    List<Map> sentEvents = []
    List<Map> commandCalls = []
    Closure onConfigureCamera
    Closure onApplyConnectionSettings
    Closure onApplyDahuaEvent
    Closure onSetManagedDisabled
    Closure onOpenEventStream

    Object currentValue(String attrName) {
        currentValues[attrName]
    }

    void sendEvent(Map evt) {
        sentEvents << evt
        currentValues[evt.name as String] = evt.value
    }

    void updateSetting(String settingName, Map settingValue) {
        settings[settingName] = settingValue.value
    }

    void updateDataValue(String name, String value) {
        dataValues[name] = value
    }

    String getDataValue(String name) {
        dataValues[name]
    }

    void setLabel(String newLabel) {
        label = newLabel
    }

    String getDisplayName() {
        label ?: name ?: deviceNetworkId ?: "Mock Device"
    }

    def configureCamera(String json) {
        commandCalls << [name: 'configureCamera', json: json]
        onConfigureCamera?.call(json)
    }

    def applyConnectionSettings(String json) {
        commandCalls << [name: 'applyConnectionSettings', json: json]
        onApplyConnectionSettings?.call(json)
    }

    def applyDahuaEvent(String json) {
        commandCalls << [name: 'applyDahuaEvent', json: json]
        onApplyDahuaEvent?.call(json)
    }

    def setManagedDisabled(Boolean disabled, String reason = "") {
        commandCalls << [name: 'setManagedDisabled', disabled: disabled, reason: reason]
        onSetManagedDisabled?.call(disabled, reason)
    }

    def openEventStream() {
        commandCalls << [name: 'openEventStream']
        onOpenEventStream?.call()
    }
}
