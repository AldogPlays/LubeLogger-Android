package app.lubelogger.wrapper

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class HttpServerProbeTest {
    @Test fun `probe follows local redirects but never follows a different origin`() {
        val foreignRequests = AtomicInteger()
        LocalHttpServer { foreignRequests.incrementAndGet(); LocalHttpServer.Response() }.use { other ->
            LocalHttpServer { path ->
                when (path) {
                    "/local" -> LocalHttpServer.Response(302, headers = mapOf("Location" to "/valid"))
                    "/foreign" -> LocalHttpServer.Response(302, headers = mapOf("Location" to "http://127.0.0.1:${other.port}/"))
                    else -> LocalHttpServer.Response()
                }
            }.use { server ->
                val probe = HttpServerProbe()
                assertTrue(probe.validates(address(server, "/local"), 1000))
                assertFalse(probe.validates(address(server, "/foreign"), 1000))
                assertEquals(0, foreignRequests.get())
            }
        }
    }

    @Test fun `probe rejects bad status content unrecognized pages and exhausted redirects`() {
        LocalHttpServer { path ->
            when {
                path == "/error" -> LocalHttpServer.Response(503)
                path == "/plain" -> LocalHttpServer.Response(headers = mapOf("Content-Type" to "text/plain"))
                path == "/other" -> LocalHttpServer.Response(body = "<title>Another app</title>")
                path.startsWith("/loop") -> LocalHttpServer.Response(302, headers = mapOf("Location" to "/loop"))
                path == "/large" -> LocalHttpServer.Response(body = " ".repeat(512 * 1024) + "LubeLogger")
                else -> LocalHttpServer.Response()
            }
        }.use { server ->
            val probe = HttpServerProbe()
            listOf("/error", "/plain", "/other", "/loop", "/large").forEach { path ->
                assertFalse(path, probe.validates(address(server, path), 1000))
            }
            assertTrue(probe.validates(address(server, "/valid"), 1000))
        }
    }

    @Test fun `probe times out stalled responses`() {
        LocalHttpServer {
            Thread.sleep(500)
            LocalHttpServer.Response()
        }.use { server ->
            assertFalse(HttpServerProbe().validates(address(server, "/slow"), 50))
        }
    }

    private fun address(server: LocalHttpServer, path: String): ServerAddress =
        (ServerAddresses.parse("http://127.0.0.1:${server.port}$path") as AddressConfiguration.Valid).addresses.preferred
}
