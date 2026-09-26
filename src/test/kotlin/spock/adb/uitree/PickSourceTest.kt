package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PickSourceTest {

    @Test
    fun `picks the file under the composable's package`() {
        val files = listOf("/p/feature/a/src/com/example/a/Screen.kt", "/p/feature/b/src/com/example/b/Screen.kt")
        assertEquals(files[1], pickSource(files, "com.example.b") { it })
    }

    @Test
    fun `falls back to the first file, and to nothing when there is none`() {
        assertEquals("/x/Screen.kt", pickSource(listOf("/x/Screen.kt", "/y/Screen.kt"), "com.other") { it })
        assertNull(pickSource(emptyList<String>(), "com.example") { it })
    }
}
