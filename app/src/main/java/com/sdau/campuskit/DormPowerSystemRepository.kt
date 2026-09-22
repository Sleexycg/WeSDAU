package com.sdau.campuskit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

internal data class DormElectricityOption(val label: String, val code: String)
internal data class DormElectricityReading(
    val paidKwh: Double, val freeKwh: Double, val arrearsKwh: Double,
    val supplyStatus: String, val location: String, val updatedAt: String = ""
) {
    val remainingKwh: Double get() = paidKwh + freeKwh - arrearsKwh
}

/** One consumption day. [day] is the day the power was used (`sjdylday`), never the settlement day. */
internal data class DormDailyPowerEntry(val day: LocalDate, val kwh: Double)

internal data class DormDailyPower(
    val days: List<DormDailyPowerEntry>,
    val requestedDays: Int
) {
    /** Newest first. A day the school has no record for is absent, never reported as zero. */
    val entries: List<DormDailyPowerEntry> get() = days.sortedByDescending { it.day }
    val totalKwh: Double get() = days.sumOf { it.kwh }
    /** Averaged over the days the school actually recorded, never over the requested window. */
    val averageKwh: Double get() = if (days.isEmpty()) 0.0 else totalKwh / days.size
    val hasData: Boolean get() = days.isNotEmpty()
}

internal data class DormRechargeQr(val imageBytes: ByteArray, val amount: Double, val orderId: String)
internal data class DormRechargePayment(
    val paymentUrl: String, val amount: Double, val billingNumber: String, val orderId: String = ""
)

/** A room can have several lines, each with a different billing number. */
internal data class DormMeter(
    val campus: String, val building: String, val floor: String,
    val room: String, val billingNumber: String, val line: String
) {
    val roomKey: String get() = "$floor\u001f$room"
    val location: String get() = "$campus-$building-$room"
}

internal data class DormMeterDirectory(val meters: List<DormMeter>, val lines: List<DormElectricityOption>) {
    fun campuses() = meters.map { it.campus }.distinct().sorted().map { DormElectricityOption(it, it) }
    fun buildings(campus: String) = meters.filter { it.campus == campus }.map { it.building }
        .distinct().sortedWith(naturalDormOrder).map { DormElectricityOption(it, it) }

    fun rooms(campus: String, building: String): List<DormElectricityOption> {
        val rooms = meters.filter { it.campus == campus && it.building == building }.distinctBy { it.roomKey }
        val duplicateNames = rooms.groupingBy { it.room }.eachCount().filterValues { it > 1 }.keys
        return rooms.sortedWith(compareBy(naturalDormOrder) { it.room }).map {
            DormElectricityOption(if (it.room in duplicateNames) "${it.room}（${it.floor}）" else it.room, it.roomKey)
        }
    }

    fun equipment(campus: String, building: String, room: String): List<DormElectricityOption> {
        val available = meters.filter { it.campus == campus && it.building == building && it.roomKey == room }
            .map { it.line }.distinct()
        return lines.filter { it.code in available } + available.filter { code -> lines.none { it.code == code } }
            .map { DormElectricityOption("线路$it", it) }
    }

    fun resolve(campus: String, building: String, room: String, line: String): DormMeter {
        val matches = meters.filter {
            it.campus == campus && it.building == building && it.roomKey == room && it.line == line
        }.distinctBy { it.billingNumber }
        check(matches.size == 1) { "房间或线路信息已变更，请重新选择宿舍" }
        return matches.single()
    }
}

private val naturalDormOrder = Comparator<String> { left, right ->
    val a = Regex("\\d+|\\D+").findAll(left).map { it.value }.toList()
    val b = Regex("\\d+|\\D+").findAll(right).map { it.value }.toList()
    for (i in 0 until minOf(a.size, b.size)) {
        val an = a[i].toLongOrNull()
        val bn = b[i].toLongOrNull()
        val comparison = if (an != null && bn != null) an.compareTo(bn) else a[i].compareTo(b[i])
        if (comparison != 0) return@Comparator comparison
    }
    left.compareTo(right)
}

internal fun interface DormApiTransport {
    fun request(path: String, body: JSONObject?): JSONObject
}

/** The school's daily-power page only offers the most recent seven consumption days. */
internal const val DORM_DAILY_POWER_DAYS = 7

/** Uses the same student endpoints as the school's new TAND page; no administrator identity. */
internal class DormElectricityRepository(
    private val transport: DormApiTransport = DormHttpTransport(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Shanghai")) }
) {
    @Volatile private var directory: DormMeterDirectory? = null

    @Synchronized
    fun loadCampuses(forceRefresh: Boolean = false): List<DormElectricityOption> {
        if (directory == null || forceRefresh) {
            val json = call("/electricityMeter/searchnp", searchBody(listOf(condition("flbs", "00")), 0, 1000))
            val meters = DormElectricityParser.meters(json.getJSONArray("data"))
            check(meters.isNotEmpty()) { "未获取到学生宿舍电表信息" }
            // The official page also falls back when its optional dictionary is unavailable.
            val lines = runCatching {
                DormElectricityParser.lines(transport.request("/system/dict/data/type/xl_type", null))
            }.getOrDefault(emptyList()).ifEmpty {
                listOf(DormElectricityOption("照明", "1"), DormElectricityOption("空调", "2"))
            }
            directory = DormMeterDirectory(meters, lines)
        }
        return requireDirectory().campuses()
    }

    fun loadBuildings(campusCode: String) = requireDirectory().buildings(campusCode)
    fun loadRooms(campusCode: String, buildingCode: String) = requireDirectory().rooms(campusCode, buildingCode)
    fun loadEquipmentTypes(campus: String, building: String, room: String) = requireDirectory().equipment(campus, building, room)
    fun resolve(campus: DormElectricityOption, building: DormElectricityOption, room: DormElectricityOption, equipment: DormElectricityOption) =
        requireDirectory().resolve(campus.code, building.code, room.code, equipment.code)

    fun query(campus: DormElectricityOption, building: DormElectricityOption, room: DormElectricityOption, equipment: DormElectricityOption) =
        query(resolve(campus, building, room, equipment))

    fun query(meter: DormMeter): DormElectricityReading {
        val json = call("/webZndbbDayDate/search", JSONObject().put("condition", JSONArray(listOf(
            condition("fangnumer", meter.billingNumber), condition("xl", meter.line),
            condition("dbday", today().toString()), condition("xiaoqu", meter.campus)
        ))))
        return DormElectricityParser.reading(json, meter)
    }

    /**
     * The school caps the daily window at seven days and exposes no server-side aggregate, so the
     * newest [days] consumption days are read as one inclusive range and summarised locally.
     * A single-day range returns no rows, so [days] is always at least one full span.
     */
    fun queryDailyPower(meter: DormMeter, days: Int = DORM_DAILY_POWER_DAYS): DormDailyPower {
        require(days in 1..DORM_DAILY_POWER_DAYS) { "每日用电仅支持最近 $DORM_DAILY_POWER_DAYS 天" }
        val end = today()
        val start = end.minusDays((days - 1).toLong())
        val conditions = listOf(
            condition("fangnumer", meter.billingNumber), condition("sjdylday", start.toString(), "gte"),
            condition("sjdylday", end.toString(), "lte"), condition("xiaoqu", meter.campus)
        )
        val entries = linkedMapOf<LocalDate, DormDailyPowerEntry>()
        var page = 1
        while (true) {
            val json = call("/webZndbbDayDate/search", searchBody(conditions, page, days, "sjdylday desc"))
            val parsed = DormElectricityParser.dailyPower(json, meter)
            parsed.forEach { entries[it.day] = it }
            val data = json.optJSONObject("data") ?: error("供电系统未返回每日用电数据")
            val hasMore = if (data.has("totalPage")) page < data.optInt("totalPage") else false
            if (!hasMore || parsed.isEmpty()) break
            check(page < 10_000) { "供电系统分页异常，请稍后重试" }
            page++
        }
        return DormDailyPower(entries.values.filter { !it.day.isBefore(start) && !it.day.isAfter(end) }
            .sortedBy { it.day }, days)
    }

    fun createRechargePayment(meter: DormMeter, lineLabel: String, amount: Double): DormRechargePayment {
        require(amount.isFinite() && amount in 0.01..4000.0) { "请输入 0.01～4000 元的充值金额" }
        val json = call("/vxPay/toV3Pay", JSONObject()
            .put("rechargetype", "1").put("paytype", "xsdbpay").put("payapp", "sl")
            .put("openid", meter.billingNumber).put("paytitle", "电量充值-${meter.location}-$lineLabel")
            .put("payfee", amount).put("xiaoqu", meter.campus)
            .put("fjhao", meter.billingNumber).put("xl", meter.line))
        val data = json.optJSONObject("data") ?: error("学校缴费平台未返回支付参数")
        val url = data.optString("code_url").trim()
        check(url.isNotBlank()) { "学校缴费平台未返回支付链接" }
        // The school's response has no merchant order ID. Resolve it from the signed cashier page.
        return DormRechargePayment(url, amount, meter.billingNumber)
    }

    fun loadHistory(meter: DormMeter, page: Int = 1, orderId: String? = null, paidOnly: Boolean = false): DormRechargeHistoryPage {
        require(page > 0)
        val conditions = mutableListOf(
            condition("paytype", "xsdbpay"), condition("openid", meter.billingNumber),
            condition("dbnumber", meter.billingNumber), condition("campusname", meter.campus),
            condition("createtime", "2000-01-01", "gte"), condition("createtime", today().plusDays(1).toString(), "lte")
        )
        orderId?.takeIf { it.isNotBlank() }?.let { conditions += condition("oderid", it) }
        if (paidOnly) conditions += condition("paymentstatus", "1")
        val json = call("/weixinDbCharges/search", searchBody(conditions, page, 20, "createtime desc"))
        return DormElectricityParser.history(json, meter, page, 20)
    }

    fun syncPaidHistory(meter: DormMeter, cancelled: () -> Boolean = { false }): List<DormRechargeHistoryEntry> {
        val records = linkedMapOf<String, DormRechargeHistoryEntry>()
        val seenPages = hashSetOf<List<String>>()
        var page = 1
        while (true) {
            check(!cancelled()) { "同步已取消" }
            val result = loadHistory(meter, page, paidOnly = true)
            check(!cancelled()) { "同步已取消" }
            check(result.entries.isEmpty() || seenPages.add(result.entries.map { it.orderId })) { "供电系统分页异常，请稍后同步" }
            result.entries.filter { it.paid }.forEach { records[it.orderId] = it }
            if (!result.hasMore) return mergeDormRechargeHistory(emptyList(), records.values.toList())
            check(result.entries.isNotEmpty() && page < 10_000) { "供电系统分页异常，请稍后同步" }
            page++
        }
    }

    private fun requireDirectory() = checkNotNull(directory) { "请先加载宿舍信息" }
    private fun call(path: String, body: JSONObject): JSONObject = transport.request(path, body).also {
        check(it.optString("returncode") == "0") {
            if (it.optString("returncode") in setOf("401", "403") || it.optInt("code") in setOf(401, 403))
                "供电系统要求重新认证，请从学校供电页面进入"
            else it.optString("message").ifBlank { it.optString("msg").ifBlank { "供电系统请求失败，请稍后重试" } }
        }
    }

    private fun condition(field: String, value: String, operation: String = "eq") =
        JSONObject().put("field", field).put("operation", operation).put("value", value)

    private fun searchBody(conditions: List<JSONObject>, page: Int, size: Int, order: String = "") = JSONObject()
        .put("fields", JSONArray()).put("pageNo", page).put("pageSize", size).put("orderBy", order)
        .put("condition", JSONArray(conditions)).put("subCondition", JSONObject())
}

internal object DormElectricityParser {
    fun meters(array: JSONArray): List<DormMeter> = buildList {
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val campus = row.optString("xiaoqu").trim()
            val building = row.optString("lhao").trim()
            val room = row.optString("fhao").trim()
            val number = row.optString("fjhao").trim()
            val line = row.optString("xl").trim()
            if (campus.isBlank() || campus.contains("商户") || building.isBlank() || room.isBlank() ||
                number.isBlank() || line.toIntOrNull() == null) continue
            add(DormMeter(campus, building, row.optString("cenghao").trim(), room, number, line))
        }
    }

    fun lines(json: JSONObject): List<DormElectricityOption> {
        check(json.optInt("code") == 200)
        val array = json.getJSONArray("data")
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .sortedBy { it.optInt("dictSort") }.mapNotNull {
                val name = it.optString("dictLabel").trim()
                val code = it.optString("dictValue").trim()
                if (name.isBlank() || code.toIntOrNull() == null) null else DormElectricityOption(name, code)
            }.distinctBy { it.code }
    }

    fun reading(json: JSONObject, meter: DormMeter): DormElectricityReading {
        val rows = json.optJSONObject("data")?.optJSONArray("records") ?: error("供电系统未返回电量数据")
        val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull {
            it.optString("fangnumer") == meter.billingNumber && it.optString("xl") == meter.line &&
                it.optString("xiaoqu") == meter.campus
        } ?: error("电表电量暂不可用，请稍后重试")
        fun number(key: String): Double = row.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: error("供电系统返回的电量数据异常")
        return DormElectricityReading(number("payele"), number("freeele"), number("lossele"),
            row.optString("dbstatech").ifBlank { "状态未知" }, meter.location,
            row.optString("updatetime").takeUnless { it == "null" }.orEmpty())
    }

    /**
     * The server filters the daily range on `sjdylday` only, so the room and line are re-checked
     * here. `dayele` is a four-decimal string; an unparsable value fails loudly instead of
     * becoming a fabricated 0 kWh day.
     */
    fun dailyPower(json: JSONObject, meter: DormMeter): List<DormDailyPowerEntry> {
        val rows = json.optJSONObject("data")?.optJSONArray("records") ?: error("供电系统未返回每日用电数据")
        val entries = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { row ->
            check(row.optString("fangnumer") == meter.billingNumber && row.optString("xiaoqu") == meter.campus) {
                "每日用电数据与当前宿舍不匹配"
            }
            check(row.optString("xl").trim() == meter.line) { "每日用电数据与当前线路不匹配" }
            val day = LocalDate.parse(
                row.optString("sjdylday").trim().take(10),
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
            )
            val kwh = row.optString("dayele").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("供电系统返回的每日用电量异常")
            check(kwh >= 0.0) { "供电系统返回的每日用电量异常" }
            DormDailyPowerEntry(day, kwh)
        }
        // A repeated day would silently hide an earlier row, so collapse it loudly rather than pick one.
        val duplicates = entries.groupingBy { it.day }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "供电系统返回了重复的每日用电记录" }
        return entries
    }

    fun history(json: JSONObject, meter: DormMeter, page: Int, pageSize: Int): DormRechargeHistoryPage {
        val data = json.optJSONObject("data") ?: error("供电系统未返回充值记录")
        val rows = data.optJSONArray("records") ?: error("充值记录格式异常")
        val entries = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { row ->
            check(row.optString("campusname") == meter.campus && row.optString("dbnumber") == meter.billingNumber) {
                "充值记录与当前宿舍不匹配"
            }
            DormRechargeHistoryEntry(
                id = row.optString("id").ifBlank { row.optString("oderid") },
                orderId = row.optString("oderid"),
                location = row.optString("paytitle").removePrefix("电量充值-").ifBlank { meter.location },
                amount = row.optString("payfee").toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("充值金额格式异常"),
                createdAt = DormPaymentTime.fromServer(row.optString("createtime")),
                paidAt = DormPaymentTime.fromServer(row.optString("paytime")),
                paymentStatus = row.optString("paymentstatus"),
                processingStatus = row.optString("processingstatus"),
                line = row.optString("linetype")
            ).also { check(it.id.isNotBlank() && it.orderId.isNotBlank()) { "充值订单号缺失" } }
        }.distinctBy { it.id }
        val hasMore = if (data.has("totalPage")) page < data.optInt("totalPage") else rows.length() >= pageSize
        return DormRechargeHistoryPage(entries, hasMore)
    }
}

private class DormHttpTransport : DormApiTransport {
    override fun request(path: String, body: JSONObject?): JSONObject {
        val connection = (URL("http://gysd.sdau.edu.cn/lwwaterpower$path").openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("clientid", "428a8310cd442757ae699df5d894f051")
            setRequestProperty("User-Agent", "WeSDAU-Android")
        }
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "供电系统请求失败（$code）" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            runCatching { JSONObject(text) }.getOrElse { error("供电系统返回异常，请稍后重试") }
        } finally {
            connection.disconnect()
        }
    }
}
