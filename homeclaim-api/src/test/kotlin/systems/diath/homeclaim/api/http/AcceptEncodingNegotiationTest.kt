package systems.diath.homeclaim.api.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptEncodingNegotiationTest {
    @Test
    fun `server preferences are stable for equal q values`() {
        val negotiation = AcceptEncodingNegotiation.parse(listOf("BR, zStD"))
        assertEquals("br", negotiation.select(ContentEncodingPolicy.STATIC))
        assertEquals("zstd", negotiation.select(ContentEncodingPolicy.DYNAMIC))
    }

    @Test
    fun `explicit qualities override wildcard and zero excludes coding`() {
        assertEquals("zstd", AcceptEncodingNegotiation.parse(listOf("br;q=0, zstd;q=1")).select(ContentEncodingPolicy.STATIC))
        assertEquals("br", AcceptEncodingNegotiation.parse(listOf("zstd;q=0, br;q=1")).select(ContentEncodingPolicy.DYNAMIC))
        assertEquals("zstd", AcceptEncodingNegotiation.parse(listOf("*;q=1, br;q=0")).select(ContentEncodingPolicy.STATIC))
    }

    @Test
    fun `identity is the fallback for unsupported and zero-quality codings`() {
        assertEquals("identity", AcceptEncodingNegotiation.parse(listOf("gzip")).select(ContentEncodingPolicy.DYNAMIC))
        assertEquals("identity", AcceptEncodingNegotiation.parse(listOf("br;q=0, zstd;q=0")).select(ContentEncodingPolicy.STATIC))
        assertEquals("identity", AcceptEncodingNegotiation.parse(null).select(ContentEncodingPolicy.STATIC))
        assertTrue(AcceptEncodingNegotiation.parse(listOf("identity;q=0, gzip")).select(ContentEncodingPolicy.STATIC) == null)
        assertEquals(null, AcceptEncodingNegotiation.parse(listOf("*;q=0")).select(ContentEncodingPolicy.DYNAMIC))
        assertEquals("identity", AcceptEncodingNegotiation.parse(listOf("*;q=0, identity;q=1")).select(ContentEncodingPolicy.DYNAMIC))
        assertFalse(AcceptEncodingNegotiation.parse(listOf("identity;q=0, gzip")).isAcceptable(ContentEncodingPolicy.STATIC))
    }

    @Test
    fun `multiple field values and whitespace are parsed`() {
        val negotiation = AcceptEncodingNegotiation.parse(listOf(" gzip ; q=0.1 ", "br ; q=0.7", "zstd;q=0.8", "identity;q=0"))
        assertEquals("zstd", negotiation.select(ContentEncodingPolicy.STATIC))
    }
}
