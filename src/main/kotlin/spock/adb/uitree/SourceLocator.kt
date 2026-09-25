package spock.adb.uitree

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import spock.adb.declaration

/**
 * Finds where in the open project a captured element most likely comes from.
 *
 * A search, not a mapping: see [SourceQuery]. It walks [SourceQuery.steps] in order and stops at
 * the first that finds anything, so a test tag that is found is never second-guessed by the text.
 * An element that finds nothing borrows from its relatives ([SourceRelatives]), and a screen where
 * nothing is found at all falls back to its Activity.
 *
 * A value the app builds with a Kotlin template — `testTag("form_${form}_button")`, `Text("Feed row
 * $i")` — is matched through [SourceTemplate.pattern]. Text is found by its words, the fixed ones
 * among them; a rendered tag is usually one word the source never contains, so every `testTag(...)`
 * call's argument is read instead.
 *
 * Only the project's own content is searched, never libraries, and only through the word index —
 * [PsiSearchHelper.processElementsWithWord] finds every file holding a word without reading the
 * rest, and each hit is then checked against the whole value. Literals are recognised without the
 * Kotlin plugin's PSI, which this plugin does not depend on: a string literal is a
 * [PsiLanguageInjectionHost] whose text opens with a quote, read through [PsiLiteralValue] where
 * the language offers it (Java) and from its source text otherwise (Kotlin).
 *
 * Needs a read action in smart mode; [SourceNavigator] runs it in a cancellable one off the EDT.
 */
internal class SourceLocator(
    private val project: Project,
    /** The rest of the captured screen: [SourceRanking.screenValues]. */
    private val screen: Set<String> = emptySet(),
) {

    private val scope = GlobalSearchScope.projectScope(project)
    private val words = PsiSearchHelper.getInstance(project)
    private val screenAffinity = HashMap<String, Int>()

    fun locate(query: SourceQuery, windowPackage: String?): SourceResult {
        query.steps.forEach { step ->
            val (tier, hits) = find(step)
            if (hits.isNotEmpty()) return SourceResult(query, tier, SourceRanking.rank(hits, windowPackage))
        }
        return SourceResult(query, null, emptyList())
    }

    /**
     * [query]'s own search; failing that, each of [relatives]' in turn; failing that, the
     * declaration of [activity] — the screen's Activity, when it was read and is in this project.
     */
    fun locate(
        query: SourceQuery,
        windowPackage: String?,
        relatives: List<SourceRelative>,
        activity: String?,
    ): SourceResult {
        val own = locate(query, windowPackage)
        if (own.hits.isNotEmpty()) return own
        relatives.forEachIndexed { tried, relative ->
            ProgressManager.checkCanceled()
            val found = locate(relative.query, windowPackage)
            if (found.hits.isNotEmpty()) return found.copy(query = query, via = relative, relativesTried = tried)
        }
        val tried = relatives.size
        val screen = activity?.let(::classDeclaration)
            ?: return SourceResult(query, null, emptyList(), relativesTried = tried)
        return SourceResult(query, SourceTier.ACTIVITY, listOf(screen), activity = activity, relativesTried = tried)
    }

    private fun find(step: SourceStep): Pair<SourceTier, List<SourceHit>> = when (step.tier) {
        SourceTier.TEST_TAG -> step.tier to literals(step.value, tagCall = true) + tagCallSites(step.value)
        SourceTier.VIEW_ID -> step.tier to viewIds(step.value)
        SourceTier.TEXT, SourceTier.CONTENT_DESCRIPTION -> {
            val literals = literals(step.value, tagCall = false)
            if (literals.isNotEmpty()) step.tier to literals else SourceTier.STRING_RESOURCE to strings(step.value)
        }
        SourceTier.CLASS -> step.tier to listOfNotNull(classDeclaration(step.value))
        SourceTier.STRING_RESOURCE, SourceTier.ACTIVITY -> step.tier to emptyList()
    }

    // ---------------------------------------------------------------- tiers

    /**
     * String literals in code equal to [value]; with [tagCall], ones passed to `testTag(...)` rank
     * first. For text, also templates that render to it; a tag's templates are [tagCallSites]'.
     */
    private fun literals(value: String, tagCall: Boolean): List<SourceHit> {
        val literals = SourceTemplate.searchWords(value).flatMapTo(LinkedHashSet()) { word ->
            leavesWith(word).mapNotNull { literalAround(it) }
        }
        return literals.mapNotNull { literal ->
            when {
                literalValue(literal) == value ->
                    hit(literal, exactContext = tagCall && SourceMatching.isTestTagArgument(textBefore(literal)))
                !tagCall && rendersTo(literal, value) ->
                    hit(literal, exactContext = false, pattern = SourceTemplate.body(literal.text))
                else -> null
            }
        }
    }

    /** `testTag(...)` calls whose argument is [tag], or a template that renders to it. */
    private fun tagCallSites(tag: String): List<SourceHit> = tagArguments.mapNotNull { literal ->
        when {
            literalValue(literal) == tag -> hit(literal, exactContext = true)
            rendersTo(literal, tag) -> hit(literal, exactContext = true, pattern = SourceTemplate.body(literal.text))
            else -> null
        }
    }

    /**
     * The string literal passed to each `testTag(...)` call in the project, read once per search
     * and shared by the relatives'. A call passing a variable — `testTag(tag)` — is not followed:
     * where that value is written as a literal, [literals] finds it.
     */
    private val tagArguments: List<PsiElement> by lazy {
        leavesWith(TEST_TAG, MAX_TAG_CALLS).mapNotNull { leaf ->
            val file = leaf.containingFile
            if (leaf.text != TEST_TAG || file is XmlFile) return@mapNotNull null
            val text = file.viewProvider.contents
            val end = leaf.textRange.endOffset
            val after = text.subSequence(end, (end + CONTEXT_CHARS).coerceAtMost(text.length))
            SourceTemplate.tagArgumentOffset(after)?.let { file.findElementAt(end + it) }?.let(::literalAround)
        }
    }

    /** `android:id="@+id/name"` in layout XML, the element's own declaration; then `R.id.name` in code. */
    private fun viewIds(name: String): List<SourceHit> = leavesWith(name).mapNotNull { leaf ->
        if (leaf.containingFile is XmlFile) {
            val value = PsiTreeUtil.getParentOfType(leaf, XmlAttributeValue::class.java, false)
                ?: return@mapNotNull null
            val declares = (value.parent as? XmlAttribute)?.name == ANDROID_ID &&
                SourceMatching.declaredIdName(value.value) == name
            if (declares) hit(value, exactContext = true) else null
        } else {
            resourceReference(leaf, name, "id")
        }
    }

    /**
     * A `strings.xml` entry whose value is [value], then where the app uses it — `R.string.name` in
     * code, `@string/name` in XML — falling back to the entry itself when nothing uses it.
     */
    private fun strings(value: String): List<SourceHit> {
        val candidates = SourceTemplate.searchWords(value).flatMapTo(LinkedHashSet()) { word ->
            leavesWith(word).mapNotNull { stringEntry(it) }
        }
        // Each entry with the format it matched through, or null when its value is [value] itself.
        val entries = candidates.mapNotNull { entry ->
            val shown = SourceMatching.androidStringValue(entry.value.text)
            when {
                shown == value -> entry to null
                SourceTemplate.formatPattern(shown)?.matches(value) == true -> entry to shown
                else -> null
            }
        }
        if (entries.isEmpty()) return emptyList()
        val usages = entries.distinctBy { (entry, _) -> entry.getAttributeValue("name") }.flatMap { (entry, format) ->
            entry.getAttributeValue("name")?.let(::stringUsages).orEmpty().map { it.copy(pattern = format) }
        }
        return usages.ifEmpty { entries.mapNotNull { (entry, format) -> hit(entry, exactContext = false, format) } }
    }

    private fun stringUsages(name: String): List<SourceHit> = leavesWith(name).mapNotNull { leaf ->
        if (leaf.containingFile is XmlFile) {
            val value = PsiTreeUtil.getParentOfType(leaf, XmlAttributeValue::class.java, false)
            if (value != null && value.value.trim() == "@string/$name") hit(value, exactContext = false) else null
        } else {
            resourceReference(leaf, name, "string")
        }
    }

    private fun classDeclaration(name: String): SourceHit? =
        JavaPsiFacade.getInstance(project).findClass(name, scope)?.let { hit(it.declaration(), exactContext = true) }

    // ---------------------------------------------------------------- PSI

    /**
     * The leaf of each place in the project's files where [word] occurs as a whole word, once each.
     *
     * The processor is handed the leaf first and then each of its parents, so only leaves are kept.
     * It can be called from several threads at once — files are searched concurrently — hence the
     * lock; the order it collects in does not matter, since [SourceRanking] sorts the result.
     * Stops after [limit]: a word like "OK" is everywhere, and a popup of hundreds helps nobody.
     */
    private fun leavesWith(word: String, limit: Int = MAX_OCCURRENCES): Collection<PsiElement> {
        val leaves = LinkedHashSet<PsiElement>()
        words.processElementsWithWord(
            { element, _ ->
                val leaf = element.firstChild == null
                synchronized(leaves) {
                    if (leaf) leaves += element
                    leaves.size < limit
                }
            },
            scope,
            word,
            UsageSearchContext.ANY,
            true,
        )
        return leaves
    }

    /** The string literal [leaf] is part of, in code; null in XML, comments and identifiers. */
    private fun literalAround(leaf: PsiElement): PsiElement? {
        if (leaf.containingFile is XmlFile) return null
        var element: PsiElement? = leaf
        repeat(LITERAL_DEPTH) {
            val current = element ?: return null
            if (current is PsiFile) return null
            if (current is PsiLanguageInjectionHost && current.text.startsWith('"')) return current
            element = current.parent
        }
        return null
    }

    private fun literalValue(literal: PsiElement): String? =
        (literal as? PsiLiteralValue)?.value as? String
            ?: SourceMatching.literalValue(literal.text, kotlin = literal.language.id == KOTLIN)

    /** A Kotlin string template that renders to [value]. */
    private fun rendersTo(literal: PsiElement, value: String): Boolean =
        literal.language.id == KOTLIN && SourceTemplate.pattern(literal.text)?.matches(value) == true

    /** [leaf] when it is [name] in `R.<type>.name`, outside comments. */
    private fun resourceReference(leaf: PsiElement, name: String, type: String): SourceHit? {
        if (leaf.text != name || PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false) != null) return null
        return if (SourceMatching.isResourceReference(textBefore(leaf), type)) hit(leaf, exactContext = false) else null
    }

    /** The `<string>` in a `values*` resource file that [leaf] is part of. */
    private fun stringEntry(leaf: PsiElement): XmlTag? {
        val file = leaf.containingFile as? XmlFile ?: return null
        if (file.containingDirectory?.name?.startsWith("values") != true) return null
        return generateSequence(PsiTreeUtil.getParentOfType(leaf, XmlTag::class.java, false)) { it.parentTag }
            .firstOrNull { it.name == "string" }
    }

    private fun textBefore(element: PsiElement): CharSequence {
        val text = element.containingFile.viewProvider.contents
        val start = element.textRange.startOffset.coerceIn(0, text.length)
        return text.subSequence((start - CONTEXT_CHARS).coerceAtLeast(0), start)
    }

    /** [pattern] is the template [element] matched through: see [SourceHit.pattern]. */
    private fun hit(element: PsiElement, exactContext: Boolean, pattern: String? = null): SourceHit? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = element.textOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val lineText = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        return SourceHit(
            url = virtualFile.url,
            fileName = virtualFile.name,
            offset = offset,
            line = line + 1,
            snippet = lineText.trim(),
            exactContext = exactContext,
            module = ModuleUtilCore.findModuleForFile(virtualFile, project)?.name,
            packageName = (file as? PsiClassOwner)?.packageName,
            screenAffinity = screenAffinity.getOrPut(virtualFile.url) {
                SourceRanking.screenAffinity(file.viewProvider.contents, screen)
            },
            pattern = pattern,
        )
    }

    private companion object {
        const val ANDROID_ID = "android:id"
        const val KOTLIN = "kotlin"
        const val TEST_TAG = "testTag"

        /** Leaf, template entry, template: how deep a Kotlin string's text sits. Java's is the leaf. */
        const val LITERAL_DEPTH = 4

        /** Enough source before a match to see `testTag(tag = ` or a package-qualified `R.id.`. */
        const val CONTEXT_CHARS = 120
        const val MAX_OCCURRENCES = 500

        /** Enough for every `testTag(...)` call in a large app; each is one short read, not a search. */
        const val MAX_TAG_CALLS = 3000
    }
}
