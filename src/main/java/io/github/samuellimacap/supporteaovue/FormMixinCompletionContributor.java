package io.github.samuellimacap.supporteaovue;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.javascript.psi.JSReferenceExpression;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

// Guia rapido do plugin (WebStorm 2024.2+):
//
// 1) // @form X.vue no topo de um mixin exclusivo de um form:
//      this.  -> membros do mixin, do form e dos mixins dele, com o arquivo ao lado.
//
// 2) this.getInfo.  /  this.$store.getters.info.  /  getters.info.
//      -> campos de getInfo usados no projeto (indice FileBasedIndex).
//
// 3) @origem antes de props/data/computed:
//      /** @origem ../Mixin.js cancelamento.configuracao */
//      /** @origem ../Factory.js criarParametros() */
//      /** @origem ../Factory.js criarCupom()[] */
//      /** @origem ./Obj.js default */
//      /** @origem criarLocal() */          (mesmo arquivo)
//      /** @origem ../Mixin.js */           (caminho omitido = nome do membro)
//      Completa this.x., x. (template), x[0]., x.find(c => c.), const c = x[0],
//      for..of e v-for (inclusive encadeado); Ctrl+clique vai ao campo de origem.
//      Ctrl+clique na propria anotacao vai ao arquivo, ao caminho, ao default ou a funcao/variavel.
public class FormMixinCompletionContributor extends CompletionContributor {

    private static final Pattern GET_INFO =
            Pattern.compile("(?:^|\\.)(?:getInfo|getters\\.info)$");

    public FormMixinCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().withParent(JSReferenceExpression.class),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters parameters,
                                                  @NotNull ProcessingContext context,
                                                  @NotNull CompletionResultSet result) {
                        PsiElement parent = parameters.getPosition().getParent();
                        if (!(parent instanceof JSReferenceExpression reference)) {
                            return;
                        }
                        PsiElement qualifier = reference.getQualifier();
                        if (qualifier == null) {
                            return;
                        }
                        String text = qualifier.getText().replace("?.", ".").replaceAll("\\s+", "");
                        if ("this".equals(text)) {
                            addFormMembers(parameters, result);
                        } else if (GET_INFO.matcher(text).find()) {
                            addGetInfoFields(parameters, result);
                        } else {
                            addOrigemFields(parameters, result, qualifier);
                        }
                    }
                });
    }

    private static void addFormMembers(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || !virtualFile.getName().endsWith(".js")) {
            return;
        }
        if (MixinResolver.formOf(file) == null) {
            return;
        }
        for (MixinResolver.Member member : MixinResolver.membersFor(file)) {
            result.addElement(lookupForMember(member));
        }
    }

    private static void addGetInfoFields(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        PsiFile host = InjectedLanguageManager.getInstance(parameters.getPosition().getProject())
                .getTopLevelFile(file);
        VirtualFile virtualFile = host.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        VirtualFile root = MixinResolver.projectRoot(virtualFile);
        if (root == null) {
            return;
        }
        if (DumbService.isDumb(parameters.getPosition().getProject())) {
            return;
        }
        List<String> fields = GetInfoFieldIndex.allFields(parameters.getPosition().getProject(), root);
        for (String field : fields) {
            result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(field)
                            .withTypeText("getInfo.do")
                            .withIcon(AllIcons.Nodes.Field),
                    100.0));
        }
    }

    private static void addOrigemFields(CompletionParameters parameters, CompletionResultSet result,
                                        PsiElement qualifier) {
        Project project = parameters.getPosition().getProject();
        PsiFile original = parameters.getOriginalFile();
        PsiFile host = InjectedLanguageManager.getInstance(project).getTopLevelFile(original);
        VirtualFile virtualFile = host.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        boolean template = !host.equals(original);
        int hostOffset = parameters.getPosition().getTextOffset();
        if (template) {
            hostOffset = InjectedLanguageManager.getInstance(project)
                    .injectedToHost(parameters.getPosition(), hostOffset);
        } else if (parameters.getEditor() != null) {
            hostOffset = parameters.getEditor().getCaretModel().getOffset();
        }
        template = template || OrigemResolver.isInsideTemplate(host, hostOffset);
        OrigemResolver.Context context = OrigemResolver.context(host, hostOffset, template);
        OrigemResolver.State state = OrigemResolver.stateOfQualifier(qualifier, context);
        if (state == null) {
            return;
        }
        for (OrigemResolver.Field field : OrigemResolver.fields(state)) {
            LookupElementBuilder builder = LookupElementBuilder.create(field.name())
                    .withTypeText(field.source() + ":" + field.line())
                    .withIcon(field.method() ? AllIcons.Nodes.Method : AllIcons.Nodes.Field);
            if (field.declaration() != null) {
                builder = builder.withPsiElement(field.declaration());
            }
            if (field.method()) {
                builder = builder.withTailText("()", true)
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS);
            }
            result.addElement(PrioritizedLookupElement.withPriority(builder, 100.0));
        }
    }

    private static LookupElement lookupForMember(MixinResolver.Member member) {
        LookupElementBuilder builder = LookupElementBuilder.create(member.name())
                .withTypeText(member.source())
                .withIcon(switch (member.kind()) {
                    case METHOD -> AllIcons.Nodes.Method;
                    case COMPUTED -> AllIcons.Nodes.Property;
                    default -> AllIcons.Nodes.Field;
                });
        if (member.kind() == MixinResolver.Kind.METHOD) {
            builder = builder.withTailText("()", true)
                    .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS);
        }
        return PrioritizedLookupElement.withPriority(builder, 100.0);
    }
}
