package com.sdau.campuskit

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Correct the payment platform's slow clock once, when parsing server order timestamps. */
internal object DormPaymentTime {
    private const val CORRECTION_MINUTES = 8L
    private val displayFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)

    fun fromServer(value: String): String {
        val text = value.trim()
        if (text.isEmpty() || text == "null") return ""
        return runCatching {
            LocalDateTime.parse(text.replace(' ', 'T'), DateTimeFormatter.ISO_DATE_TIME)
                .plusMinutes(CORRECTION_MINUTES).format(displayFormat)
        }.getOrDefault(text)
    }
}

/** Server records only. Legacy preference/public-file copies are intentionally left untouched. */
internal data class DormRechargeHistoryEntry(
    val id: String,
    val orderId: String,
    val location: String,
    val amount: Double,
    val createdAt: String,
    val paidAt: String,
    val paymentStatus: String,
    val processingStatus: String,
    val line: String,
    val rechargedKwh: Double? = null,
    val beforePaidKwh: Double? = null,
    val beforeTotalKwh: Double? = null
) {
    val paid: Boolean get() = paymentStatus == "1"
    val credited: Boolean get() = paid && processingStatus == "2"
    val paymentLabel: String get() = when (paymentStatus) {
        "0" -> "未支付"
        "1" -> "已支付"
        else -> "支付状态未知"
    }
    val processingLabel: String get() = when (processingStatus) {
        "0" -> "充值待执行"
        "1" -> "充值中"
        "2" -> "充值成功"
        else -> "充值状态未知"
    }
    val summary: String get() = if (paid) processingLabel else paymentLabel
}

internal data class DormRechargeHistoryPage(val entries: List<DormRechargeHistoryEntry>, val hasMore: Boolean)
internal data class DormPendingPayment(val payment: DormRechargePayment, val meter: DormMeter)

/** Upsert without losing older pages or duplicating orders as their status changes. */
internal fun mergeDormRechargeHistory(
    existing: List<DormRechargeHistoryEntry>, incoming: List<DormRechargeHistoryEntry>
): List<DormRechargeHistoryEntry> = (incoming + existing).distinctBy { it.orderId }
    .filter { it.paid }.sortedWith(compareByDescending<DormRechargeHistoryEntry> { it.createdAt }.thenBy { it.orderId })

internal fun formatDormRechargeDelta(kwh: Double): String = java.lang.String.format(java.util.Locale.US, "%+.2f 度", kwh)

/** Round-robin extra checks for unfinished orders outside the latest page. */
internal class DormHistoryPollTargets {
    private var lastOrderId: String? = null
    fun next(orderIds: List<String>): String? {
        if (orderIds.isEmpty()) return null
        val index = (orderIds.indexOf(lastOrderId) + 1) % orderIds.size
        return orderIds[index].also { lastOrderId = it }
    }
}
