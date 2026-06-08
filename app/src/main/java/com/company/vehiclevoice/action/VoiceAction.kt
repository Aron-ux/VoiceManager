package com.company.vehiclevoice.action

import com.company.vehiclevoice.data.VehicleStateSnapshot
import com.company.vehiclevoice.nlu.IntentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VoiceAction(
    val actionId: String,
    val intent: String,
    val asrText: String,
    val slots: Map<String, String>,
    val vehicleState: VehicleStateSnapshot,
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"event_type\":\"voice.action\",")
            append("\"action_id\":\"").append(escape(actionId)).append("\",")
            append("\"intent\":\"").append(escape(intent)).append("\",")
            append("\"asr_text\":\"").append(escape(asrText)).append("\",")
            append("\"slots\":{")
            append(slots.entries.joinToString(separator = ",") { (key, value) ->
                "\"${escape(key)}\":\"${escape(value)}\""
            })
            append("},")
            append("\"vehicle_state\":{").append(vehicleState.toJsonFields()).append("}")
            append("}")
        }
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    companion object {
        fun from(asrText: String, intentResult: IntentResult, state: VehicleStateSnapshot): VoiceAction {
            return VoiceAction(
                actionId = actionIdFormatter.format(Date()),
                intent = intentResult.intentId,
                asrText = asrText,
                slots = intentResult.slots,
                vehicleState = state,
            )
        }

        private val actionIdFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
