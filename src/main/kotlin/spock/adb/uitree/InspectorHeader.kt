package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The line above the tree that says what was captured: a framework badge, where and when the
 * capture was taken, and whether Compose test tags could be seen.
 *
 * States the framework outright. On a Compose screen "Activity → View hierarchy" is the wrong
 * mental model, and a developer reading a flat list of `android.view.View` nodes has no other way
 * to tell that they are looking at Compose semantics.
 */
internal class InspectorHeader(private val onNotice: (String) -> Unit) : JPanel(BorderLayout()) {

    private val badge = JBLabel().apply {
        font = JBUI.Fonts.smallFont().asBold()
    }
    private val summary = JBLabel().apply {
        foreground = JBColor.GRAY
    }
    private val tags = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
    }
    private val banner = testTagBanner()

    init {
        isVisible = false
        val line = JPanel(BorderLayout(JBUI.scale(GAP * 2), 0)).apply {
            border = JBUI.Borders.empty(2, GAP * 2, 2, GAP * 2)
            add(badge, BorderLayout.WEST)
            add(summary, BorderLayout.CENTER)
            add(tags, BorderLayout.EAST)
        }
        add(line, BorderLayout.NORTH)
        add(banner, BorderLayout.CENTER)
    }

    fun show(observation: UiObservation, device: DeviceLabel) {
        val tree = observation.tree
        badge.text = frameworkBadge(tree.framework)
        badge.toolTipText = tree.framework.description
        badge.foreground = frameworkColour(tree.framework)
        badge.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(frameworkColour(tree.framework), JBUI.scale(BADGE_ARC), 1),
            JBUI.Borders.empty(0, GAP + 2),
        )

        summary.text = captureSummary(observation, device.name, ZoneId.systemDefault())
        // The full description, limits included, for whoever hovers: too long for one docked line.
        summary.toolTipText = "<html>" + StringUtil.escapeXmlEntities(observation.summary()) + "<br><br>" +
            StringUtil.escapeXmlEntities(observation.limitsNote()) + "</html>"

        tags.text = tagExposure(tree).orEmpty()
        tags.isVisible = tags.text.isNotEmpty()

        banner.isVisible = tree.testTagSupport == UiTree.TestTagSupport.UNAVAILABLE
        isVisible = true
        revalidate()
        repaint()
    }

    /**
     * Shown when Compose test tags are not exposed.
     *
     * A banner rather than a grey hint: this is the difference between an agent that can address
     * elements by `testTag` and one reduced to matching on visible text, and it is fixed by one
     * line the developer can copy from here. As a grey note beside the framework name it read as
     * trivia, so it was missed by exactly the people it is for.
     *
     * Drawn with the platform's banner colours rather than `InlineBanner`, which is not in 2023.2.
     */
    private fun testTagBanner(): JPanel {
        val title = JBLabel("No exposed Compose test tags were observed in this capture.").apply {
            icon = AllIcons.General.Warning
            font = font.deriveFont(Font.BOLD)
        }
        val advice = InspectorNote(grey = false).apply {
            text = "Use text or content description, or add tags and expose them on their subtree."
            border = JBUI.Borders.emptyLeft(AllIcons.General.Warning.iconWidth + title.iconTextGap)
        }
        val message = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(advice, BorderLayout.CENTER)
        }

        val copy = ActionLink(
            "Copy Modifier",
            ActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(TEST_TAG_SNIPPET))
                onNotice("Copied $TEST_TAG_SNIPPET")
            },
        ).apply { toolTipText = TEST_TAG_SNIPPET }

        val inner = JPanel(BorderLayout(JBUI.scale(GAP * 2), 0)).apply {
            background = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
            border = BorderFactory.createCompoundBorder(
                RoundedLineBorder(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR, JBUI.scale(BADGE_ARC * 2), 1),
                JBUI.Borders.empty(GAP + 2, GAP * 2),
            )
            add(message, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(copy)
                },
                BorderLayout.EAST,
            )
        }
        return JPanel(BorderLayout()).apply {
            isVisible = false
            border = JBUI.Borders.empty(2, GAP * 2, GAP, GAP * 2)
            add(inner, BorderLayout.CENTER)
        }
    }

    private companion object {
        const val GAP = 4
        const val BADGE_ARC = 6

        val COMPOSE_COLOR = JBColor(0x1F6F4A, 0x57BA8C)
        val HYBRID_COLOR = JBColor(0x8A6100, 0xE0A030)

        fun frameworkColour(framework: UiFramework): JBColor = when (framework) {
            UiFramework.COMPOSE -> COMPOSE_COLOR
            UiFramework.HYBRID -> HYBRID_COLOR
            else -> JBColor.GRAY
        }
    }
}

/** Exactly what the developer has to add, so Copy Modifier pastes something that compiles. */
internal const val TEST_TAG_SNIPPET = "Modifier.semantics { testTagsAsResourceId = true }"

/** The badge's one word; the full [UiFramework.description] is its tooltip. */
internal fun frameworkBadge(framework: UiFramework): String = when (framework) {
    UiFramework.COMPOSE -> "Compose"
    UiFramework.HYBRID -> "Hybrid"
    UiFramework.VIEWS -> "Views"
    UiFramework.UNKNOWN -> "Unknown"
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * The capture in one short line — `Pixel 7 · com.example · 10:15:02 · 1080x2400 rot 0 · 420 dpi` —
 * the same facts as [UiObservation.summary], worded to fit beside the badge in a docked panel.
 * The time is the host's, in [zone].
 */
internal fun captureSummary(observation: UiObservation, deviceName: String, zone: ZoneId): String = listOf(
    deviceName,
    observation.windowPackage ?: "window unknown",
    CLOCK.format(Instant.ofEpochMilli(observation.startedAtMillis).atZone(zone)),
    observation.describeViewport().let { if (it == "unknown") "viewport unknown" else it },
    observation.describeDensity(),
).joinToString(" · ")

/** Whether Compose test tags could be seen, or null on a screen where the question does not arise. */
internal fun tagExposure(tree: UiTree): String? = when (tree.testTagSupport) {
    UiTree.TestTagSupport.AVAILABLE -> "Test tags exposed"
    UiTree.TestTagSupport.UNAVAILABLE -> "No test tags exposed"
    UiTree.TestTagSupport.NOT_APPLICABLE -> null
}
