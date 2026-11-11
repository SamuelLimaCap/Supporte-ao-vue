package io.github.samuellimacap.supporteaovue;

import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

public class GetInfoFieldIndex extends ScalarIndexExtension<String> {

    public static final ID<String, Void> NAME = ID.create("io.github.samuellimacap.supporteaovue.getInfoFields");

    private static final Pattern FIELD =
            Pattern.compile("(?<![/\\w$])(?:getInfo|getters\\.info)\\??\\.([A-Za-z_$][\\w$]*)");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return content -> {
            String text = content.getContentAsText().toString();
            if (text.indexOf("getInfo") < 0) {
                return Map.of();
            }
            Map<String, Void> fields = new HashMap<>();
            Matcher matcher = FIELD.matcher(text);
            while (matcher.find()) {
                String field = matcher.group(1);
                if (!"do".equals(field)) {
                    fields.put(field, null);
                }
            }
            return fields;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return file -> {
            if (file.isDirectory()) {
                return false;
            }
            String name = file.getName();
            if (!name.endsWith(".js") && !name.endsWith(".vue")) {
                return false;
            }
            return !file.getPath().contains("/node_modules/");
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    public static List<String> allFields(Project project, VirtualFile root) {
        if (root == null) {
            return List.of();
        }
        String prefix = root.getPath() + "/src/";
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<String> fields = new TreeSet<>();
        try {
            FileBasedIndex index = FileBasedIndex.getInstance();
            for (String key : index.getAllKeys(NAME, project)) {
                for (VirtualFile file : index.getContainingFiles(NAME, key, scope)) {
                    if (file.isValid() && file.getPath().startsWith(prefix)) {
                        fields.add(key);
                        break;
                    }
                }
            }
        } catch (IndexNotReadyException ignored) {
            return List.of();
        }
        return new ArrayList<>(fields);
    }
}
