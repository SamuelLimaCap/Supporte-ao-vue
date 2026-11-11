package io.github.samuellimacap.supporteaovue;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;

public class OrigemCompletionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("jsconfig.json", "{}");
        myFixture.addFileToProject("src/views/x/OrigemMixin.js", """
                export default {
                  data: () => ({
                    cancelamento: {
                      configuracao: {
                        nivelLiberacao: 0,
                        exigeLiberacao: false,
                        descricaoNivel: '',
                      },
                      solicitacoes: [],
                    },
                    configuracaoSimples: {
                      simplesA: 1,
                      simplesB: 2,
                    },
                  }),
                };
                """);
        myFixture.addFileToProject("src/views/x/OrigemFactory.js", """
                export function criarParametros() {
                  return {
                    empresa: 1,
                    emitente: 'X',
                    ajustarHorarioServidor: true,
                  };
                }

                export function criarCupom() {
                  return {
                    numeroNota: 1,
                    valor: 10,
                    itens: [
                      {codigoItem: 1, nomeItem: 'x'},
                    ],
                  };
                }

                export function criarItens() {
                  return [
                    {codigoItem: 1, nomeItem: 'x'},
                  ];
                }

                export function criarVazio() {
                  return [];
                }

                export function criarBase() {
                  return {baseA: 1, baseB: 2};
                }

                export function criarComBase() {
                  return {...criarBase(), extra: 3};
                }
                """);
        myFixture.addFileToProject("src/views/x/Obj.js", """
                export default {
                  opcaoA: 1,
                  opcaoB: 'x',
                };
                """);
        myFixture.addFileToProject("src/views/x/FabDef.js", """
                export default function () {
                  return {
                    defA: 1,
                    defB: 2,
                  };
                }
                """);
        myFixture.addFileToProject("src/views/x/FabNom.js", """
                export default function criar() {
                  return {
                    nomA: 1,
                    nomB: 2,
                  };
                }
                """);
        myFixture.addFileToProject("src/views/x/FabNested.js", """
                export default {
                  nivel1: {
                    nivel2: {
                      nestedA: 1,
                      nestedB: 2,
                    },
                  },
                };
                """);
        myFixture.addFileToProject("src/views/x/OrigemForm.vue", """
                <template>
                  <div>{{ configuracao. }}</div>
                  <div v-for="(cupom, i) in cupons">
                    <span>{{ cupom. }}</span>
                    <span>{{ i. }}</span>
                    <div v-for="it in cupom.itens">{{ it. }}</div>
                    <span>{{ cupom.numeroNota }}</span>
                  </div>
                </template>

                <script>
                import OrigemMixin from './OrigemMixin.js';
                import {criarParametros} from './OrigemFactory.js';

                export function criarLocal() {
                  return {
                    localA: 1,
                    localB: 2,
                  };
                }

                export default {
                  mixins: [OrigemMixin],
                  data: () => ({
                    /** @origem ./OrigemMixin.js cancelamento.configuracao */
                    configuracaoData: {},
                  }),
                  computed: {
                    /** @origem ./OrigemMixin.js cancelamento.configuracao */
                    configuracaoComputed() {
                      return {};
                    },
                  },
                  props: {
                    /** @origem ./OrigemMixin.js cancelamento.configuracao */
                    configuracao: {type: Object, default: () => ({})},
                    /** @origem ./OrigemMixin.js cancelamento */
                    cancelamento: {type: Object, default: () => ({})},
                    /** @origem ./OrigemMixin.js */
                    configuracaoSimples: {type: Object, default: () => ({})},
                    /** @origem ./OrigemFactory.js criarParametros() */
                    parametros: {type: Object, default: () => ({})},
                    /** @origem criarParametros() */
                    parametrosImportado: {type: Object, default: () => ({})},
                    /** @origem ./OrigemFactory.js criarComBase() */
                    comBase: {type: Object, default: () => ({})},
                    /** @origem ./OrigemFactory.js criarCupom()[] */
                    cupons: {type: Array, default: () => []},
                    /** @origem ./OrigemFactory.js criarItens() */
                    itens: {type: Array, default: () => []},
                    /** @origem ./OrigemFactory.js criarVazio()[] */
                    vazios: {type: Array, default: () => []},
                    /** @origem ./Obj.js default */
                    opcoes: {type: Object, default: () => ({})},
                    /** @origem ./Obj.js */
                    opcoesSemPath: {type: Object, default: () => ({})},
                    /** @origem ./FabDef.js default */
                    fabDef: {type: Object, default: () => ({})},
                    /** @origem ./FabNom.js default */
                    fabNom: {type: Object, default: () => ({})},
                    /** @origem ./FabNested.js default.nivel1.nivel2 */
                    fabNested: {type: Object, default: () => ({})},
                    /** @origem criarLocal() */
                    local: {type: Object, default: () => ({})},
                  },
                };
                </script>
                """);
    }

    public void testOrigemNestedPathInMixin() {
        List<String> fields = completeIn("src/views/x/TesteOrigem.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracao.
                    },
                  },
                };
                """, "this.configuracao.", "OrigemMixin.js");
        assertContainsElements(fields, "nivelLiberacao", "exigeLiberacao", "descricaoNivel");
    }

    public void testOrigemFactoryFunction() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFactory.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.parametros.
                    },
                  },
                };
                """, "this.parametros.", "OrigemFactory.js");
        assertContainsElements(fields, "empresa", "emitente", "ajustarHorarioServidor");
    }

    public void testOrigemNestedPropertyChain() {
        List<String> fields = completeIn("src/views/x/TesteOrigemNested.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cancelamento.configuracao.
                    },
                  },
                };
                """, "this.cancelamento.configuracao.", "OrigemMixin.js");
        assertContainsElements(fields, "nivelLiberacao", "exigeLiberacao", "descricaoNivel");
    }

    public void testOrigemOmittedPath() {
        List<String> fields = completeIn("src/views/x/TesteOrigemOmitted.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracaoSimples.
                    },
                  },
                };
                """, "this.configuracaoSimples.", "OrigemMixin.js");
        assertContainsElements(fields, "simplesA", "simplesB");
    }

    public void testOrigemSameFile() {
        List<String> fields = completeIn("src/views/x/TesteOrigemSame.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.local.
                    },
                  },
                };
                """, "this.local.", "OrigemForm.vue");
        assertContainsElements(fields, "localA", "localB");
    }

    public void testOrigemImportedFunctionAndSpread() {
        assertContainsElements(completeIn("src/views/x/TesteOrigemImport.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.parametrosImportado.
                    },
                  },
                };
                """, "this.parametrosImportado.", "OrigemFactory.js"), "empresa", "emitente");
        assertContainsElements(completeIn("src/views/x/TesteOrigemSpread.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.comBase.
                    },
                  },
                };
                """, "this.comBase.", "OrigemFactory.js"), "baseA", "baseB", "extra");
    }

    public void testOrigemDefaultPlainObject() {
        List<String> fields = completeIn("src/views/x/TesteOrigemDefault.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.opcoes.
                    },
                  },
                };
                """, "this.opcoes.", "Obj.js");
        assertContainsElements(fields, "opcaoA", "opcaoB");
    }

    public void testOrigemDefaultFallback() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFallback.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.opcoesSemPath.
                    },
                  },
                };
                """, "this.opcoesSemPath.", "Obj.js");
        assertContainsElements(fields, "opcaoA", "opcaoB");
    }

    public void testOrigemDefaultFunction() {
        assertContainsElements(completeIn("src/views/x/TesteOrigemFabDef.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.fabDef.
                    },
                  },
                };
                """, "this.fabDef.", "FabDef.js"), "defA", "defB");
        assertContainsElements(completeIn("src/views/x/TesteOrigemFabNom.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.fabNom.
                    },
                  },
                };
                """, "this.fabNom.", "FabNom.js"), "nomA", "nomB");
    }

    public void testOrigemDefaultNestedPath() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFabNested.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.fabNested.
                    },
                  },
                };
                """, "this.fabNested.", "FabNested.js");
        assertContainsElements(fields, "nestedA", "nestedB");
    }

    public void testOrigemArrayItem() {
        List<String> fields = completeIn("src/views/x/TesteOrigemArrayItem.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cupons[0].
                    },
                  },
                };
                """, "this.cupons[0].", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayFindCallback() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFindCallback.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cupons.find(c => c.
                    },
                  },
                };
                """, "this.cupons.find(c => c.", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayFindResult() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFindResult.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cupons.find(c => c.numeroNota).
                    },
                  },
                };
                """, "this.cupons.find(c => c.numeroNota).", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayFindVariable() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFindVar.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      const c = this.cupons.find(cupom => cupom.id);
                      c.
                    },
                  },
                };
                """, "c.", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayFilterIndex() {
        List<String> fields = completeIn("src/views/x/TesteOrigemFilterIndex.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cupons.filter(c => c.valor > 0)[0].
                    },
                  },
                };
                """, "this.cupons.filter(c => c.valor > 0)[0].", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayVariable() {
        List<String> fields = completeIn("src/views/x/TesteOrigemArrayVar.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      const c = this.cupons[0];
                      c.
                    },
                  },
                };
                """, "c.", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemMemberVariable() {
        List<String> fields = completeIn("src/views/x/TesteOrigemMemberVar.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      const cfg = this.configuracao;
                      cfg.
                    },
                  },
                };
                """, "cfg.", "OrigemMixin.js");
        assertContainsElements(fields, "nivelLiberacao", "exigeLiberacao", "descricaoNivel");
    }

    public void testOrigemForOf() {
        List<String> fields = completeIn("src/views/x/TesteOrigemForOf.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      for (const c of this.cupons) {
                        c.
                      }
                    },
                  },
                };
                """, "c.", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemArrayLiteralWithoutBrackets() {
        List<String> fields = completeIn("src/views/x/TesteOrigemArrayLiteral.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.itens[0].
                    },
                  },
                };
                """, "this.itens[0].", "OrigemFactory.js");
        assertContainsElements(fields, "codigoItem", "nomeItem");
    }

    public void testOrigemEmptyArray() {
        List<String> fields = completeIn("src/views/x/TesteOrigemEmptyArray.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.vazios[0].
                    },
                  },
                };
                """, "this.vazios[0].", "OrigemFactory.js");
        assertTrue(fields.isEmpty());
    }

    public void testOrigemArrayMethodsOnly() {
        List<String> fields = completeIn("src/views/x/TesteOrigemArrayMethods.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.cupons.
                    },
                  },
                };
                """, "this.cupons.", "OrigemFactory.js");
        assertTrue(fields.isEmpty());
    }

    public void testOrigemParameterShadowsMember() {
        List<String> fields = completeIn("src/views/x/TesteOrigemShadow.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste(configuracao) {
                      configuracao.
                    },
                  },
                };
                """, "configuracao.", "OrigemMixin.js");
        assertTrue(fields.isEmpty());
    }

    public void testOrigemDataAndComputed() {
        assertContainsElements(completeIn("src/views/x/TesteOrigemData.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracaoData.
                    },
                  },
                };
                """, "this.configuracaoData.", "OrigemMixin.js"), "nivelLiberacao", "exigeLiberacao");
        assertContainsElements(completeIn("src/views/x/TesteOrigemComputed.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracaoComputed.
                    },
                  },
                };
                """, "this.configuracaoComputed.", "OrigemMixin.js"), "nivelLiberacao", "exigeLiberacao");
    }

    public void testOrigemInTemplate() {
        List<String> fields = completeInTemplate("src/views/x/OrigemForm.vue", "configuracao.", "OrigemMixin.js");
        assertContainsElements(fields, "nivelLiberacao", "exigeLiberacao", "descricaoNivel");
    }

    public void testOrigemVFor() {
        List<String> fields = completeInTemplate("src/views/x/OrigemForm.vue", "cupom.", "OrigemFactory.js");
        assertContainsElements(fields, "numeroNota", "valor", "itens");
    }

    public void testOrigemVForIndex() {
        List<String> fields = completeInTemplate("src/views/x/OrigemForm.vue", "i.", "OrigemFactory.js");
        assertTrue(fields.isEmpty());
    }

    public void testOrigemVForChained() {
        List<String> fields = completeInTemplate("src/views/x/OrigemForm.vue", "it.", "OrigemFactory.js");
        assertContainsElements(fields, "codigoItem", "nomeItem");
    }

    public void testGotoFieldInMixin() {
        PsiFile file = myFixture.addFileToProject("src/views/x/GotoOrigem.js", """
                // @form OrigemForm.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracao.exigeLiberacao;
                    },
                  },
                };
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Editor editor = myFixture.getEditor();
        int offset = editor.getDocument().getText().indexOf("exigeLiberacao");
        editor.getCaretModel().moveToOffset(offset);
        PsiElement leaf = file.findElementAt(offset);
        PsiElement[] targets = new OrigemGotoDeclarationHandler()
                .getGotoDeclarationTargets(leaf, offset, editor);
        assertNotNull(targets);
        assertTrue(targets.length > 0);
        assertEquals("OrigemMixin.js", targets[0].getContainingFile().getName());
    }

    public void testGotoFieldInTemplateVFor() {
        myFixture.configureByFile("src/views/x/OrigemForm.vue");
        Editor editor = myFixture.getEditor();
        int offset = editor.getDocument().getText().indexOf("numeroNota");
        editor.getCaretModel().moveToOffset(offset);
        PsiElement leaf = com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject())
                .findInjectedElementAt(myFixture.getFile(), offset);
        PsiElement[] targets = new OrigemGotoDeclarationHandler()
                .getGotoDeclarationTargets(leaf, offset, editor);
        assertNotNull(targets);
        assertTrue(targets.length > 0);
        assertEquals("OrigemFactory.js", targets[0].getContainingFile().getName());
    }

    public void testGotoOrigemAnnotationPathAndMethod() {
        myFixture.configureByFile("src/views/x/OrigemForm.vue");
        Editor editor = myFixture.getEditor();
        String text = editor.getDocument().getText();
        int configuracao = text.indexOf("@origem ./OrigemMixin.js cancelamento.configuracao");
        int configuracaoPath = configuracao + "@origem ./OrigemMixin.js ".length();
        gotoAnnotation(editor, configuracaoPath + 2, "OrigemMixin.js", "cancelamento");
        gotoAnnotation(editor, configuracaoPath + "cancelamento.".length() + 2,
                "OrigemMixin.js", "configuracao");
        gotoAnnotation(editor, configuracao + "@origem ".length() + 2, "OrigemMixin.js", null);
        int parametros = text.indexOf("@origem ./OrigemFactory.js criarParametros()");
        int parametrosPath = parametros + "@origem ./OrigemFactory.js ".length();
        gotoAnnotation(editor, parametrosPath + 2, "OrigemFactory.js", "criarParametros");
        int comBase = text.indexOf("@origem ./OrigemFactory.js criarComBase()");
        int comBasePath = comBase + "@origem ./OrigemFactory.js ".length();
        gotoAnnotation(editor, comBasePath + 2, "OrigemFactory.js", "criarComBase");
        int opcoes = text.indexOf("@origem ./Obj.js default");
        int opcoesPath = opcoes + "@origem ./Obj.js ".length();
        gotoAnnotation(editor, opcoesPath + 2, "Obj.js", null);
        int fab = text.indexOf("@origem ./FabNested.js default.nivel1.nivel2");
        int fabPath = fab + "@origem ./FabNested.js ".length();
        gotoAnnotation(editor, fabPath + "default.nivel1.".length() + 2, "FabNested.js", "nivel2");
        int local = text.indexOf("@origem criarLocal()");
        int localPath = local + "@origem ".length();
        gotoAnnotation(editor, localPath + 2, "OrigemForm.vue", "criarLocal");
    }

    public void testGotoOrigemThroughAction() {
        myFixture.configureByFile("src/views/x/OrigemForm.vue");
        Editor editor = myFixture.getEditor();
        String text = editor.getDocument().getText();
        int annotation = text.indexOf("@origem ./OrigemMixin.js cancelamento.configuracao");
        int offset = annotation + "@origem ./OrigemMixin.js ".length() + 2;
        editor.getCaretModel().moveToOffset(offset);
        PsiElement target = GotoDeclarationAction.findTargetElement(getProject(), editor, offset);
        assertNotNull(target);
        assertEquals("OrigemMixin.js", target.getContainingFile().getName());
    }

    private void gotoAnnotation(Editor editor, int offset, String fileName, String text) {
        editor.getCaretModel().moveToOffset(offset);
        PsiElement source = myFixture.getFile().findElementAt(offset);
        if (source == null) {
            source = myFixture.getFile();
        }
        PsiElement[] targets = new OrigemGotoDeclarationHandler()
                .getGotoDeclarationTargets(source, offset, editor);
        assertNotNull(targets);
        assertTrue(targets.length > 0);
        PsiFile target = targets[0] instanceof PsiFile file ? file : targets[0].getContainingFile();
        assertNotNull(target);
        assertEquals(fileName, target.getName());
        if (text != null) {
            assertTrue(targets[0].getText().contains(text));
        }
    }

    private List<String> completeIn(String path, String content, String marker, String source) {
        PsiFile file = myFixture.addFileToProject(path, content);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        return completeAt(marker, source);
    }

    private List<String> completeInTemplate(String path, String marker, String source) {
        myFixture.configureByFile(path);
        return completeAt(marker, source);
    }

    private List<String> completeAt(String marker, String source) {
        Editor editor = myFixture.getEditor();
        String text = editor.getDocument().getText();
        int index = text.indexOf(marker);
        int offset = index + marker.length();
        editor.getCaretModel().moveToOffset(offset);
        myFixture.completeBasic();
        return ourFields(source);
    }

    private List<String> ourFields(String source) {
        LookupElement[] elements = myFixture.getLookupElements();
        if (elements == null) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (LookupElement element : elements) {
            LookupElementPresentation presentation = new LookupElementPresentation();
            element.renderElement(presentation);
            if (presentation.getTypeText() != null && presentation.getTypeText().startsWith(source)) {
                fields.add(element.getLookupString());
            }
        }
        return fields;
    }
}
