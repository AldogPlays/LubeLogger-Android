package app.lubelogger.wrapper

import org.junit.Assert.*
import org.junit.Test

class ServerAddressesTest {
    @Test fun `configuration normalizes identity without losing path case`() {
        val result = ServerAddresses.parse(" HTTPS://Example.COM:443/Garage/ ", "https://example.com/Garage")
        assertTrue(result is AddressConfiguration.Valid)
        val addresses = (result as AddressConfiguration.Valid).addresses
        assertEquals("https://example.com/Garage", addresses.preferred.url)
        assertEquals(1, addresses.ordered.size)
        assertNotEquals(addresses.preferred, valid("https://example.com/garage").preferred)
    }

    @Test fun `unsafe configured addresses return field errors without rewriting input`() {
        val invalid = listOf(
            "https://example.com?mode=1", "https://example.com#page",
            "https://user:pass@example.com", "https://example.com:65536",
            "https://example.com/a/../b", "https://example.com/%2e%2E/b",
            "https://example.com/a%2fb", "https://example.com/a%5Cb",
            "ftp://example.com", "not an address", "https://example.com:"
        )
        invalid.forEach { text ->
            val result = ServerAddresses.parse("https://valid.example", text)
            assertTrue(text, result is AddressConfiguration.Invalid)
            assertEquals(text, AddressField.FALLBACK, (result as AddressConfiguration.Invalid).errors.single().field)
        }
    }

    @Test fun `navigation is origin wide but page transfer requires base membership`() {
        val addresses = valid("https://home.example/garage", "https://away.example/cars")
        val active = addresses.preferred
        val replacement = addresses.fallback!!
        assertTrue(addresses.allowsNavigation("https://AWAY.example:443/login"))
        assertTrue(active.sameOrigin("https://HOME.example:443/elsewhere"))
        assertFalse(active.sameOrigin("http://home.example/garage"))
        assertFalse(addresses.allowsNavigation("https://other.example"))
        assertFalse(addresses.allowsNavigation("https://user@home.example"))
        assertEquals("https://away.example/cars/vehicles/a%20b?id=7#notes",
            active.equivalentPage("https://home.example/garage/vehicles/a%20b?id=7#notes", replacement))
        listOf(null, "broken", "https://other.example/garage/a", "https://home.example/garage2/a",
            "https://home.example/Garage/a", "https://home.example/garage/../elsewhere",
            "https://home.example/garage/a%2fb").forEach { page ->
            assertEquals(page, replacement.url, active.equivalentPage(page, replacement))
        }
        assertEquals("https://away.example/cars?q=1#here",
            active.equivalentPage("https://home.example/garage?q=1#here", replacement))
        assertEquals("https://away.example/cars/a", valid("https://home.example").preferred
            .equivalentPage("https://home.example/a", replacement))
    }

    private fun valid(preferred: String, fallback: String? = null): ServerAddresses =
        (ServerAddresses.parse(preferred, fallback) as AddressConfiguration.Valid).addresses
}
