package com.sdau.campuskit

import org.json.JSONObject
import java.math.BigDecimal

/** Only local visibility and meter samples; the school remains the source of order records. */
internal class DormRechargeAnnotations(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private fun data() = runCatching { JSONObject(read()) }.getOrElse { JSONObject() }
    private fun key(meter: DormMeter) = "${meter.campus}\u001f${meter.billingNumber}\u001f${meter.line}"

    @Synchronized
    fun rememberBefore(orderId: String, meter: DormMeter, reading: DormElectricityReading, capturedAt: Long) {
        require(orderId.isNotBlank() && reading.paidKwh.isFinite() && reading.remainingKwh.isFinite())
        val data = data()
        if (data.optJSONObject(orderId)?.has("before") == true) return
        // Two payments must not both claim the same aggregate balance increase.
        data.keys().asSequence().filterIsInstance<String>().toList().forEach { id ->
            data.optJSONObject(id)?.takeIf { it.optString("meter") == key(meter) && !it.has("after") }
                ?.put("eligible", false)
        }
        val note = data.optJSONObject(orderId) ?: JSONObject()
        note.put("meter", key(meter)).put("before", reading.paidKwh).put("capturedAt", capturedAt)
            .put("beforeTotal", reading.remainingKwh).put("beforeUpdatedAt", reading.updatedAt).put("eligible", true)
        data.put(orderId, note)
        write(data.toString())
    }

    @Synchronized
    fun canCapture(entry: DormRechargeHistoryEntry, meter: DormMeter): Boolean {
        if (!entry.credited || entry.line != meter.line) return false
        val note = data().optJSONObject(entry.orderId) ?: return false
        return note.optString("meter") == key(meter) && note.optBoolean("eligible") && !note.has("after") &&
            note.optDouble("before").isFinite() && now() - note.optLong("capturedAt") in 0..30 * 60_000L
    }

    @Synchronized
    fun recordAfter(entry: DormRechargeHistoryEntry, meter: DormMeter, reading: DormElectricityReading): Boolean {
        if (!canCapture(entry, meter) || !reading.paidKwh.isFinite()) return false
        val data = data()
        val note = data.getJSONObject(entry.orderId)
        val updatedBefore = note.optString("beforeUpdatedAt")
        // School status may update before its meter sample. Keep polling an unchanged old sample.
        if (reading.paidKwh == note.getDouble("before") ||
            (updatedBefore.isNotBlank() && reading.updatedAt.isNotBlank() && reading.updatedAt <= updatedBefore)) return false
        note.put("after", reading.paidKwh)
        write(data.toString())
        return true
    }

    @Synchronized
    fun hide(orderId: String) {
        require(orderId.isNotBlank())
        val data = data()
        data.put(orderId, (data.optJSONObject(orderId) ?: JSONObject()).put("hidden", true))
        write(data.toString())
    }

    /** Only explicit, fully successful synchronization restores deleted paid records. */
    @Synchronized
    fun restorePaid(entries: List<DormRechargeHistoryEntry>): List<DormRechargeHistoryEntry> {
        val data = data()
        var changed = false
        entries.filter { it.paid }.forEach { entry ->
            data.optJSONObject(entry.orderId)?.takeIf { it.optBoolean("hidden") }?.let {
                it.remove("hidden")
                changed = true
            }
        }
        if (changed) write(data.toString())
        return visible(entries)
    }

    @Synchronized
    fun visible(entries: List<DormRechargeHistoryEntry>): List<DormRechargeHistoryEntry> {
        val data = data()
        return entries.filter { it.paid && data.optJSONObject(it.orderId)?.optBoolean("hidden") != true }.map { entry ->
            val note = data.optJSONObject(entry.orderId)
            val before = note?.optDouble("before")
            val after = note?.optDouble("after")
            val delta = if (entry.credited && before != null && after != null && before.isFinite() && after.isFinite())
                BigDecimal.valueOf(after).subtract(BigDecimal.valueOf(before)).toDouble() else null
            entry.copy(rechargedKwh = delta, beforePaidKwh = before?.takeIf { it.isFinite() },
                beforeTotalKwh = note?.optDouble("beforeTotal")?.takeIf { it.isFinite() })
        }
    }
}
