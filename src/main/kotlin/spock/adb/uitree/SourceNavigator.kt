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
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * Takes the developer from a selected element to the source that most likely produced it.
 *
 * Every selection starts a search ([SourceLocator]) in a non-blocking read action off the EDT, and
 * the one before it is cancelled, so holding an arrow key down does not queue a search per row. The
 * answer fills the details pane's [line]; with **Autoscroll to Source** on it is also opened, without
 * taking focus from the tree, so the arrow keys keep working. Double-click, Enter, the Edit Source
 * shortcut, the context menu and the details link always open it with focus, and offer a list when
 * several places matched.
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
    private var selection: Selection? = null
    private var resolved: SourceResult? = null
    private var pending: CancellablePromise<SourceResult>? = null

    /** Bumped by every new request, so an answer that arrives late is dropped. */
    private var generation = 0
    private var disposed = false

    private var autoscroll: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(AUTOSCROLL_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(AUTOSCROLL_KEY, value, true)

    private data class Selection(val query: SourceQuery, val windowPackage: String?, val screen: Set<String>)

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
            e.presentation.isEnabled = selection?.query?.isEmpty == false
        }
        override fun actionPerformed(e: AnActionEvent) = jumpFromTree()
    }

    /**
     * Double-click and Enter jump; so does the IDE's Edit Source shortcut, which the context menu
     * shows. Double-click no longer expands a row — nearly every row that matters, a button with
     * its label inside, has children — so rows expand from their handles and the arrow keys.
     */
    fun install(tree: JTree) {
        this.tree = tree
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

    /** The tree's selection changed to [node], from [observation]. */
    fun select(node: UiNode?, observation: UiObservation?) {
        cancelPending()
        generation++
        resolved = null
        selection = node?.let {
            Selection(
                SourceQuery.of(it, observation?.tree?.framework ?: UiFramework.UNKNOWN),
                observation?.windowPackage,
                SourceRanking.screenValues(observation?.tree, it),
            )
        }
        val query = selection?.query
        when {
            query == null -> line.clear()
            query.isEmpty -> line.say(SourceStatus.NOTHING_TO_SEARCH)
            DumbService.isDumb(project) -> searchAfterIndexing()
            else -> search { result -> if (autoscroll) result.best?.let { open(it, requestFocus = false) } }
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
            current.query.isEmpty -> onNotice(SourceStatus.NOTHING_TO_SEARCH)
            DumbService.isDumb(project) -> onNotice(SourceStatus.INDEXING)
            known != null -> present(known, showPopup)
            else -> {
                // A search for this selection may be under way; its answer is wanted now, with focus.
                cancelPending()
                generation++
                search { present(it, showPopup) }
            }
        }
    }

    private fun present(result: SourceResult, showPopup: (JBPopup) -> Unit) {
        when (result.hits.size) {
            0 -> onNotice(SourceStatus.notFound(result.query))
            1 -> open(result.hits.single(), requestFocus = true)
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(result.hits)
                .setTitle("Found by ${result.tier?.label ?: "search"} — best first")
                .setRenderer(SimpleListCellRenderer.create("") { it.presentation })
                .setItemChosenCallback { open(it, requestFocus = true) }
                .createPopup()
                .let(showPopup)
        }
    }

    private fun search(then: (SourceResult) -> Unit) {
        val current = selection ?: return
        val request = generation
        line.searching()
        val locate = Callable { SourceLocator(project, current.screen).locate(current.query, current.windowPackage) }
        pending = ReadAction.nonBlocking(locate)
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                if (request != generation) return@finishOnUiThread
                pending = null
                resolved = result
                line.show(result)
                then(result)
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

    /** Says why there is no answer yet, and finds one — without opening it — once indexing ends. */
    private fun searchAfterIndexing() {
        line.say(SourceStatus.INDEXING)
        val request = generation
        DumbService.getInstance(project).runWhenSmart {
            if (!disposed && request == generation) search { }
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
