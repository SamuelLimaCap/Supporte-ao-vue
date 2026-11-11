package io.github.samuellimacap.supporteaovue;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class MixinResolver {

    enum Kind {
        PROP, DATA, COMPUTED, METHOD
    }

    record Member(String name, Kind kind, List<String> sources, List<String> forms) {
    }

    private static final Pattern FORM =
            Pattern.compile("^\\s*(?:[/\\*]+\\s*)?@form\\s+(.+?)\\s*(?:\\*/)?\\s*$");
    private static final Pattern BLOCK = Pattern.compile("^\\s*([A-Za-z]+)\\s*:\\s*\\{\\s*$");
    private static final Pattern PROPS_ARRAY = Pattern.compile("^\\s*props\\s*:\\s*\\[");
    private static final Pattern QUOTED_VALUE = Pattern.compile("['\"]([\\w$]+)['\"]");
    private static final Pattern DATA = Pattern.compile("^\\s*data\\s*[:(]");
    private static final Pattern DATA_ARROW = Pattern.compile("=>\\s*\\(\\s*\\{\\s*$");
    private static final Pattern DATA_OBJECT = Pattern.compile("^\\s*data\\s*:\\s*\\{\\s*$");
    private static final Pattern RETURN_OBJECT = Pattern.compile("^\\s*return\\s*\\{\\s*$");
    private static final Pattern MIXINS = Pattern.compile("mixins\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern ASYNC_KEY = Pattern.compile("^\\s*async\\s+([\\w$]+)\\s*\\(");
    private static final Pattern GET_KEY = Pattern.compile("^\\s*get\\s+([\\w$]+)\\s*\\(");
    private static final Pattern NORMAL_KEY = Pattern.compile("^\\s*([\\w$]+)\\s*[(:,]");
    private static final Pattern BARE_KEY = Pattern.compile("^\\s*([\\w$]+)\\s*$");
    private static final Pattern QUOTED_KEY = Pattern.compile("^\\s*['\"]([\\w$]+)['\"]\\s*:");

    private static final String[] EXTENSIONS = {"", ".vue", ".js", "/index.vue", "/index.js"};

    private static final Key<CachedValue<List<VirtualFile>>> FORMS_KEY = Key.create("vuehelper.forms");
    private static final Key<CachedValue<List<Member>>> MEMBERS_KEY = Key.create("vuehelper.form.members");
    private static final List<Member> EMPTY = List.of();
    private static final int MAX_FORMS = 3;

    private MixinResolver() {
    }

    static List<VirtualFile> formsOf(PsiFile mixin) {
        return CachedValuesManager.getCachedValue(mixin, FORMS_KEY,
                () -> CachedValueProvider.Result.create(findForms(mixin.getVirtualFile()),
                        PsiModificationTracker.MODIFICATION_COUNT));
    }

    static List<Member> membersFor(PsiFile mixin) {
        return CachedValuesManager.getCachedValue(mixin, MEMBERS_KEY, () -> {
            VirtualFile mixinFile = mixin.getVirtualFile();
            List<VirtualFile> forms = formsOf(mixin);
            if (mixinFile == null || forms.isEmpty()) {
                return CachedValueProvider.Result.create(EMPTY, PsiModificationTracker.MODIFICATION_COUNT);
            }
            LinkedHashMap<String, Member> members = new LinkedHashMap<>();
            for (VirtualFile form : forms) {
                LinkedHashMap<String, Member> formMembers = new LinkedHashMap<>();
                Set<String> visited = new HashSet<>();
                visited.add(mixinFile.getPath());
                collect(form, visited, formMembers);
                for (Member member : formMembers.values()) {
                    merge(members, member, form.getName());
                }
            }
            return CachedValueProvider.Result.create(List.copyOf(members.values()),
                    PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    static List<VirtualFile> componentFiles(PsiFile current) {
        LinkedHashSet<VirtualFile> files = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        visitComponentFiles(current.getVirtualFile(), files, seen);
        for (VirtualFile form : formsOf(current)) {
            visitComponentFiles(form, files, seen);
        }
        return new ArrayList<>(files);
    }

    static @Nullable VirtualFile projectRoot(@Nullable VirtualFile file) {
        if (file == null) {
            return null;
        }
        VirtualFile dir = file.isDirectory() ? file : file.getParent();
        while (dir != null) {
            VirtualFile src = dir.findChild("src");
            if (src != null && src.isDirectory() && dir.findChild("jsconfig.json") != null) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void visitComponentFiles(@Nullable VirtualFile file, Set<VirtualFile> out, Set<String> seen) {
        if (file == null || file.isDirectory() || !seen.add(file.getPath())) {
            return;
        }
        out.add(file);
        String text = textOf(file);
        String[] lines = text.split("\n", -1);
        int script = 0;
        for (int i = 0; i < lines.length; i++) {
            if (stripCr(lines[i]).trim().startsWith("<script")) {
                script = i;
                break;
            }
        }
        int offset = 0;
        for (int i = 0; i < script; i++) {
            offset += lines[i].length() + 1;
        }
        Matcher mixins = MIXINS.matcher(text.substring(Math.min(offset, text.length())));
        if (!mixins.find()) {
            return;
        }
        Map<String, String> imports = imports(lines);
        for (String name : mixins.group(1).split("[^A-Za-z0-9_$]+")) {
            if (!name.isEmpty()) {
                String origin = imports.get(name);
                if (origin != null) {
                    visitComponentFiles(resolve(file.getParent(), origin), out, seen);
                }
            }
        }
    }

    private static List<VirtualFile> findForms(@Nullable VirtualFile mixin) {
        if (mixin == null || mixin.isDirectory() || !mixin.getName().endsWith(".js")) {
            return List.of();
        }
        LinkedHashSet<VirtualFile> forms = new LinkedHashSet<>();
        String[] lines = textOf(mixin).split("\n", -1);
        for (int i = 0; i < Math.min(lines.length, 10) && forms.size() < MAX_FORMS; i++) {
            Matcher matcher = FORM.matcher(stripCr(lines[i]));
            if (!matcher.find()) {
                continue;
            }
            for (String target : matcher.group(1).split("[,\\s]+")) {
                if (target.isEmpty()) {
                    continue;
                }
                if (!target.startsWith("@") && !target.startsWith(".")) {
                    target = "./" + target;
                }
                VirtualFile form = resolve(mixin.getParent(), target);
                if (form != null) {
                    forms.add(form);
                }
                if (forms.size() >= MAX_FORMS) {
                    break;
                }
            }
        }
        return List.copyOf(forms);
    }

    static @Nullable VirtualFile resolve(@Nullable VirtualFile baseDir, @Nullable String spec) {
        if (baseDir == null || spec == null || spec.isEmpty()) {
            return null;
        }
        if (!spec.startsWith("@/") && !spec.startsWith(".")) {
            return null;
        }
        VirtualFile dir = baseDir;
        String target = spec;
        if (target.startsWith("@/")) {
            VirtualFile root = projectRoot(baseDir);
            if (root == null) {
                return null;
            }
            dir = root.findChild("src");
            if (dir == null) {
                return null;
            }
            target = target.substring(2);
        }
        for (String extension : EXTENSIONS) {
            VirtualFile file = dir.findFileByRelativePath(target + extension);
            if (file != null && !file.isDirectory()) {
                return file;
            }
        }
        return null;
    }

    private static void collect(VirtualFile file, Set<String> visited, Map<String, Member> out) {
        if (file == null || file.isDirectory() || !visited.add(file.getPath())) {
            return;
        }
        String text = textOf(file);
        if (text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\n", -1);
        String source = file.getName();
        int script = 0;
        for (int i = 0; i < lines.length; i++) {
            if (stripCr(lines[i]).trim().startsWith("<script")) {
                script = i;
                break;
            }
        }
        int topo = -1;
        for (int i = script; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            String block = match(BLOCK, line);
            if (block != null
                    && ("props".equals(block) || "computed".equals(block) || "methods".equals(block))) {
                if (topo < 0) {
                    topo = indent(line);
                }
                if (indent(line) == topo) {
                    keys(lines, i, kindFor(block), source, out);
                }
                continue;
            }
            if (PROPS_ARRAY.matcher(line).find() && (topo < 0 || indent(line) == topo)) {
                StringBuilder array = new StringBuilder(line);
                int end = i;
                while (array.indexOf("]") < 0 && end + 1 < lines.length) {
                    array.append('\n').append(stripCr(lines[++end]));
                }
                Matcher quoted = QUOTED_VALUE.matcher(array);
                while (quoted.find()) {
                    add(out, quoted.group(1), Kind.PROP, source);
                }
                continue;
            }
            if (DATA.matcher(line).find() && (topo < 0 || indent(line) == topo)) {
                if (topo < 0) {
                    topo = indent(line);
                }
                if (DATA_ARROW.matcher(line).find() || DATA_OBJECT.matcher(line).find()) {
                    keys(lines, i, Kind.DATA, source, out);
                } else {
                    for (int j = i + 1; j < Math.min(lines.length, i + 31); j++) {
                        String next = stripCr(lines[j]);
                        if (RETURN_OBJECT.matcher(next).find()) {
                            keys(lines, j, Kind.DATA, source, out);
                            break;
                        }
                        if (indent(next) <= indent(line) && !next.isBlank()) {
                            break;
                        }
                    }
                }
            }
        }

        int offset = 0;
        for (int i = 0; i < script; i++) {
            offset += lines[i].length() + 1;
        }
        Matcher mixins = MIXINS.matcher(text.substring(Math.min(offset, text.length())));
        if (!mixins.find()) {
            return;
        }
        Map<String, String> imports = imports(lines);
        for (String name : mixins.group(1).split("[^A-Za-z0-9_$]+")) {
            if (name.isEmpty()) {
                continue;
            }
            String origin = imports.get(name);
            if (origin != null) {
                collect(resolve(file.getParent(), origin), visited, out);
            }
        }
    }

    private static void keys(String[] lines, int start, Kind kind, String source, Map<String, Member> out) {
        int base = indent(stripCr(lines[start]));
        int level = -1;
        for (int i = start + 1; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("/*")) {
                continue;
            }
            int current = indent(line);
            if (current <= base) {
                break;
            }
            if (level < 0) {
                level = current;
            }
            if (current != level) {
                continue;
            }
            String name = keyName(line);
            if (name != null) {
                add(out, name, kind, source);
            }
        }
    }

    private static @Nullable String keyName(String line) {
        String name = match(ASYNC_KEY, line);
        if (name == null) {
            name = match(GET_KEY, line);
        }
        if (name == null) {
            name = match(NORMAL_KEY, line);
        }
        if (name == null) {
            name = match(BARE_KEY, line);
        }
        if (name == null) {
            name = match(QUOTED_KEY, line);
        }
        return name;
    }

    private static void add(Map<String, Member> out, String name, Kind kind, String source) {
        if (name == null || name.isEmpty() || name.startsWith("_")) {
            return;
        }
        out.putIfAbsent(name, new Member(name, kind, List.of(source), List.of()));
    }

    private static void merge(Map<String, Member> out, Member member, String form) {
        Member existing = out.get(member.name());
        if (existing == null) {
            out.put(member.name(), new Member(member.name(), member.kind(), member.sources(), List.of(form)));
            return;
        }
        out.put(member.name(), new Member(member.name(), existing.kind(),
                union(existing.sources(), member.sources()), union(existing.forms(), List.of(form))));
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    static Map<String, String> imports(String[] lines) {
        Map<String, String> imports = new HashMap<>();
        for (String line : lines) {
            Matcher matcher = IMPORT.matcher(stripCr(line));
            if (matcher.find()) {
                imports.put(matcher.group(1), matcher.group(2));
            }
        }
        return imports;
    }

    private static Kind kindFor(String block) {
        return switch (block) {
            case "props" -> Kind.PROP;
            case "computed" -> Kind.COMPUTED;
            default -> Kind.METHOD;
        };
    }

    private static @Nullable String match(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    static String textOf(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException ignored) {
            return "";
        }
    }
}
