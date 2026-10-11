package spock.adb.flutter.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StripUrlQueriesTest {

    @Test
    fun `brackets in a query are part of it`() {
        assertEquals(
            "Navigator: /search",
            stripUrlQueries("Navigator: /search?filter[tag]=x&email=a@b.c"),
        )
        assertEquals(
            "HTTP request failed, https://cdn.example.com/a.png",
            stripUrlQueries("HTTP request failed, https://cdn.example.com/a.png?ids[]=1&email=a@b.c"),
        )
    }

    @Test
    fun `a route inside parentheses keeps its closing one, and a query's own parentheses go with it`() {
        assertEquals("MaterialPageRoute<dynamic>(/item/42)", stripUrlQueries("MaterialPageRoute<dynamic>(/item/42)"))
        assertEquals(
            "MaterialPageRoute<dynamic>(/item/42)",
            stripUrlQueries("MaterialPageRoute<dynamic>(/item/42?ref=spock&email=a@b.c)"),
        )
        assertEquals(
            "see (https://x.test/a) and more",
            stripUrlQueries("see (https://x.test/a?q=f(1)&e=a@b.c) and more"),
        )
    }

    @Test
    fun `a host without a scheme loses its query too`() {
        assertEquals("GET api.example.com/me failed", stripUrlQueries("GET api.example.com/me?email=a@b.c failed"))
        assertEquals("api.example.com:8443/me", stripUrlQueries("api.example.com:8443/me?session=s"))
    }

    @Test
    fun `text with no URL in it is left alone`() {
        listOf(
            "Bad state: what?",
            "RenderFlex#8de67 relayoutBoundary=up5",
            "IgnorePointer-[GlobalKey#20800]",
            "Is lib/a.dart?x right? Yes.",
            "file:///p/lib/a.dart:3:5",
        ).forEach { assertEquals(it, stripUrlQueries(it)) }
    }

    @Test
    fun `a redacted token inside the query does not end it`() {
        assertEquals(
            "visit: http://127.0.0.1:9102/\nnext",
            stripUrlQueries(
                "visit: http://127.0.0.1:9102/#/inspector?uri=http%3A%2F%2F127.0.0.1%3A1%2F<redacted>%2F&x=0\nnext",
            ),
        )
    }
}
