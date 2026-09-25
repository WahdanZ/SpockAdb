package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.awt.Component
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * Takes the developer from a selected element to the source that most likely produced it.
 *
 * Every selection starts a search ([SourceLocator]) in a non-blocking read action off the EDT, and
 * the one before it is cancelled, so holding an arrow key down does not queue a search per row. The
 * answer fills the details pane's [line]; with **Autoscroll to Source** on it is also opened, without
 * taking focus from the tree, so the arrow keys keep working — if the developer is still in the
 * Inspector when it arrives ([autoscrollFollows]). Double-click, Enter, the Edit Source
 * shortcut, the context menu and the details link always open it with focus, and offer a list when
 * several places matched.
 *
 * An element with nothing of its own to find borrows from its relatives, and when nothing at all
 * is found the screen's Activity — read just after the capture — is the answer, said as such.
 *
 * While the IDE is indexing the word index cannot be read, so it says so rather than searching, and
 * fills the line in once indexing ends.
 */
internal class SourceNavigator(
    private val project: Project,
    private val onNotice: (String) -> Unit,
) : Disposable {

    val line = SourceLine { link -> jump { it.showUnderneathOf(link) } }

    private var tree: JTree? = null

    /** Where focus must be for Autoscroll to open an answer: see [autoscrollFollows]. */
    private var focusScope: JComponent? = null
    private var selection: Selection? = null
    private var resolved: SourceResult? = null
    private var pending: CancellablePromise<SourceResult>? = null

    /** Bumped by every new request, so an answer that arrives late is dropped. */
    private var generation = 0
    private var disposed = false

    /** A `runWhenSmart` is registered: see [searchAfterIndexing]. */
    private var awaitingSmart = false

    private var autoscroll: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(AUTOSCROLL_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(AUTOSCROLL_KEY, value, true)

    private data class Selection(
        val query: SourceQuery,
        val windowPackage: String?,
        val screen: Set<String>,
        val relatives: List<SourceRelative>,
        val activity: String?,
    ) {
        val searchable: Boolean get() = !query.isEmpty || relatives.isNotEmpty() || activity != null
    }

    val autoscrollAction: AnAction = object :
        ToggleAction(
            "Autoscroll to Source",
            "Open the source of each element as it is selected, keeping focus in the tree",
            AllIcons.General.AutoscrollToSource,
        ),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = autoscroll
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            autoscroll = state
            if (state) resolved?.best?.let { open(it, requestFocus = false) }
        }
    }

    val jumpAction: AnAction = object : DumbAwareAction(
        "Jump to Source",
        "Open the source this element most likely comes from",
        AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selection?.searchable == true
        }
        override fun actionPerformed(e: AnActionEvent) = jumpFromTree()
    }

    /**
     * Double-click and Enter jump; so does the IDE's Edit Source shortcut, which the context menu
     * shows. Double-click no longer expands a row — nearly every row that matters, a button with
     * its label inside, has children — so rows expand from their handles and the arrow keys.
     * [focusScope] is what the developer is still using the tree from when focus is anywhere in it —
     * the Inspector, whose findings list selects rows too.
     */
    fun install(tree: JTree, focusScope: JComponent = tree) {
        this.tree = tree
        this.focusScope = focusScope
        tree.toggleClickCount = 0
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (tree.getPathForLocation(event.x, event.y) == null) return false
                jumpFromTree()
                return true
            }
        }.installOn(tree)
        tree.addMouseListener(
            object : MouseAdapter() {
                // Clicking the row that is already selected opens it again, as the Project view does.
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e) || !autoscroll) return
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    if (path == tree.selectionPath) resolved?.best?.let { open(it, requestFocus = false) }
                }
            },
        )
        tree.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_ENTER || e.modifiersEx != 0 || e.isConsumed) return
                    if (tree.selectionPath == null) return
                    e.consume()
                    jumpFromTree()
                }
            },
        )
        ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)?.shortcutSet?.let {
            jumpAction.registerCustomShortcutSet(it, tree)
        }
    }

    /**
     * The tree's selection changed to [node], from [observation]; [activity] is the Activity that
     * was resumed when it was captured, if it could be read.
     */
    fun select(node: UiNode?, observation: UiObservation?, activity: String?) {
        cancelPending()
        generation++
        resolved = null
        selection = node?.let {
            Selection(
                SourceQuery.of(it, observation?.tree?.framework ?: UiFramework.UNKNOWN),
                observation?.windowPackage,
                SourceRanking.screenValues(observation?.tree, it),
                SourceRelatives.of(observation?.tree, it),
                activity,
            )
        }
        start()
    }

    /**
     * The screen's Activity, read after the tree was shown so a capture never waits for it. The
     * selection takes it if it had none; a search it could change — one under way, or one that
     * found nothing — starts again with it.
     */
    fun activityRead(activity: String) {
        val current = selection ?: return
        if (current.activity != null) return
        selection = current.copy(activity = activity)
        val known = resolved
        val couldChange = pending != null || !current.searchable || (known != null && known.hits.isEmpty())
        if (!couldChange) return
        cancelPending()
        generation++
        resolved = null
        start()
    }

    /** Searches for the selection, opening the answer when it arrives if [autoscrollFollows] then. */
    private fun start() {
        val current = selection
        when {
            current == null -> line.clear()
            !current.searchable -> line.say(SourceStatus.NOTHING_TO_SEARCH)
            DumbService.isDumb(project) -> searchAfterIndexing()
            else -> search { result ->
                val focusOwner = IdeFocusManager.getInstance(project).focusOwner
                val opens = autoscroll && tree?.let { tree ->
                    autoscrollFollows(focusScope ?: tree, tree.isShowing, focusOwner)
                } == true
                if (opens) result.best?.let { open(it, requestFocus = false) }
                opens
            }
        }
    }

    private fun jumpFromTree() {
        val tree = tree ?: return
        val row = tree.selectionPath?.let { tree.getPathBounds(it) }
        jump { popup ->
            if (row == null) {
                popup.showInFocusCenter()
            } else {
                popup.show(RelativePoint(tree, Point(row.x, row.y + row.height)))
            }
        }
    }

    /** Opens the best match with focus, or lets the developer choose when several matched. */
    private fun jump(showPopup: (JBPopup) -> Unit) {
        val current = selection ?: return
        val known = resolved
        when {
            !current.searchable -> onNotice(SourceStatus.NOTHING_TO_SEARCH)
            DumbService.isDumb(project) -> onNotice(SourceStatus.INDEXING)
            known != null -> present(known, showPopup)
            else -> {
                // A search for this selection may be under way; its answer is wanted now, with focus.
                cancelPending()
                generation++
                search { result ->
                    present(result, showPopup)
                    true
                }
            }
        }
    }

    private fun present(result: SourceResult, showPopup: (JBPopup) -> Unit) {
        when (result.hits.size) {
            0 -> onNotice(SourceStatus.notFound(result))
            1 -> open(result.hits.single(), requestFocus = true)
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(result.hits)
                .setTitle("Found by ${SourceStatus.how(result)} — best first")
                .setRenderer(SimpleListCellRenderer.create("") { it.presentation })
                .setItemChosenCallback { open(it, requestFocus = true) }
                .createPopup()
                .let(showPopup)
        }
    }

    /**
     * [then] is handed the answer and says whether it opened it, for the Source line to say so. An
     * answer that failed is shown but not kept, so the next jump searches again.
     */
    private fun search(then: (SourceResult) -> Boolean) {
        val current = selection ?: return
        val request = generation
        line.searching()
        val locate = Callable {
            SourceSearch.guarded(current.query) {
                SourceLocator(project, current.screen)
                    .locate(current.query, current.windowPackage, current.relatives, current.activity)
            }
        }
        pending = ReadAction.nonBlocking(locate)
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                if (request != generation) return@finishOnUiThread
                pending = null
                resolved = result.takeIf { it.failure == null }
                line.show(result, opened = then(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .also { promise ->
                promise.onError { error ->
                    if (error is ControlFlowException || error is CancellationException) return@onError
                    val message = "Source search failed: ${error.message ?: error.javaClass.simpleName}"
                    ApplicationManager.getApplication().invokeLater({
                        if (request == generation) line.say(message)
                    }) { disposed }
                }
            }
    }

    /**
     * Says why there is no answer yet, and finds one — without opening it — once indexing ends: for
     * whatever is selected then. One wait at a time, however many rows are clicked while indexing,
     * and none once the Inspector is gone.
     */
    private fun searchAfterIndexing() {
        line.say(SourceStatus.INDEXING)
        if (awaitingSmart || disposed) return
        awaitingSmart = true
        DumbService.getInstance(project).runWhenSmart {
            awaitingSmart = false
            val waiting = selection?.searchable == true && resolved == null && pending == null
            if (!disposed && waiting) search { false }
        }
    }

    private fun open(hit: SourceHit, requestFocus: Boolean) {
        val file = VirtualFileManager.getInstance().findFileByUrl(hit.url)?.takeIf { it.isValid }
            ?: return onNotice("${hit.fileName} is no longer in the project.")
        OpenFileDescriptor(project, file, hit.offset).navigate(requestFocus)
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }

    override fun dispose() {
        disposed = true
        cancelPending()
    }

    private companion object {
        const val AUTOSCROLL_KEY = "spock.adb.uiInspector.autoscrollToSource"
    }
}

/**
 * Whether Autoscroll may still open an answer that arrived just now: only while the tree is
 * [showing] and focus is within [scope]. A search can take seconds; by then the developer may be
 * typing in an editor, or have closed the tool window, and switching the editor under them is worse
 * than not following. The answer still fills the Source line, and an explicit jump still opens it.
 */
internal fun autoscrollFollows(scope: Component, showing: Boolean, focusOwner: Component?): Boolean =
    showing && focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, scope)
