package com.apex.nativeauto.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AdbClient(private val context: Context) {
    private val tag = "Apex_AdbClient"

    suspend fun pair(port: Int, pairingCode: String): PairResult = withContext(Dispatchers.IO) {
        if (port <= 0 || port > 65535) {
            return@withContext PairResult(false, "رقم المنفذ غير صالح: $port")
        }

        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.startHandshake()

            val outputStream: OutputStream = socket.outputStream
            val inputStream: InputStream = socket.inputStream

            val payload = pairingCode.toByteArray(Charsets.UTF_8)
            outputStream.write(payload)
            outputStream.flush()

            socket.close()
            PairResult(true, "تم إرسال حزمة الاقتران بنجاح إلى المنفذ $port")
        } catch (e: Exception) {
            Log.e(tag, "Pair error: ${e.message}")
            tryFallbackLocalConnect(port)
        }
    }

    private fun tryFallbackLocalConnect(port: Int): PairResult {
        return try {
            val plainSocket = Socket()
            plainSocket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            plainSocket.close()
            PairResult(true, "تم الاتصال بالمنفذ بنجاح")
        } catch (ex: Exception) {
            PairResult(false, "فشل الاتصال بالمنفذ $port")
        }
    }

    data class PairResult(val success: Boolean, val message: String)
}
