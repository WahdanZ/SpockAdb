package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that decides which open project an MCP call is about.
 *
 * This is the fix for a real defect: resolution used to be
 * `openProjects.singleOrNull { !it.isDisposed }`, so opening a second project made every
 * project-dependent tool — current activity, activity stack, fragments, the default logcat
 * filter — fail with "No project is open", which was both wrong and unactionable.
 */
class ProjectResolutionTest {

    private data class Proj(val name: String, val path: String?)

    private fun resolve(
        candidates: List<Proj>,
        selectedKey: String? = null,
    ): ProjectResolution.Outcome<Proj> = ProjectResolution.resolve(
        candidates = candidates,
        selectedKey = selectedKey,
        keysOf = { listOfNotNull(it.name, it.path) },
        // The real caller passes a labeller; these cases are about resolution, not labelling,
        // and the labelling has its own tests below.
        labelOf = { it.name },
    )

    private val app = Proj("app", "/home/dev/app")
    private val sdk = Proj("sdk", "/home/dev/sdk")

    @Test
    fun `nothing open resolves to none`() {
        assertEquals(ProjectResolution.Outcome.None, resolve(emptyList()))
    }

    @Test
    fun `one open project is used without being selected`() {
        assertEquals(
            ProjectResolution.Outcome.Resolved(app, ProjectResolution.Source.ONLY_OPEN),
            resolve(listOf(app)),
        )
    }

    @Test
    fun `several open projects with a selection resolve to the selected one`() {
        assertEquals(
            ProjectResolution.Outcome.Resolved(sdk, ProjectResolution.Source.SELECTED),
            resolve(listOf(app, sdk), selectedKey = "sdk"),
        )
    }

    @Test
    fun `a selection may name the project path as well as its name`() {
        assertEquals(
            ProjectResolution.Outcome.Resolved(sdk, ProjectResolution.Source.SELECTED),
            resolve(listOf(app, sdk), selectedKey = "/home/dev/sdk"),
        )
    }

    @Test
    fun `a selection matches regardless of case`() {
        assertEquals(
            ProjectResolution.Outcome.Resolved(sdk, ProjectResolution.Source.SELECTED),
            resolve(listOf(app, sdk), selectedKey = "SDK"),
        )
    }

    @Test
    fun `several open projects without a selection are ambiguous, and say which`() {
        // Refusing to guess is the point. Picking the focused window would be wrong exactly
        // when it matters most: an agent working while the developer looks at something else.
        assertEquals(
            ProjectResolution.Outcome.Ambiguous(listOf("app", "sdk")),
            resolve(listOf(sdk, app)),
        )
    }

    @Test
    fun `a selection naming a project that has since closed does not win`() {
        assertEquals(
            ProjectResolution.Outcome.Ambiguous(listOf("app", "sdk")),
            resolve(listOf(app, sdk), selectedKey = "closed-project"),
        )
    }

    @Test
    fun `a stale selection falls back to the only project left open`() {
        // The choice should not outlive the project it named.
        assertEquals(
            ProjectResolution.Outcome.Resolved(app, ProjectResolution.Source.ONLY_OPEN),
            resolve(listOf(app), selectedKey = "sdk"),
        )
    }

    @Test
    fun `a blank selection is treated as no selection`() {
        assertEquals(
            ProjectResolution.Outcome.Ambiguous(listOf("app", "sdk")),
            resolve(listOf(app, sdk), selectedKey = "   "),
        )
    }

    @Test
    fun `a project with no path is still matched by name`() {
        val pathless = Proj("scratch", null)
        assertEquals(
            ProjectResolution.Outcome.Resolved(pathless, ProjectResolution.Source.SELECTED),
            resolve(listOf(app, pathless), selectedKey = "scratch"),
        )
    }

    @Test
    fun `a duplicated name carries the path that tells the two apart`() {
        val fork = Proj("app", "/home/dev/app-fork")
        val label = ProjectResolution.labeller(listOf(app, fork), { it.name }, { it.path })

        assertEquals("app (/home/dev/app)", label(app))
        assertEquals("app (/home/dev/app-fork)", label(fork))
    }

    @Test
    fun `a unique name is not cluttered with its path`() {
        val label = ProjectResolution.labeller(listOf(app, sdk), { it.name }, { it.path })

        assertEquals("app", label(app))
    }

    @Test
    fun `a duplicated name with no path still says something`() {
        val pathless = Proj("app", null)
        val label = ProjectResolution.labeller(listOf(app, pathless), { it.name }, { it.path })

        assertEquals("app (no path)", label(pathless))
    }

    @Test
    fun `an ambiguity between same-named projects names choices the caller can make`() {
        val fork = Proj("app", "/home/dev/app-fork")
        val candidates = listOf(app, fork)

        val outcome = ProjectResolution.resolve(
            candidates = candidates,
            selectedKey = null,
            keysOf = { listOfNotNull(it.name, it.path) },
            labelOf = ProjectResolution.labeller(candidates, { it.name }, { it.path }),
        )

        // "app, app" would ask the caller to choose between two identical strings.
        assertEquals(
            ProjectResolution.Outcome.Ambiguous(
                listOf("app (/home/dev/app)", "app (/home/dev/app-fork)"),
            ),
            outcome,
        )
    }

    @Test
    fun `selecting by path wins over another project with the same name`() {
        // The defect this covers: the selection used to be stored as the project's *name*, so
        // it matched whichever the IDE listed first and the fork could never be targeted.
        val fork = Proj("app", "/home/dev/app-fork")

        val outcome = resolve(listOf(app, fork), selectedKey = "/home/dev/app-fork")

        assertEquals(ProjectResolution.Outcome.Resolved(fork, ProjectResolution.Source.SELECTED), outcome)
    }
}
