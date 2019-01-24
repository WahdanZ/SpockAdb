package spock.adb;

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope



fun PsiClass.openIn(project: Project) {
    OpenFileDescriptor(project, this.containingFile.virtualFile, 1, 0).navigateInEditor(project, false)
}