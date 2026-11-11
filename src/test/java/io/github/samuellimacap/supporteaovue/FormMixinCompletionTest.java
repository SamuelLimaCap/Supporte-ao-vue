package io.github.samuellimacap.supporteaovue;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FormMixinCompletionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("jsconfig.json", "{}");
        myFixture.addFileToProject("src/views/x/AtalhosMixin.js", """
                export default {
                  data: () => ({
                    campoAtalho: 1,
                  }),
                  computed: {
                    computedAtalho() {
                      return 1;
                    },
                  },
                  methods: {
                    metodoAtalho() {
                    },
                  },
                };
                """);
        myFixture.addFileToProject("src/views/x/XMixin.js", """
                // @form Xform.vue
                export default {
                  data() {
                    return {
                      campoMixin: 1,
                    };
                  },
                  methods: {
                    metodoMixin() {
                    },
                  },
                };
                """);
        myFixture.addFileToProject("src/views/x/Xform.vue", """
                <template>
                  <div />
                </template>

                <script>
                import XMixin from './XMixin.js';
                import AtalhosMixin from './AtalhosMixin.js';

                export default {
                  mixins: [XMixin, AtalhosMixin],
                  props: ['propriedade'],
                  data() {
                    return {
                      campoForm: 1,
                    };
                  },
                  computed: {
                    computedForm() {
                      return 1;
                    },
                  },
                  methods: {
                    metodoForm() {
                    },
                  },
                };
                </script>
                """);
    }

    public void testThisCompletesFormAndAllItsMixins() {
        PsiFile file = myFixture.addFileToProject("src/views/x/TestMixin.js", """
                // @form Xform.vue
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        List<String> strings = completeAt(file, "this.");
        assertNotNull(strings);
        assertContainsElements(strings,
                "propriedade", "campoForm", "computedForm", "metodoForm",
                "campoAtalho", "computedAtalho", "metodoAtalho");
    }

    public void testJsDocFormAnnotation() {
        PsiFile file = myFixture.addFileToProject("src/views/x/JsDocMixin.js", """
                /** @form Xform.vue */
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        List<String> strings = completeAt(file, "this.");
        assertNotNull(strings);
        assertContainsElements(strings, "campoForm", "metodoForm");
    }

    public void testAliasFormAnnotation() {
        PsiFile file = myFixture.addFileToProject("src/views/x/AliasMixin.js", """
                // @form @/views/x/Xform.vue
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        List<String> strings = completeAt(file, "this.");
        assertNotNull(strings);
        assertContainsElements(strings, "campoForm", "metodoForm");
    }

    public void testOwnMixinMembersAreSkipped() {
        PsiFile file = myFixture.configureByFile("src/views/x/XMixin.js");
        List<String> names = MixinResolver.membersFor(file).stream()
                .map(MixinResolver.Member::name)
                .toList();
        assertFalse(names.contains("campoMixin"));
        assertFalse(names.contains("metodoMixin"));
        assertContainsElements(names, "campoForm", "metodoForm", "campoAtalho", "metodoAtalho");
    }

    public void testMultipleFormsMergeMembersAndShowBothForms() {
        addForm("FormA.vue", "campoComum", "campoA");
        addForm("FormB.vue", "campoComum", "campoB");
        PsiFile file = myFixture.addFileToProject("src/views/x/MultiMixin.js", """
                // @form FormA.vue
                // @form FormB.vue
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        Map<String, String> types = completeTypesAt(file, "this.");
        assertContainsElements(types.keySet(), "campoComum", "campoA", "campoB");
        assertTrue(types.get("campoComum").contains("FormA.vue"));
        assertTrue(types.get("campoComum").contains("FormB.vue"));
        assertTrue(types.get("campoA").contains("FormA.vue"));
        assertFalse(types.get("campoA").contains("FormB.vue"));
    }

    public void testMultipleFormsOnOneLineAndLimitOfThree() {
        addForm("FormA.vue", "campoA");
        addForm("FormB.vue", "campoB");
        addForm("FormC.vue", "campoC");
        addForm("FormD.vue", "campoD");
        PsiFile file = myFixture.addFileToProject("src/views/x/MultiMixin.js", """
                // @form FormA.vue, FormB.vue, FormC.vue, FormD.vue
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        Map<String, String> types = completeTypesAt(file, "this.");
        assertContainsElements(types.keySet(), "campoA", "campoB", "campoC");
        assertFalse(types.containsKey("campoD"));
    }

    public void testNestedMixinMembersShowEachFormThatUsesThem() {
        myFixture.addFileToProject("src/views/x/SharedMixin.js", """
                export default {
                  data: () => ({
                    campoCompartilhado: 1,
                  }),
                };
                """);
        myFixture.addFileToProject("src/views/x/FormA.vue", """
                <script>
                import SharedMixin from './SharedMixin.js';
                export default {
                  mixins: [SharedMixin],
                  data: () => ({
                    campoA: 1,
                  }),
                };
                </script>
                """);
        myFixture.addFileToProject("src/views/x/FormB.vue", """
                <script>
                import SharedMixin from './SharedMixin.js';
                export default {
                  mixins: [SharedMixin],
                  data: () => ({
                    campoB: 1,
                  }),
                };
                </script>
                """);
        PsiFile file = myFixture.addFileToProject("src/views/x/MultiMixin.js", """
                // @form FormA.vue
                // @form FormB.vue
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        Map<String, String> types = completeTypesAt(file, "this.");
        assertTrue(types.get("campoCompartilhado").contains("FormA.vue"));
        assertTrue(types.get("campoCompartilhado").contains("FormB.vue"));
        assertTrue(types.get("campoCompartilhado").contains("SharedMixin.js"));
        assertTrue(types.get("campoA").contains("FormA.vue"));
        assertFalse(types.get("campoA").contains("FormB.vue"));
    }

    public void testOrigemFromSecondFormIsUsed() {
        myFixture.addFileToProject("src/views/x/ObjB.js", """
                export default {
                  subCampoB: 1,
                };
                """);
        myFixture.addFileToProject("src/views/x/FormA.vue", """
                <script>
                export default {
                  data: () => ({
                    campoA: 1,
                  }),
                };
                </script>
                """);
        myFixture.addFileToProject("src/views/x/FormB.vue", """
                <script>
                export default {
                  props: {
                    /** @origem ./ObjB.js default */
                    configuracao: {type: Object, default: () => ({})},
                  },
                };
                </script>
                """);
        PsiFile file = myFixture.addFileToProject("src/views/x/MultiMixin.js", """
                // @form FormA.vue
                // @form FormB.vue
                export default {
                  methods: {
                    teste() {
                      this.configuracao.
                    },
                  },
                };
                """);
        List<String> strings = completeAt(file, "this.configuracao.");
        assertNotNull(strings);
        assertContainsElements(strings, "subCampoB");
    }

    public void testThisWithoutFormDoesNotComplete() {
        PsiFile file = myFixture.addFileToProject("src/views/x/SemForm.js", """
                export default {
                  methods: {
                    teste() {
                      this.
                    },
                  },
                };
                """);
        List<String> strings = completeAt(file, "this.");
        assertTrue(strings == null || !strings.contains("campoForm"));
    }

    private List<String> completeAt(PsiFile file, String marker) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Editor editor = myFixture.getEditor();
        int offset = editor.getDocument().getText().indexOf(marker) + marker.length();
        editor.getCaretModel().moveToOffset(offset);
        myFixture.completeBasic();
        return myFixture.getLookupElementStrings();
    }

    private Map<String, String> completeTypesAt(PsiFile file, String marker) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Editor editor = myFixture.getEditor();
        int offset = editor.getDocument().getText().indexOf(marker) + marker.length();
        editor.getCaretModel().moveToOffset(offset);
        myFixture.completeBasic();
        Map<String, String> types = new LinkedHashMap<>();
        LookupElement[] elements = myFixture.getLookupElements();
        if (elements == null) {
            return types;
        }
        for (LookupElement element : elements) {
            LookupElementPresentation presentation = new LookupElementPresentation();
            element.renderElement(presentation);
            String typeText = presentation.getTypeText();
            if (types.get(element.getLookupString()) == null || typeText != null) {
                types.put(element.getLookupString(), typeText);
            }
        }
        return types;
    }

    private void addForm(String name, String... fields) {
        StringBuilder data = new StringBuilder();
        for (String field : fields) {
            data.append("      ").append(field).append(": 1,\n");
        }
        myFixture.addFileToProject("src/views/x/" + name, """
                <script>
                export default {
                  data() {
                    return {
                %s    };
                  },
                };
                </script>
                """.formatted(data));
    }
}
