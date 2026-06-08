package com.company.vehiclevoice.response

import com.company.vehiclevoice.data.VehicleStateSnapshot
import com.company.vehiclevoice.nlu.IntentResult
import com.company.vehiclevoice.nlu.VehicleStateKey

class ResponseTemplateEngine {
    fun render(intentResult: IntentResult, state: VehicleStateSnapshot): String {
        return when (intentResult.intentId) {
            "vehicle.query_speed" -> "当前车速 ${value(state, VehicleStateKey.SPEED_KPH)} 公里每小时。"
            "vehicle.query_location" -> {
                "当前位置纬度 ${value(state, VehicleStateKey.LOCATION_LAT)}，经度 ${value(state, VehicleStateKey.LOCATION_LON)}。"
            }
            "vehicle.query_heading" -> "当前航向 ${value(state, VehicleStateKey.HEADING_DEG)} 度。"
            "vehicle.query_battery" -> "当前电量 ${value(state, VehicleStateKey.BATTERY_SOC)}%。"
            "vehicle.query_gear" -> "当前档位 ${value(state, VehicleStateKey.GEAR)}。"
            "vehicle.query_door" -> {
                val leftFront = doorValue(state, VehicleStateKey.DOOR_LEFT_FRONT)
                val rightFront = doorValue(state, VehicleStateKey.DOOR_RIGHT_FRONT)
                "左前门$leftFront，右前门$rightFront。"
            }
            "unity.show_speed_panel" -> {
                "已打开速度页面，当前车速 ${value(state, VehicleStateKey.SPEED_KPH)} 公里每小时。"
            }
            "unity.show_map_panel" -> {
                "已打开地图页面，当前位置纬度 ${value(state, VehicleStateKey.LOCATION_LAT)}，经度 ${value(state, VehicleStateKey.LOCATION_LON)}。"
            }
            IntentResult.INTENT_UNSUPPORTED -> "我还不支持这个指令。"
            else -> "我还不支持这个指令。"
        }
    }

    private fun value(state: VehicleStateSnapshot, key: VehicleStateKey): String {
        return state.values[key]?.takeIf { it.isNotBlank() } ?: "未知"
    }

    private fun doorValue(state: VehicleStateSnapshot, key: VehicleStateKey): String {
        return when (state.values[key]) {
            "closed" -> "已关闭"
            "open" -> "已打开"
            null -> "未知"
            else -> state.values[key].orEmpty()
        }
    }
}
