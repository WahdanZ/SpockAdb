package spock.adb

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

fun PsiClass.openIn(project: Project) {
    OpenFileDescriptor(project, this.containingFile.virtualFile, 1, 0).navigateInEditor(project, false)
}

fun String.psiClassByNameFromCache(project: Project): PsiClass? {
    return PsiShortNamesCache.getInstance(project).getClassesByName(
        this, GlobalSearchScope.allScope(project)
    ).getOrNull(0)
}

fun String.psiClassByNameFromProjct(project: Project): PsiClass? {
    return JavaPsiFacade.getInstance(project).findClass(this, GlobalSearchScope.allScope(project))
}
