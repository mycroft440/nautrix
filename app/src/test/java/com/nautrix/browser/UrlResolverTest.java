package com.nautrix.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UrlResolverTest {
    @Test
    public void keepsHttpsAddress() {
        assertEquals("https://example.com/path", UrlResolver.resolve("https://example.com/path"));
    }

    @Test
    public void upgradesExplicitHttpAddress() {
        assertEquals("https://example.com/path", UrlResolver.resolve("http://example.com/path"));
    }

    @Test
    public void addsHttpsToDomain() {
        assertEquals("https://example.com/path", UrlResolver.resolve("example.com/path"));
    }

    @Test
    public void searchesWords() {
        assertEquals("https://duckduckgo.com/?q=teste+nautrix", UrlResolver.resolve("teste nautrix"));
    }

    @Test
    public void doesNotExecuteUnknownScheme() {
        assertEquals("https://duckduckgo.com/?q=javascript%3Aalert%281%29",
                UrlResolver.resolve("javascript:alert(1)"));
    }
}
