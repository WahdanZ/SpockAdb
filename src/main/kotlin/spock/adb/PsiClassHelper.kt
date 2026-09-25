package spock.adb

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Opens the editor at the class's declaration.
 *
 * This opened the class's file at its first line, so any class that shares a file with others —
 * the sample's fragments all live in NavigationActivity.kt — landed on the top of that file and
 * looked like the wrong class. [PsiClass.getNavigationElement] is the Kotlin source rather than
 * its light class, so the offset is the declaration's in the file the user wrote.
 */
fun PsiClass.openIn(project: Project) {
    val descriptor = ReadAction.compute<OpenFileDescriptor?, RuntimeException> {
        val target = declaration()
        target.containingFile?.virtualFile?.let { OpenFileDescriptor(project, it, target.textOffset) }
    } ?: return
    descriptor.navigateInEditor(project, true)
}

/**
 * Where [openIn] lands: the declaration in the file the user wrote, or the class itself when that
 * has no file of its own. Needs a read action. The UI Inspector's source search lands here too.
 */
fun PsiClass.declaration(): PsiElement =
    navigationElement.takeIf { it.containingFile?.virtualFile != null } ?: this

fun String.psiClassByNameFromCache(project: Project): PsiClass? {
    return PsiShortNamesCache.getInstance(project).getClassesByName(
        this, GlobalSearchScope.allScope(project)
    ).getOrNull(0)
}

fun String.psiClassByNameFromProjct(project: Project): PsiClass? {
    return JavaPsiFacade.getInstance(project).findClass(this, GlobalSearchScope.allScope(project))
}
