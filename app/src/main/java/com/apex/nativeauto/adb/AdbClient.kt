package com.apex.nativeauto.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.PrintWriter
import java.net.Socket
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class AdbClient(private val context: Context) {

    init {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    suspend fun pair(port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(null, null, null)
            val socket = sslContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            socket.use {
                val out = it.outputStream
                out.write(pairingCode.toByteArray())
                out.flush()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deployDaemon(daemonPath: String, modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = "cp $daemonPath /data/local/tmp/apex_daemon && chmod 777 /data/local/tmp/apex_daemon && nohup /data/local/tmp/apex_daemon $modelPath > /dev/null 2>&1 &"
            Socket("127.0.0.1", 5555).use { socket ->
                val out = PrintWriter(socket.outputStream, true)
                out.println(cmd)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
