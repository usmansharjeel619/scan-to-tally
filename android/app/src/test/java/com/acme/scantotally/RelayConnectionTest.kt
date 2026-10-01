package com.acme.scantotally

import com.acme.scantotally.data.RelayApi
import com.acme.scantotally.data.RelayHttpException
import java.net.ServerSocket
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RelayConnectionTest {
    private class TestServer(code: Int, body: String) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray()
                        val header = "HTTP/1.1 $code Test\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                    }
                } catch (_: Exception) { if (socket.isClosed) break }
            }
        }.apply { isDaemon = true; start() }
        fun stop() { socket.close(); worker.join(1000) }
    }

    private fun server(code: Int, body: String) = TestServer(code, body)

    @Test
    fun `rejected login and server errors cannot look like checking or empty successful sync`() = runBlocking {
        for (code in listOf(401, 403, 503)) {
            val server = server(code, """{"error":"unauthorized"}""")
            try {
                val api = RelayApi("http://127.0.0.1:${server.port}", "test")
                try { api.status(); fail("status accepted HTTP $code") }
                catch (e: RelayHttpException) { assertEquals(code, e.code) }
                try { api.sync(); fail("sync accepted HTTP $code") }
                catch (e: RelayHttpException) { assertEquals(code, e.code) }
            } finally { server.stop() }
        }
    }

    @Test
    fun `malformed successful sync cannot clear cached inventory`() = runBlocking {
        val server = server(200, """{"error":"wrong endpoint"}""")
        try {
            val api = RelayApi("http://127.0.0.1:${server.port}", "test")
            try { api.sync(); fail("incomplete sync accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { server.stop() }
    }

    @Test
    fun `real online response remains online`() = runBlocking {
        val server = server(200, """{"connector":{"health":"ONLINE","company":"Test"},"pending":0,"failed":0}""")
        try {
            val api = RelayApi("http://127.0.0.1:${server.port}", "test")
            assertEquals("ONLINE", api.status().connector.health)
        } finally { server.stop() }
    }
}
