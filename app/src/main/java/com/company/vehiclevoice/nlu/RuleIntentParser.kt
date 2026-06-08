package com.company.vehiclevoice.nlu

class RuleIntentParser {
    fun parse(text: String): IntentResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return IntentResult.unsupported(normalized)
        }

        exactRules[normalized]?.let { return it(normalized) }

        return when {
            containsAny(normalized, "速度页面", "速度页", "车速页面", "车速页") -> showSpeedPanel(normalized, confidence = 0.8)
            containsAny(normalized, "地图", "地图页面", "地图页") -> showMapPanel(normalized, confidence = 0.8)
            containsAny(normalized, "速度", "车速", "时速") -> querySpeed(normalized, confidence = 0.8)
            containsAny(normalized, "位置", "坐标", "在哪", "哪里") -> queryLocation(normalized, confidence = 0.8)
            containsAny(normalized, "航向", "朝向", "方向") -> queryHeading(normalized, confidence = 0.8)
            containsAny(normalized, "电量", "电池", "soc") -> queryBattery(normalized, confidence = 0.8)
            containsAny(normalized, "档位", "挡位", "什么档", "什么挡") -> queryGear(normalized, confidence = 0.8)
            containsAny(normalized, "车门", "门") -> queryDoor(normalized, confidence = 0.8)
            else -> IntentResult.unsupported(normalized)
        }
    }

    private fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(" ", "")
            .replace("，", "")
            .replace(",", "")
            .replace("。", "")
            .replace(".", "")
            .replace("？", "")
            .replace("?", "")
            .replace("！", "")
            .replace("!", "")
            .replace("小智小智", "")
            .replace("你好小智", "")
            .replace("速读", "速度")
            .replace("车宿", "车速")
            .trim()
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun querySpeed(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_speed",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.SPEED_KPH),
        )
    }

    private fun queryLocation(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_location",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.LOCATION_LAT, VehicleStateKey.LOCATION_LON),
        )
    }

    private fun queryHeading(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_heading",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.HEADING_DEG),
        )
    }

    private fun queryBattery(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_battery",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.BATTERY_SOC),
        )
    }

    private fun queryGear(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_gear",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.GEAR),
        )
    }

    private fun queryDoor(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "vehicle.query_door",
            confidence = confidence,
            normalizedText = text,
            requiredStateKeys = listOf(VehicleStateKey.DOOR_LEFT_FRONT, VehicleStateKey.DOOR_RIGHT_FRONT),
        )
    }

    private fun showSpeedPanel(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "unity.show_speed_panel",
            confidence = confidence,
            normalizedText = text,
            slots = mapOf("panel" to "speed"),
            requiredStateKeys = listOf(VehicleStateKey.SPEED_KPH),
        )
    }

    private fun showMapPanel(text: String, confidence: Double = 1.0): IntentResult {
        return IntentResult(
            intentId = "unity.show_map_panel",
            confidence = confidence,
            normalizedText = text,
            slots = mapOf("panel" to "map"),
            requiredStateKeys = listOf(VehicleStateKey.LOCATION_LAT, VehicleStateKey.LOCATION_LON),
        )
    }

    private val exactRules = mapOf<String, (String) -> IntentResult>(
        "当前速度多少" to { querySpeed(it) },
        "现在车速多少" to { querySpeed(it) },
        "当前位置在哪" to { queryLocation(it) },
        "当前坐标是多少" to { queryLocation(it) },
        "电量还有多少" to { queryBattery(it) },
        "现在是什么档位" to { queryGear(it) },
        "打开速度页面" to { showSpeedPanel(it) },
        "显示地图" to { showMapPanel(it) },
    )
}
