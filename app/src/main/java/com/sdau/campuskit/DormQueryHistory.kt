package com.sdau.campuskit

import org.json.JSONObject

internal data class DormQuerySnapshot(val queriedAt: Long, val remainingKwh: Double)

/** Only successful explicit balance queries, isolated by campus/meter/line. No order-history copy. */
internal class DormQueryHistory(private val read: () -> String, private val write: (String) -> Unit) {
    @Synchronized
    fun record(meter: DormMeter, reading: DormElectricityReading, now: Long): DormQuerySnapshot? {
        require(reading.remainingKwh.isFinite() && now > 0)
        val data = runCatching { JSONObject(read()) }.getOrElse { JSONObject() }
        val key = "${meter.campus}\u001f${meter.billingNumber}\u001f${meter.line}"
        val previous = data.optJSONObject(key)?.let {
            val time = it.optLong("at")
            val kwh = it.optDouble("kwh")
            if (time > 0 && kwh.isFinite()) DormQuerySnapshot(time, kwh) else null
        }
        data.put(key, JSONObject().put("at", now).put("kwh", reading.remainingKwh))
        write(data.toString())
        return previous
    }
}
