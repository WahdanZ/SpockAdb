package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstalledPackagesTest {

    @Test
    fun `parses pm output into sorted package names`() {
        val output = "package:com.zeta.app\r\npackage:com.example.app\r\n\r\npackage:com.example.app\r\n"

        assertEquals(listOf("com.example.app", "com.zeta.app"), InstalledPackages.parse(output))
    }

    @Test
    fun `lines that are not package names are dropped`() {
        val output = "package:com.example.app\nError: something went wrong\npackage:bad;name\n"

        assertEquals(listOf("com.example.app"), InstalledPackages.parse(output))
    }

    @Test
    fun `a diagnostic that reads like a package name is still not one`() {
        // `pm` writes warnings to the same stream, and a line such as this one passes every
        // check a component name has to pass — except for being a `package:` line.
        val output = "package:com.example.app\nWARNING.line.without.the.prefix\n"

        assertEquals(listOf("com.example.app"), InstalledPackages.parse(output))
    }

    @Test
    fun `the open project's app comes first when it is installed`() {
        val installed = listOf("com.a", "com.example.app", "com.z")
        val expected = listOf("com.example.app", "com.a", "com.z")

        assertEquals(expected, InstalledPackages.choices(installed, "com.example.app"))
    }

    @Test
    fun `an uninstalled or unknown project app leaves the list as it is`() {
        val installed = listOf("com.a", "com.z")

        assertEquals(installed, InstalledPackages.choices(installed, "com.example.app"))
        assertEquals(installed, InstalledPackages.choices(installed, null))
    }
}
