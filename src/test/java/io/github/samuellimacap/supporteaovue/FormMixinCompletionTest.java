package io.github.samuellimacap.supporteaovue;

import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

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
}
