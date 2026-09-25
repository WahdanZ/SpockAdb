package spock.adb.uitree

import com.intellij.openapi.module.ModuleUtilCore
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

    private fun find(step: SourceStep): Pair<SourceTier, List<SourceHit>> = when (step.tier) {
        SourceTier.TEST_TAG -> step.tier to literals(step.value, tagCall = true)
        SourceTier.VIEW_ID -> step.tier to viewIds(step.value)
        SourceTier.TEXT, SourceTier.CONTENT_DESCRIPTION -> {
            val literals = literals(step.value, tagCall = false)
            if (literals.isNotEmpty()) step.tier to literals else SourceTier.STRING_RESOURCE to strings(step.value)
        }
        SourceTier.CLASS -> step.tier to listOfNotNull(classDeclaration(step.value))
        SourceTier.STRING_RESOURCE -> step.tier to emptyList()
    }

    // ---------------------------------------------------------------- tiers

    /** String literals in code equal to [value]; with [tagCall], ones passed to `testTag(...)` rank first. */
    private fun literals(value: String, tagCall: Boolean): List<SourceHit> {
        val word = SourceMatching.searchWord(value) ?: return emptyList()
        val literals = leavesWith(word).mapNotNullTo(LinkedHashSet()) { literalAround(it) }
        return literals
            .filter { literalValue(it) == value }
            .mapNotNull { hit(it, exactContext = tagCall && SourceMatching.isTestTagArgument(textBefore(it))) }
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
        val word = SourceMatching.searchWord(value) ?: return emptyList()
        val entries = leavesWith(word).mapNotNullTo(LinkedHashSet()) { stringEntry(it) }
            .filter { SourceMatching.androidStringValue(it.value.text) == value }
        if (entries.isEmpty()) return emptyList()
        val usages = entries.mapNotNull { it.getAttributeValue("name") }.distinct().flatMap(::stringUsages)
        return usages.ifEmpty { entries.mapNotNull { hit(it, exactContext = false) } }
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
     * Stops after [MAX_OCCURRENCES]: a word like "OK" is everywhere, and a popup of hundreds helps nobody.
     */
    private fun leavesWith(word: String): Collection<PsiElement> {
        val leaves = LinkedHashSet<PsiElement>()
        words.processElementsWithWord(
            { element, _ ->
                val leaf = element.firstChild == null
                synchronized(leaves) {
                    if (leaf) leaves += element
                    leaves.size < MAX_OCCURRENCES
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

    private fun hit(element: PsiElement, exactContext: Boolean): SourceHit? {
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
        )
    }

    private companion object {
        const val ANDROID_ID = "android:id"
        const val KOTLIN = "kotlin"

        /** Leaf, template entry, template: how deep a Kotlin string's text sits. Java's is the leaf. */
        const val LITERAL_DEPTH = 4

        /** Enough source before a match to see `testTag(tag = ` or a package-qualified `R.id.`. */
        const val CONTEXT_CHARS = 120
        const val MAX_OCCURRENCES = 500
    }
}
