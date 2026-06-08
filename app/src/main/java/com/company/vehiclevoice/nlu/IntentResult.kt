package com.company.vehiclevoice.nlu

data class IntentResult(
    val intentId: String,
    val confidence: Double,
    val normalizedText: String,
    val slots: Map<String, String> = emptyMap(),
    val requiredStateKeys: List<VehicleStateKey> = emptyList(),
) {
    val matched: Boolean
        get() = intentId != INTENT_UNSUPPORTED

    companion object {
        const val INTENT_UNSUPPORTED = "unsupported"

        fun unsupported(normalizedText: String): IntentResult {
            return IntentResult(
                intentId = INTENT_UNSUPPORTED,
                confidence = 0.0,
                normalizedText = normalizedText,
            )
        }
    }
}

enum class VehicleStateKey {
    SPEED_KPH,
    LOCATION_LAT,
    LOCATION_LON,
    HEADING_DEG,
    BATTERY_SOC,
    GEAR,
    DOOR_LEFT_FRONT,
    DOOR_RIGHT_FRONT,
}
