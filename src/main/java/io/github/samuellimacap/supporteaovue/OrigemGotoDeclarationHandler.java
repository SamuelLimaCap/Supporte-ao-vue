package io.github.samuellimacap.supporteaovue;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.javascript.psi.JSReferenceExpression;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

public class OrigemGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset,
                                                             Editor editor) {
        if (sourceElement == null || editor == null) {
            return null;
        }
        Project project = sourceElement.getProject();
        PsiFile original = sourceElement.getContainingFile();
        PsiFile host = InjectedLanguageManager.getInstance(project).getTopLevelFile(original);
        VirtualFile virtualFile = host.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        int hostOffset = editor.getCaretModel().getOffset();
        PsiElement annotationTarget = OrigemResolver.definitionAt(host, hostOffset);
        if (annotationTarget != null) {
            return new PsiElement[]{annotationTarget};
        }
        JSReferenceExpression reference = PsiTreeUtil.getParentOfType(sourceElement, JSReferenceExpression.class);
        if (reference == null || reference.getReferenceName() == null) {
            return null;
        }
        PsiElement qualifier = reference.getQualifier();
        if (qualifier == null) {
            return null;
        }
        boolean template = !host.equals(original) || OrigemResolver.isInsideTemplate(host, hostOffset);
        OrigemResolver.Context context = OrigemResolver.context(host, hostOffset, template);
        OrigemResolver.State base = OrigemResolver.stateOfQualifier(qualifier, context);
        PsiElement declaration = OrigemResolver.fieldDeclaration(base, reference.getReferenceName());
        return declaration == null ? null : new PsiElement[]{declaration};
    }
}
