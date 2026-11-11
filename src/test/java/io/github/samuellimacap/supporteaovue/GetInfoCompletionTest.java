package io.github.samuellimacap.supporteaovue;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.util.List;

public class GetInfoCompletionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("jsconfig.json", "{}");
        myFixture.addFileToProject("src/views/x/Usos.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.emitente + this.getInfo.usuario + this.$store.getters.info.nomeUsuario;
                    },
                  },
                };
                """);
        myFixture.addFileToProject("other/src/Outro.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.foraDoProjeto;
                    },
                  },
                };
                """);
        myFixture.addFileToProject("node_modules/pkg/Info.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.ignorado;
                    },
                  },
                };
                """);
        myFixture.addFileToProject("src/views/x/Url.js", """
                const url = '/troppus/getInfo.do';
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.do;
                    },
                  },
                };
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }

    public void testGetInfoFields() {
        List<String> strings = completeIn("src/views/x/TesteGetInfo.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.
                    },
                  },
                };
                """, "this.getInfo.");
        assertNotNull(strings);
        assertContainsElements(strings, "emitente", "usuario", "nomeUsuario");
        assertFalse(strings.contains("do"));
    }

    public void testStoreGettersInfoFields() {
        List<String> strings = completeIn("src/views/x/TesteStoreInfo.js", """
                export default {
                  methods: {
                    teste() {
                      return this.$store.getters.info.
                    },
                  },
                };
                """, "this.$store.getters.info.");
        assertNotNull(strings);
        assertContainsElements(strings, "emitente", "usuario", "nomeUsuario");
        assertFalse(strings.contains("do"));
        assertFalse(strings.contains("ignorado"));
    }

    public void testGetInfoFieldsInsideVueScript() {
        List<String> strings = completeIn("src/views/x/Info.vue", """
                <template>
                  <div />
                </template>

                <script>
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.
                    },
                  },
                };
                </script>
                """, "this.getInfo.");
        assertNotNull(strings);
        assertContainsElements(strings, "emitente", "usuario", "nomeUsuario");
        assertFalse(strings.contains("do"));
    }

    public void testGetInfoFieldsInsideVueTemplate() {
        List<String> strings = completeIn("src/views/x/TemplateInfo.vue", """
                <template>
                  <div>{{ getInfo. }}</div>
                </template>

                <script>
                export default {};
                </script>
                """, "getInfo.");
        assertNotNull(strings);
        assertContainsElements(strings, "emitente", "usuario", "nomeUsuario");
        assertFalse(strings.contains("do"));
    }

    public void testBareGettersInfoFields() {
        List<String> strings = completeIn("src/views/x/TesteGetters.js", """
                export default {
                  methods: {
                    teste() {
                      return getters.info.
                    },
                  },
                };
                """, "getters.info.");
        assertNotNull(strings);
        assertContainsElements(strings, "emitente", "usuario", "nomeUsuario");
        assertFalse(strings.contains("do"));
    }

    public void testStaleFieldWithoutFileIsNotShown() {
        PsiFile antigo = myFixture.addFileToProject("src/views/x/Antigo.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.antigo;
                    },
                  },
                };
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                antigo.getVirtualFile().delete(antigo);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        List<String> strings = completeIn("src/views/x/TesteAntigo.js", """
                export default {
                  methods: {
                    teste() {
                      return this.getInfo.
                    },
                  },
                };
                """, "this.getInfo.");
        assertNotNull(strings);
        assertFalse(strings.contains("antigo"));
    }

    private List<String> completeIn(String path, String content, String marker) {
        PsiFile file = myFixture.addFileToProject(path, content);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Editor editor = myFixture.getEditor();
        int offset = editor.getDocument().getText().indexOf(marker) + marker.length();
        editor.getCaretModel().moveToOffset(offset);
        myFixture.completeBasic();
        return myFixture.getLookupElementStrings();
    }
}
