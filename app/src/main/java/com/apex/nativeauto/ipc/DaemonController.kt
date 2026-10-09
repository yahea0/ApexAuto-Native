package com.apex.nativeauto.ipc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DaemonController {
    private val socketAddress = "/data/local/tmp/apex_daemon.sock"

    suspend fun sendTap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketAddress, LocalSocketAddress.Namespace.FILESYSTEM))
            val output = socket.outputStream
            val input = socket.inputStream

            val cmd = "TAP $x $y\n"
            output.write(cmd.toByteArray())
            output.flush()

            val buffer = ByteArray(16)
            val length = input.read(buffer)
            socket.close()

            length > 0 && String(buffer, 0, length).startsWith("OK")
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun stopDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketAddress, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.outputStream.write("STOP\n".toByteArray())
            socket.outputStream.flush()
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
