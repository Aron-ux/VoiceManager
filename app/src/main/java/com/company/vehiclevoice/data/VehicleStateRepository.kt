package com.company.vehiclevoice.data

import com.company.vehiclevoice.nlu.VehicleStateKey

interface VehicleStateRepository {
    fun getState(keys: List<VehicleStateKey>): VehicleStateSnapshot
}

data class VehicleStateSnapshot(
    val values: Map<VehicleStateKey, String>,
) {
    fun toDisplayText(): String {
        if (values.isEmpty()) {
            return "-"
        }

        return values.entries.joinToString(separator = " | ") { (key, value) ->
            "${key.name}=$value"
        }
    }

    fun toJsonFields(): String {
        return values.entries.joinToString(separator = ",") { (key, value) ->
            "\"${key.name.lowercase()}\":\"$value\""
        }
    }
}
