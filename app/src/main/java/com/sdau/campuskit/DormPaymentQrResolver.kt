package com.sdau.campuskit

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Resolves the new school's signed cashier flow off the UI thread, without a hidden WebView. */
internal class DormPaymentQrResolver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var request: DormCashierHttpTransport? = null
    private var work: Future<*>? = null

    fun resolve(payment: DormRechargePayment, onResult: (Result<DormRechargeQr>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancel()
        val token = generation
        val transport = DormCashierHttpTransport()
        request = transport
        work = executor.submit {
            val result = runCatching {
                DormCashierClient(transport).prepare(payment).also { qr ->
                    val bitmap = BitmapFactory.decodeByteArray(qr.imageBytes, 0, qr.imageBytes.size)
                        ?: error("支付码图片损坏，请稍后重试")
                    bitmap.recycle()
                }
            }
            mainHandler.post {
                if (token == generation) {
                    request = null
                    work = null
                    onResult(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        request?.cancel()
        request = null
        work?.cancel(true)
        work = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun dispose() { cancel(); executor.shutdownNow() }
}
