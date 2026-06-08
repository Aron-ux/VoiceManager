package com.company.vehiclevoice.data

import com.company.vehiclevoice.nlu.VehicleStateKey

class MockVehicleStateRepository : VehicleStateRepository {
    private val values = mapOf(
        VehicleStateKey.SPEED_KPH to "42.5",
        VehicleStateKey.LOCATION_LAT to "31.230416",
        VehicleStateKey.LOCATION_LON to "121.473701",
        VehicleStateKey.HEADING_DEG to "90",
        VehicleStateKey.BATTERY_SOC to "82",
        VehicleStateKey.GEAR to "D",
        VehicleStateKey.DOOR_LEFT_FRONT to "closed",
        VehicleStateKey.DOOR_RIGHT_FRONT to "closed",
    )

    override fun getState(keys: List<VehicleStateKey>): VehicleStateSnapshot {
        return VehicleStateSnapshot(
            values = keys.distinct().mapNotNull { key ->
                values[key]?.let { value -> key to value }
            }.toMap(),
        )
    }
}
