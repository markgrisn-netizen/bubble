package com.example.engine

import com.example.engine.importpath.DirectImportResult
import com.example.engine.importpath.MangaDomSourceAdapter
import com.example.engine.importpath.SitemapSourceAdapter
import com.example.engine.importpath.SourceAdapterRegistry
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class SourceAdapterRegistryTest {

    @Test
    fun `MangaDomSourceAdapter extracts lazy-loaded comic images and filters avatars and logos`() {
        val html = """
            <html>
              <head><title>Chapter 42</title></head>
              <body>
                <img src="/assets/logo.png" />
                <img src="/users/avatar_1.jpg" />
                <img data-src="/pages/01.jpg" src="placeholder.gif" />
                <img data-lazy-src="https://cdn.example.com/pages/02.webp" />
                <img srcset="https://cdn.example.com/pages/03_sm.png 400w, https://cdn.example.com/pages/03_hd.png 1200w" />
              </body>
            </html>
        """.trimIndent()

        val pages = MangaDomSourceAdapter().parsePages(html, "https://reader.example.com/ch/42")
        assertEquals(
            listOf(
                "https://reader.example.com/pages/01.jpg",
                "https://cdn.example.com/pages/02.webp",
                "https://cdn.example.com/pages/03_hd.png"
            ),
            pages
        )
    }

    @Test
    fun `SitemapSourceAdapter extracts comic image URLs from XML`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset>
              <url><loc>https://cdn.example.com/page1.png</loc></url>
              <url><loc>https://cdn.example.com/about.html</loc></url>
              <url><loc>https://cdn.example.com/page2.webp?v=2</loc></url>
            </urlset>
        """.trimIndent()

        val pages = SitemapSourceAdapter().parsePages(xml, "https://cdn.example.com/sitemap.xml")
        assertEquals(
            listOf(
                "https://cdn.example.com/page1.png",
                "https://cdn.example.com/page2.webp?v=2"
            ),
            pages
        )
    }

    @Test
    fun `SourceAdapterRegistry returns BLOCKED_BY_SOURCE on HTTP 403 and parses HTTP 200 chapters`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/blocked") { exchange ->
            val body = "Attention Required! | Cloudflare".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/open-chapter") { exchange ->
            val body = """
                <html><head><title>Open Comic Ch 1</title></head>
                <body><img data-src="/img/p1.png" /><img data-src="/img/p2.png" /></body></html>
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val registry = SourceAdapterRegistry()

            val blockedRes = registry.fetchChapter("http://127.0.0.1:$port/blocked")
            assertTrue("Expected BlockedBySource", blockedRes is DirectImportResult.BlockedBySource)
            val blocked = blockedRes as DirectImportResult.BlockedBySource
            assertEquals("BLOCKED_BY_SOURCE", blocked.code)
            assertEquals(403, blocked.httpCode)

            val okRes = registry.fetchChapter("http://127.0.0.1:$port/open-chapter")
            assertTrue("Expected Success", okRes is DirectImportResult.Success)
            val ok = okRes as DirectImportResult.Success
            assertEquals("Open Comic Ch 1", ok.chapterTitle)
            assertEquals(2, ok.imageUrls.size)
        } finally {
            server.stop(0)
        }
    }
}
