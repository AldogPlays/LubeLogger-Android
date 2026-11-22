package app.lubelogger.wrapper

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Real loopback HTTP fixture; no production HTTP behavior is replaced. */
internal class LocalHttpServer(private val respond: (String) -> Response) : AutoCloseable {
    data class Response(val status: Int = 200, val body: String = "<title>LubeLogger</title>", val headers: Map<String, String> = emptyMap())
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "test-http").apply { isDaemon = true } }
    val port: Int get() = socket.localPort

    init {
        workers.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute {
                    client.use {
                        runCatching {
                            it.soTimeout = 2000
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@runCatching
                            while (true) {
                                val header = input.readLine() ?: break
                                if (header.isEmpty()) break
                            }
                            val response = respond(path)
                            val body = response.body.toByteArray(Charsets.UTF_8)
                            val headers = mapOf("Content-Type" to "text/html", "Content-Length" to body.size.toString(), "Connection" to "close") + response.headers
                            val head = "HTTP/1.1 ${response.status} Response\r\n" + headers.entries.joinToString("") { header -> "${header.key}: ${header.value}\r\n" } + "\r\n"
                            it.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(body); flush() }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        workers.shutdownNow()
    }
}
