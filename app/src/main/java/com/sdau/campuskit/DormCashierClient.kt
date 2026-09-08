package com.sdau.campuskit

import org.json.JSONObject
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64

private const val CASHIER_BASE = "https://pay.sinojy.cn/sltf-outside/"

internal data class DormCashierResponse(val bytes: ByteArray, val contentType: String) {
    fun json() = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
        .getOrElse { error("收银台返回异常，请稍后从充值记录核对订单") }
}

internal fun interface DormCashierTransport {
    fun request(url: String, form: Map<String, String>?): DormCashierResponse
}

internal data class DormCashierOrder(
    val orderId: String, val payNumber: String, val form: Map<String, String>
)

/** Native QR preparation only: never submits bank credentials or confirms a payment. */
internal class DormCashierClient(private val transport: DormCashierTransport) {
    fun prepare(payment: DormRechargePayment): DormRechargeQr {
        DormCashierParser.requireCashierUrl(payment.paymentUrl)
        val page = transport.request(payment.paymentUrl, null)
        val order = DormCashierParser.order(page.bytes.toString(Charsets.UTF_8), payment)
        fun post(path: String) = transport.request(CASHIER_BASE + path, order.form).json()

        DormCashierParser.requireNoExtraFee(post("inter/ajaxCharge"), payment.amount)
        check(post("inter/nextAgoCheck").optString("code") == "0000") {
            "充值订单已失效或暂不可支付，请在充值记录中核对"
        }
        // Official desktop flow. “交易成功” here means QR preparation, NOT payment success.
        val qrUrl = DormCashierParser.qrUrl(post("wechatInter/nativePayReady"), order)
        val image = transport.request(qrUrl, null)
        DormCashierParser.requireQrImage(image)
        return DormRechargeQr(image.bytes, payment.amount, order.orderId)
    }
}

internal object DormCashierParser {
    fun requireCashierUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { error("学校返回的收银台地址无效") }
        check(uri.scheme == "https" && uri.host == "pay.sinojy.cn" && uri.port in listOf(-1, 443) &&
            uri.userInfo == null && uri.fragment == null && uri.path.startsWith("/sltf-outside/")) {
            "学校收银台地址已变更，请更新应用后重试"
        }
        return uri
    }

    private fun payNumber(url: String): String {
        val uri = requireCashierUrl(url)
        check(uri.path == "/sltf-outside/inter/orderQRUrl") { "学校返回的订单链接异常" }
        val values = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("pay_no=") }
        check(values.size == 1) { "收银台订单号缺失" }
        return values.single().substringAfter('=').also {
            check(it.matches(Regex("[0-9]{10,64}"))) { "收银台订单号无效" }
        }
    }

    fun order(html: String, payment: DormRechargePayment): DormCashierOrder {
        val payNumber = payNumber(payment.paymentUrl)
        val document = Jsoup.parse(html)
        fun input(id: String) = document.getElementById(id)?.attr("value")?.takeIf { it.isNotBlank() }
            ?: error("收银台订单已失效或页面已变更，请在充值记录中核对")
        val dataText = input("data")
        val signText = input("signdata")
        val data = JSONObject(dataText)
        val sign = JSONObject(signText)
        check(data.optString("order_id") == payNumber && data.optString("userid") == payment.billingNumber &&
            payment.billingNumber.isNotBlank() && data.optString("merch_account_name") == "山东农业大学") {
            "收银台订单与当前宿舍不匹配，已停止生成支付码"
        }
        check(money(data, "order_fee") == money(payment.amount) && money(sign, "order_fee") == money(payment.amount)) {
            "收银台金额与输入金额不一致，已停止生成支付码"
        }
        val orderId = data.optString("merchant_order_id")
        check(orderId.matches(Regex("[A-Za-z0-9_-]{8,100}")) &&
            (payment.orderId.isBlank() || payment.orderId == orderId)) { "学校充值订单号不匹配" }
        val method = document.selectFirst("[data-payment_means=20][data-path_no]")
            ?: error("收银台暂未提供建行扫码支付")
        val path = method.attr("data-path_no")
        check(path.matches(Regex("[0-9]{1,10}"))) { "收银台支付通道异常" }
        val businessId = input("sl_busi_id")
        check(businessId == data.optString("sl_busi_id")) { "收银台业务订单不匹配" }
        return DormCashierOrder(orderId, payNumber, linkedMapOf(
            // Preserve the original signed strings, including whitespace and property order.
            "data" to dataText, "signdata" to signText, "datasign" to input("datasign"),
            "payment_means" to "20", "path_no" to path, "isMobile" to "false",
            "sl_busi_id" to businessId, "url" to payment.paymentUrl,
            "coupon_fee" to "0", "coupon_ids" to ""
        ))
    }

    fun requireNoExtraFee(json: JSONObject, amount: Double) {
        check(json.optString("returncode") == "0000") { "收银台暂不可用，请稍后核对订单" }
        val fee = json.optJSONObject("respdata") ?: error("收银台未返回费用信息")
        check(money(fee, "orderfee") == money(amount) && money(fee, "transfee") == money(amount) &&
            money(fee, "myfee") == BigDecimal.ZERO && money(fee, "couponfee") == BigDecimal.ZERO) {
            "收银台金额或手续费有变化，请到学校供电页面确认后支付"
        }
    }

    fun qrUrl(json: JSONObject, order: DormCashierOrder): String {
        check(json.optString("returncode") == "0000" && json.optBoolean("flag")) {
            "收银台未生成支付码，请从充值记录核对订单后重试"
        }
        check(json.optString("path_order_id") == order.payNumber &&
            json.optString("path_no") == order.form.getValue("path_no")) { "支付码订单不匹配" }
        val returnedOrder = json.optJSONObject("data") ?: error("支付码缺少订单信息")
        check(returnedOrder.optString("merchant_order_id") == order.orderId &&
            returnedOrder.optString("order_id") == order.payNumber) { "支付码与学校订单不匹配" }
        val encoded = json.optString("code_url")
        val decoded = runCatching { Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8) }
            .getOrElse { error("收银台支付码格式异常") }
        check(payNumber(decoded) == order.payNumber) { "支付码指向其他订单，已停止展示" }
        return CASHIER_BASE + "inter/nativePayQrcode?" + encodeDormCashierForm(linkedMapOf(
            "code_url" to encoded, "path_no" to order.form.getValue("path_no"),
            "payment_means" to "20", "flag" to "true"
        ))
    }

    fun requireQrImage(response: DormCashierResponse) {
        val bytes = response.bytes
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        // The official endpoint serves PNG bytes with Content-Type: image/jpeg;charset=UTF-8.
        val mime = response.contentType.substringBefore(';').trim().lowercase()
        check(mime in setOf("image/png", "image/jpeg") && bytes.size in 33..1_048_576 &&
            bytes.take(8).toByteArray().contentEquals(signature) && bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "IHDR") {
            "收银台未返回有效二维码图片"
        }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8)
        check(dimensions.int in 128..2048 && dimensions.int in 128..2048) { "收银台二维码尺寸异常" }
    }

    private fun money(value: Double): BigDecimal {
        check(value.isFinite() && value in 0.01..4000.0) { "充值金额无效" }
        return BigDecimal.valueOf(value).stripTrailingZeros()
    }
    private fun money(json: JSONObject, key: String): BigDecimal = runCatching {
        BigDecimal(json.getString(key)).stripTrailingZeros()
    }.getOrElse { error("收银台金额格式异常") }
}

internal fun encodeDormCashierForm(form: Map<String, String>) = form.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}

/** Each QR request owns its cookies and connection; cancellation cannot affect another request. */
internal class DormCashierHttpTransport : DormCashierTransport {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null

    fun cancel() { cancelled = true; active?.disconnect() }

    override fun request(url: String, form: Map<String, String>?): DormCashierResponse {
        check(!cancelled && !Thread.currentThread().isInterrupted) { "已取消获取支付码" }
        val uri = DormCashierParser.requireCashierUrl(url)
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = if (form == null) "GET" else "POST"
            // Do not silently forward signed parameters to redirects or replay a payment request.
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            setRequestProperty("Referer", CASHIER_BASE)
            if (form != null) {
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
            }
            cookies.get(uri, emptyMap()).forEach { (key, values) -> setRequestProperty(key, values.joinToString("; ")) }
        }
        active = connection
        return try {
            check(!cancelled) { "已取消获取支付码" }
            if (form != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(encodeDormCashierForm(form).toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "收银台连接失败，请从充值记录核对订单后重试" }
            cookies.put(uri, connection.headerFields.filterKeys { it != null })
            val bytes = connection.inputStream.use { stream ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!cancelled && !Thread.currentThread().isInterrupted) { "已取消获取支付码" }
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(result.size() + count <= 1_048_576) { "收银台响应过大" }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            DormCashierResponse(bytes, connection.contentType.orEmpty())
        } finally {
            active = null
            connection.disconnect()
        }
    }
}
