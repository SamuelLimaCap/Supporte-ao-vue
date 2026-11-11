package io.github.samuellimacap.supporteaovue;

import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment;
import com.intellij.lang.javascript.JavaScriptFileType;
import com.intellij.lang.javascript.psi.JSArgumentList;
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression;
import com.intellij.lang.javascript.psi.JSBlockStatement;
import com.intellij.lang.javascript.psi.JSCallExpression;
import com.intellij.lang.javascript.psi.JSElement;
import com.intellij.lang.javascript.psi.JSExpression;
import com.intellij.lang.javascript.psi.JSFile;
import com.intellij.lang.javascript.psi.JSForInStatement;
import com.intellij.lang.javascript.psi.JSFunction;
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression;
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression;
import com.intellij.lang.javascript.psi.JSParameter;
import com.intellij.lang.javascript.psi.JSParameterItem;
import com.intellij.lang.javascript.psi.JSParenthesizedExpression;
import com.intellij.lang.javascript.psi.JSProperty;
import com.intellij.lang.javascript.psi.JSReferenceExpression;
import com.intellij.lang.javascript.psi.JSReturnStatement;
import com.intellij.lang.javascript.psi.JSSpreadExpression;
import com.intellij.lang.javascript.psi.JSThisExpression;
import com.intellij.lang.javascript.psi.JSVariable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

// Guia rapido:
//
// // @form X.vue  -> no mixin, this. completa os forms (até 3) e todos os mixins deles.
// this.getInfo.  -> campos de getInfo usados no projeto.
// @origem        -> antes de uma prop/data/computed, aponta de onde vem o objeto:
//                   /** @origem ../Mixin.js cancelamento.configuracao */
//                   /** @origem ../Factory.js criarParametros() */
//                   /** @origem ../Factory.js criarCupom()[] */   (array de objetos)
//                   /** @origem ./Obj.js default */
//                   /** @origem criarLocal() */                   (mesmo arquivo)
//                   /** @origem ../Mixin.js */                    (caminho omitido)
//                   Campos aninhados, this.x[0]., x.find(c => c.), const c = this.x[0], for..of,
//                   v-for e Ctrl+clique no campo. Ctrl+clique na propria anotacao vai para o
//                   arquivo/caminho/default/funcao/variável apontados.
final class OrigemResolver {

    record Origem(VirtualFile target, String path, boolean list) {
    }

    record Field(String name, boolean method, String source, int line, String lineText, PsiElement declaration) {
    }

    sealed interface State permits ObjectState, ListState {
    }

    record ObjectState(JSObjectLiteralExpression object, Module module) implements State {
    }

    record ListState(State item) implements State {
    }

    record VForScope(String tag, String alias, String expression) {
    }

    static final class Context {
        final Project project;
        final PsiFile host;
        final VirtualFile hostVirtualFile;
        final boolean template;
        final int hostOffset;
        final Map<String, Origem> origens;
        final List<VForScope> vForScopes;
        final Module currentModule;

        Context(Project project, PsiFile host, VirtualFile hostVirtualFile, boolean template, int hostOffset,
                Map<String, Origem> origens, List<VForScope> vForScopes, Module currentModule) {
            this.project = project;
            this.host = host;
            this.hostVirtualFile = hostVirtualFile;
            this.template = template;
            this.hostOffset = hostOffset;
            this.origens = origens;
            this.vForScopes = vForScopes;
            this.currentModule = currentModule;
        }
    }

    private record ImportRef(String target, String name, boolean isDefault) {
    }

    private record Decl(Module module, PsiElement element) {
    }

    private record PropertyRef(Module module, JSProperty property) {
    }

    private record Segment(String text, int start, int end) {
    }

    private static final class Module {
        final Project project;
        final VirtualFile virtualFile;
        final PsiFile hostFile;
        final String sourceName;
        final int scriptStartOffset;
        final int lineOffset;
        final String scriptText;
        final JSFile psiFile;
        final boolean synthetic;
        final Map<String, PsiElement> declarations;
        final Map<String, ImportRef> imports;
        final JSExpression defaultExport;

        Module(Project project, VirtualFile virtualFile, PsiFile hostFile, String sourceName,
               int scriptStartOffset, int lineOffset, String scriptText, JSFile psiFile, boolean synthetic,
               Map<String, PsiElement> declarations, Map<String, ImportRef> imports, JSExpression defaultExport) {
            this.project = project;
            this.virtualFile = virtualFile;
            this.hostFile = hostFile;
            this.sourceName = sourceName;
            this.scriptStartOffset = scriptStartOffset;
            this.lineOffset = lineOffset;
            this.scriptText = scriptText;
            this.psiFile = psiFile;
            this.synthetic = synthetic;
            this.declarations = declarations;
            this.imports = imports;
            this.defaultExport = defaultExport;
        }
    }

    private static final Pattern ORIGEM = Pattern.compile("@origem\\s+([^\\s*]+)(?:\\s+([\\w$.()\\[\\]]*))?");
    private static final Pattern MEMBER =
            Pattern.compile("^\\s*(?:async\\s+)?(?:get\\s+|set\\s+)?([\\w$]+)\\s*[:(,]");
    private static final Pattern QUOTED_MEMBER = Pattern.compile("^\\s*['\"]([\\w$]+)['\"]\\s*:");
    private static final Pattern IMPORT_DEFAULT =
            Pattern.compile("^\\s*import\\s+([\\w$]+)(?:\\s*,\\s*\\{([^}]*)\\})?\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern IMPORT_NAMED =
            Pattern.compile("^\\s*import\\s*\\{([^}]*)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern IMPORT_SPECIFIER =
            Pattern.compile("([\\w$]+)(?:\\s+as\\s+([\\w$]+))?");
    private static final Pattern V_FOR = Pattern.compile(
            "^\\(?\\s*([A-Za-z_$][\\w$]*)\\s*(?:,\\s*[A-Za-z_$][\\w$]*)?\\s*\\)?\\s+(?:in|of)\\s+(.+)$");
    private static final Pattern V_FOR_ATTR =
            Pattern.compile("(?:^|\\s)v-for\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private static final Set<String> CALLBACK_METHODS = Set.of(
            "find", "findLast", "findIndex", "filter", "forEach", "map", "flatMap", "some", "every");
    private static final Set<String> ITEM_METHODS = Set.of("find", "findLast", "at");
    private static final Set<String> LIST_METHODS = Set.of(
            "filter", "slice", "sort", "concat", "reverse", "splice");
    private static final Set<String> COMPONENT_KEYS = Set.of(
            "data", "methods", "computed", "props", "mixins", "components");
    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
            "source", "track", "wbr");
    private static final int MAX_DEPTH = 60;

    private static final Key<CachedValue<Map<String, Origem>>> ORIGENS = Key.create("vuehelper.origens");
    private static final Key<Map<String, Module>> MODULES = Key.create("vuehelper.modules");

    private OrigemResolver() {
    }

    static Context context(PsiFile host, int hostOffset, boolean template) {
        VirtualFile virtualFile = host.getVirtualFile();
        Module current = virtualFile == null ? null : module(host.getProject(), virtualFile);
        List<VForScope> scopes = template && virtualFile != null
                ? vForScopes(host, hostOffset)
                : List.of();
        return new Context(host.getProject(), host, virtualFile, template, hostOffset,
                origens(host), scopes, current);
    }

    static boolean isInsideTemplate(PsiFile host, int offset) {
        if (host == null || !host.getName().endsWith(".vue")) {
            return false;
        }
        String text = host.getText();
        int script = text.indexOf("<script");
        if (script >= 0 && offset >= script) {
            return false;
        }
        int open = text.lastIndexOf("<template", Math.max(0, Math.min(offset, text.length())));
        int close = text.lastIndexOf("</template", Math.max(0, Math.min(offset, text.length())));
        return open >= 0 && open > close;
    }

    static @Nullable State stateOfQualifier(@Nullable PsiElement qualifier, Context context) {
        if (qualifier == null || context == null || context.currentModule == null) {
            return null;
        }
        return stateOfElement(qualifier, context.currentModule, context, new HashSet<>(), 0);
    }

    static List<Field> fields(@Nullable State state) {
        if (!(state instanceof ObjectState object)) {
            return List.of();
        }
        List<Field> fields = new ArrayList<>();
        collectFields(object, new HashSet<>(), new HashSet<>(), fields);
        return fields;
    }

    static @Nullable PsiElement fieldDeclaration(@Nullable State state, @Nullable String name) {
        if (!(state instanceof ObjectState object) || name == null || name.isEmpty()) {
            return null;
        }
        PropertyRef reference = findPropertyRef(object, name, new HashSet<>(), 0);
        return reference == null ? null : hostElement(reference.module(), reference.property());
    }

    static @Nullable PsiElement definitionAt(PsiFile host, int hostOffset) {
        VirtualFile hostFile = host == null ? null : host.getVirtualFile();
        if (hostFile == null) {
            return null;
        }
        String text = host.getText();
        if (text.isEmpty()) {
            return null;
        }
        int offset = Math.max(0, Math.min(hostOffset, text.length()));
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        if (end < 0) {
            end = text.length();
        }
        String line = text.substring(start, end);
        String trimmedLine = line.trim();
        if (!trimmedLine.startsWith("//") && !trimmedLine.startsWith("*") && !trimmedLine.startsWith("/*")) {
            return null;
        }
        Matcher matcher = ORIGEM.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        int caret = offset - start;
        String first = matcher.group(1);
        String second = matcher.group(2);
        String fileSpec;
        String path;
        int pathStart;
        boolean fileToken;
        if (second != null && !second.isEmpty()) {
            fileSpec = first;
            path = second;
            pathStart = matcher.start(2);
            fileToken = inToken(caret, matcher.start(1), matcher.end(1));
            if (!fileToken && !inToken(caret, matcher.start(2), matcher.end(2))) {
                return null;
            }
        } else if (looksLikeFile(first)) {
            fileSpec = first;
            path = null;
            pathStart = -1;
            fileToken = inToken(caret, matcher.start(1), matcher.end(1));
            if (!fileToken) {
                return null;
            }
        } else {
            fileSpec = null;
            path = first;
            pathStart = matcher.start(1);
            fileToken = false;
            if (!inToken(caret, matcher.start(1), matcher.end(1))) {
                return null;
            }
        }
        VirtualFile target = fileSpec == null
                ? hostFile
                : resolveFile(hostFile.getParent(), normalizeSpec(fileSpec));
        if (target == null) {
            return null;
        }
        if (fileToken) {
            return PsiManager.getInstance(host.getProject()).findFile(target);
        }
        if (path == null || path.isEmpty()) {
            return null;
        }
        String pathText = path;
        if (pathText.endsWith("[]")) {
            pathText = pathText.substring(0, pathText.length() - 2);
        }
        List<Segment> segments = segments(pathText);
        Module module = module(host.getProject(), target);
        if (module == null) {
            return null;
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (inToken(caret, pathStart + segment.start(), pathStart + segment.end())) {
                return pathDefinition(module, segments, i);
            }
        }
        return null;
    }

    private static String normalizeSpec(String spec) {
        return spec.startsWith("@") || spec.startsWith(".") ? spec : "./" + spec;
    }

    private static boolean inToken(int value, int start, int end) {
        return value >= start && value <= end;
    }

    private static List<Segment> segments(String path) {
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '.') {
                if (i > start) {
                    segments.add(new Segment(path.substring(start, i), start, i));
                }
                start = i + 1;
            }
        }
        return segments;
    }

    private static @Nullable PsiElement pathDefinition(Module module, List<Segment> segments, int index) {
        if (index < 0 || index >= segments.size()) {
            return null;
        }
        Segment segment = segments.get(index);
        String name = stripCall(segment.text());
        if (index == 0) {
            if ("default".equals(name) && !segment.text().endsWith("()")) {
                return defaultDeclaration(module);
            }
            if (segment.text().endsWith("()")) {
                Decl call = resolveName(module, name, new HashSet<>());
                if (call != null) {
                    return hostElement(call.module(), call.element());
                }
            }
            State defaultState = stateOfElement(module.defaultExport, module, null, new HashSet<>(), 0);
            if (defaultState instanceof ObjectState object) {
                PropertyRef data = findPropertyRef(object, "data", new HashSet<>(), 0);
                if (data != null) {
                    State dataState = stateOfElement(data.property().getValue(), data.module(), null,
                            new HashSet<>(), 0);
                    if (dataState instanceof ObjectState dataObject) {
                        PropertyRef reference = findPropertyRef(dataObject, name, new HashSet<>(), 0);
                        if (reference != null) {
                            return hostElement(reference.module(), reference.property());
                        }
                    }
                }
            }
            Decl declaration = resolveName(module, name, new HashSet<>());
            return declaration == null ? null : hostElement(declaration.module(), declaration.element());
        }
        State state;
        String first = stripCall(segments.get(0).text());
        if ("default".equals(first) && !segments.get(0).text().endsWith("()")) {
            state = stateOfElement(module.defaultExport, module, null, new HashSet<>(), 0);
        } else {
            state = nameState(module, first, null, new HashSet<>(), 0);
        }
        for (int i = 1; i < index && state != null; i++) {
            state = fieldState(state, stripCall(segments.get(i).text()), null, new HashSet<>(), 0);
        }
        if (!(state instanceof ObjectState object)) {
            return null;
        }
        PropertyRef reference = findPropertyRef(object, name, new HashSet<>(), 0);
        return reference == null ? null : hostElement(reference.module(), reference.property());
    }

    private static @Nullable PsiElement defaultDeclaration(Module module) {
        if (module.defaultExport == null) {
            return null;
        }
        ES6ExportDefaultAssignment assignment =
                PsiTreeUtil.getParentOfType(module.defaultExport, ES6ExportDefaultAssignment.class);
        return hostElement(module, assignment == null ? module.defaultExport : assignment);
    }

    static Map<String, Origem> origens(PsiFile current) {
        return CachedValuesManager.getCachedValue(current, ORIGENS, () -> {
            Map<String, Origem> origens = new LinkedHashMap<>();
            for (VirtualFile file : MixinResolver.componentFiles(current)) {
                String text = MixinResolver.textOf(file);
                if (!text.isEmpty() && text.contains("@origem")) {
                    parseOrigem(file, text, origens);
                }
            }
            return CachedValueProvider.Result.create(Collections.unmodifiableMap(origens),
                    PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static void parseOrigem(VirtualFile file, String text, Map<String, Origem> out) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = ORIGEM.matcher(stripCr(lines[i]));
            if (!matcher.find()) {
                continue;
            }
            String first = matcher.group(1);
            String second = matcher.group(2);
            String fileSpec = null;
            String path = null;
            if (second != null && !second.isEmpty()) {
                fileSpec = first;
                path = second;
            } else if (looksLikeFile(first)) {
                fileSpec = first;
            } else {
                path = first;
            }
            boolean list = false;
            if (path != null && path.endsWith("[]")) {
                list = true;
                path = path.substring(0, path.length() - 2);
            }
            for (int j = i + 1; j < Math.min(lines.length, i + 16); j++) {
                String line = stripCr(lines[j]);
                int close = line.indexOf("*/");
                if (close >= 0) {
                    line = line.substring(close + 2);
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                        || trimmed.startsWith("/*")) {
                    continue;
                }
                String member = memberName(line);
                if (member != null) {
                    VirtualFile target;
                    if (fileSpec == null) {
                        target = file;
                    } else {
                        String spec = fileSpec;
                        if (!spec.startsWith("@") && !spec.startsWith(".")) {
                            spec = "./" + spec;
                        }
                        target = resolveFile(file.getParent(), spec);
                    }
                    if (target != null) {
                        out.putIfAbsent(member, new Origem(target, path, list));
                    }
                }
                break;
            }
        }
    }

    private static boolean looksLikeFile(String token) {
        return token.contains("/") || token.startsWith("@") || token.startsWith(".")
                || token.endsWith(".js") || token.endsWith(".vue") || token.endsWith(".ts");
    }

    private static @Nullable String memberName(String line) {
        Matcher matcher = MEMBER.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = QUOTED_MEMBER.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static @Nullable State resolveOrigem(Origem origem, String member, Context context,
                                                 Set<PsiElement> visited, int depth) {
        Module module = module(context.project, origem.target());
        if (module == null || depth > MAX_DEPTH) {
            return null;
        }
        Context targetContext = module.virtualFile.equals(context.hostVirtualFile) ? context : null;
        String path = origem.path();
        State state;
        if (path == null || path.isEmpty()) {
            state = nameState(module, member, targetContext, visited, depth + 1);
            if (state == null) {
                State def = stateOfElement(module.defaultExport, module, targetContext, new HashSet<>(), depth + 1);
                if (def instanceof ObjectState object && !isComponent(object)) {
                    state = object;
                }
            }
        } else if (path.equals("default") || path.startsWith("default.")) {
            state = stateOfElement(module.defaultExport, module, targetContext, visited, depth + 1);
            String[] parts = path.split("\\.");
            for (int i = 1; i < parts.length && state != null; i++) {
                state = fieldState(state, parts[i], targetContext, visited, depth + 1);
            }
        } else {
            String[] parts = path.split("\\.");
            state = nameState(module, stripCall(parts[0]), targetContext, visited, depth + 1);
            for (int i = 1; i < parts.length && state != null; i++) {
                state = fieldState(state, parts[i], targetContext, visited, depth + 1);
            }
        }
        if (state == null) {
            return null;
        }
        if (origem.list()) {
            if (state instanceof ObjectState) {
                return new ListState(state);
            }
            return state instanceof ListState ? state : null;
        }
        return state;
    }

    private static @Nullable State nameState(Module module, String name, Context context,
                                             Set<PsiElement> visited, int depth) {
        if (name == null || name.isEmpty() || depth > MAX_DEPTH) {
            return null;
        }
        State def = stateOfElement(module.defaultExport, module, context, visited, depth + 1);
        if (def instanceof ObjectState object) {
            PropertyRef data = findPropertyRef(object, "data", visited, depth + 1);
            if (data != null) {
                State dataState = stateOfElement(data.property().getValue(), data.module(), context, visited, depth + 1);
                if (dataState instanceof ObjectState dataObject) {
                    State found = fieldState(dataObject, name, context, visited, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        Decl declaration = resolveName(module, name, new HashSet<>());
        return declaration == null ? null : stateOfElement(declaration.element(), declaration.module(),
                context, visited, depth + 1);
    }

    private static @Nullable State stateOfElement(@Nullable PsiElement element, Module module, Context context,
                                                  Set<PsiElement> visited, int depth) {
        if (element == null || module == null || depth > MAX_DEPTH || !visited.add(element)) {
            return null;
        }
        if (element instanceof JSParenthesizedExpression parenthesized) {
            return stateOfElement(parenthesized.getInnerExpression(), module, context, visited, depth + 1);
        }
        if (element instanceof JSObjectLiteralExpression object) {
            return new ObjectState(object, module);
        }
        if (element instanceof JSArrayLiteralExpression array) {
            JSExpression[] items = array.getExpressions();
            if (items.length == 0) {
                return new ListState(null);
            }
            return new ListState(stateOfElement(items[0], module, context, visited, depth + 1));
        }
        if (element instanceof JSFunction function) {
            return returnState(function, module, context, visited, depth + 1);
        }
        if (element instanceof JSParameter parameter) {
            return parameterState(parameter, module, context, visited, depth + 1);
        }
        if (element instanceof JSVariable variable) {
            return variableState(variable, module, context, visited, depth + 1);
        }
        if (element instanceof JSProperty property) {
            return stateOfElement(property.getValue(), module, context, visited, depth + 1);
        }
        if (element instanceof JSCallExpression call) {
            return callState(call, module, context, visited, depth + 1);
        }
        if (element instanceof JSIndexedPropertyAccessExpression indexed) {
            State base = stateOfElement(indexed.getQualifier(), module, context, visited, depth + 1);
            return base instanceof ListState list ? list.item() : null;
        }
        if (element instanceof JSReferenceExpression reference) {
            return referenceState(reference, module, context, visited, depth + 1);
        }
        if (element instanceof ES6ExportDefaultAssignment export) {
            return stateOfElement(export.getExpression(), module, context, visited, depth + 1);
        }
        return null;
    }

    private static @Nullable State referenceState(JSReferenceExpression reference, Module module, Context context,
                                                  Set<PsiElement> visited, int depth) {
        String name = reference.getReferenceName();
        PsiElement qualifier = reference.getQualifier();
        if (qualifier != null) {
            if (qualifier instanceof JSThisExpression) {
                if (context == null || name == null) {
                    return null;
                }
                return origemState(context, name, visited, depth + 1);
            }
            State base = stateOfElement(qualifier, module, context, visited, depth + 1);
            return fieldState(base, name, context, visited, depth + 1);
        }
        if (context != null && context.template) {
            return templateNameState(name, context, visited, depth + 1);
        }
        PsiElement resolved = null;
        try {
            resolved = reference.resolve();
        } catch (RuntimeException ignored) {
        }
        if (resolved != null && resolved != reference) {
            return stateOfElement(resolved, module, context, visited, depth + 1);
        }
        State callback = callbackParameterState(name, module, context, reference, visited, depth + 1);
        if (callback != null) {
            return callback;
        }
        Decl declaration = name == null ? null : resolveName(module, name, new HashSet<>());
        return declaration == null ? null : stateOfElement(declaration.element(), declaration.module(),
                context, visited, depth + 1);
    }

    private static @Nullable State origemState(Context context, String member,
                                               Set<PsiElement> visited, int depth) {
        Origem origem = context.origens.get(member);
        return origem == null ? null : resolveOrigem(origem, member, context, visited, depth + 1);
    }

    private static @Nullable State variableState(JSVariable variable, Module module, Context context,
                                                 Set<PsiElement> visited, int depth) {
        if (variable.getInitializer() != null) {
            return stateOfElement(variable.getInitializer(), module, context, visited, depth + 1);
        }
        JSForInStatement forIn = PsiTreeUtil.getParentOfType(variable, JSForInStatement.class);
        if (forIn != null && forIn.isForEach()) {
            State collection = stateOfElement(forIn.getCollectionExpression(), module, context, visited, depth + 1);
            if (collection instanceof ListState list) {
                return list.item();
            }
        }
        return null;
    }

    private static @Nullable State parameterState(JSParameter parameter, Module module, Context context,
                                                  Set<PsiElement> visited, int depth) {
        JSFunction function = PsiTreeUtil.getParentOfType(parameter, JSFunction.class);
        if (function == null) {
            return null;
        }
        JSCallExpression call = callbackCall(function);
        if (call == null) {
            return null;
        }
        JSExpression method = call.getMethodExpression();
        String name = methodName(method);
        int index = parameterIndex(function, parameter);
        if (name == null || !CALLBACK_METHODS.contains(name) || index != 0) {
            return null;
        }
        PsiElement target = method instanceof JSReferenceExpression reference ? reference.getQualifier() : null;
        State collection = stateOfElement(target, module, context, visited, depth + 1);
        return collection instanceof ListState list ? list.item() : null;
    }

    private static @Nullable State callbackParameterState(String name, Module module, Context context,
                                                         PsiElement reference, Set<PsiElement> visited,
                                                         int depth) {
        JSFunction function = PsiTreeUtil.getParentOfType(reference, JSFunction.class);
        if (function == null || name == null) {
            return null;
        }
        JSParameterItem[] parameters = function.getParameters();
        if (parameters.length == 0 || !name.equals(parameters[0].getName())) {
            return null;
        }
        JSCallExpression call = callbackCall(function);
        if (call == null) {
            return null;
        }
        String method = methodName(call.getMethodExpression());
        if (method == null || !CALLBACK_METHODS.contains(method)) {
            return null;
        }
        PsiElement target = methodQualifier(call.getMethodExpression());
        State collection = stateOfElement(target, module, context, visited, depth + 1);
        return collection instanceof ListState list ? list.item() : null;
    }

    private static @Nullable JSCallExpression callbackCall(JSFunction function) {
        JSCallExpression call = PsiTreeUtil.getParentOfType(function, JSCallExpression.class);
        if (call == null || call.getArgumentList() == null) {
            return null;
        }
        for (JSExpression argument : call.getArgumentList().getArguments()) {
            if (argument == function) {
                return call;
            }
            if (argument instanceof JSParenthesizedExpression parenthesized
                    && parenthesized.getInnerExpression() == function) {
                return call;
            }
        }
        return null;
    }

    private static int parameterIndex(JSFunction function, JSParameter parameter) {
        JSParameterItem[] parameters = function.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == parameter || (parameters[i] instanceof PsiElement element
                    && PsiTreeUtil.isAncestor(element, parameter, false))) {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable State returnState(JSFunction function, Module module, Context context,
                                               Set<PsiElement> visited, int depth) {
        JSBlockStatement block = function.getBlock();
        if (block != null) {
            for (JSReturnStatement statement : PsiTreeUtil.findChildrenOfType(block, JSReturnStatement.class)) {
                if (PsiTreeUtil.getParentOfType(statement, JSFunction.class) != function) {
                    continue;
                }
                JSExpression expression = statement.getExpression();
                if (expression != null) {
                    State state = stateOfElement(expression, module, context, visited, depth + 1);
                    if (state != null) {
                        return state;
                    }
                }
            }
            return null;
        }
        for (PsiElement child : function.getChildren()) {
            if (child instanceof JSExpression expression) {
                State state = stateOfElement(expression, module, context, visited, depth + 1);
                if (state != null) {
                    return state;
                }
            }
        }
        return null;
    }

    private static @Nullable State callState(JSCallExpression call, Module module, Context context,
                                             Set<PsiElement> visited, int depth) {
        JSExpression method = call.getMethodExpression();
        String name = methodName(method);
        String text = method == null ? "" : method.getText().trim();
        if ("defineComponent".equals(text) || text.endsWith(".extend")) {
            JSArgumentList arguments = call.getArgumentList();
            if (arguments != null && arguments.getArguments().length > 0) {
                return stateOfElement(arguments.getArguments()[0], module, context, visited, depth + 1);
            }
            return null;
        }
        if (name != null && ITEM_METHODS.contains(name)) {
            State target = stateOfElement(methodQualifier(method), module, context, visited, depth + 1);
            return target instanceof ListState list ? list.item() : null;
        }
        if (name != null && LIST_METHODS.contains(name)) {
            State target = stateOfElement(methodQualifier(method), module, context, visited, depth + 1);
            return target instanceof ListState list ? new ListState(list.item()) : null;
        }
        if (name != null && CALLBACK_METHODS.contains(name) && !ITEM_METHODS.contains(name)) {
            return null;
        }
        PsiElement resolved = null;
        try {
            resolved = method instanceof JSReferenceExpression reference ? reference.resolve() : null;
        } catch (RuntimeException ignored) {
        }
        if (resolved != null && resolved != method) {
            State state = stateOfElement(resolved, module, context, visited, depth + 1);
            if (state != null) {
                return state;
            }
        }
        String functionName = name == null ? null : lastSegment(name);
        Decl declaration = functionName == null ? null : resolveName(module, functionName, new HashSet<>());
        return declaration == null ? null : stateOfElement(declaration.element(), declaration.module(),
                context, visited, depth + 1);
    }

    private static @Nullable State fieldState(@Nullable State state, @Nullable String name, Context context,
                                              Set<PsiElement> visited, int depth) {
        if (!(state instanceof ObjectState object) || name == null || name.isEmpty() || depth > MAX_DEPTH) {
            return null;
        }
        PropertyRef reference = findPropertyRef(object, name, visited, depth + 1);
        return reference == null ? null : stateOfElement(reference.property().getValue(), reference.module(),
                context, visited, depth + 1);
    }

    private static @Nullable PropertyRef findPropertyRef(ObjectState object, String name,
                                                         Set<PsiElement> visited, int depth) {
        if (name == null || depth > MAX_DEPTH) {
            return null;
        }
        for (JSProperty property : object.object.getProperties()) {
            if (name.equals(property.getName())) {
                return new PropertyRef(object.module, property);
            }
        }
        for (JSSpreadExpression spread : spreads(object.object)) {
            State state = stateOfElement(spread.getExpression(), object.module, null, new HashSet<>(), depth + 1);
            if (state instanceof ObjectState spreadObject) {
                PropertyRef reference = findPropertyRef(spreadObject, name, visited, depth + 1);
                if (reference != null) {
                    return reference;
                }
            }
        }
        return null;
    }

    private static List<JSSpreadExpression> spreads(JSObjectLiteralExpression object) {
        List<JSSpreadExpression> spreads = new ArrayList<>();
        for (JSElement element : object.getPropertiesIncludingSpreads()) {
            if (element instanceof JSSpreadExpression spread) {
                spreads.add(spread);
            }
        }
        return spreads;
    }

    private static boolean isComponent(ObjectState object) {
        for (JSProperty property : object.object.getProperties()) {
            if (COMPONENT_KEYS.contains(property.getName())) {
                return true;
            }
        }
        for (JSSpreadExpression spread : spreads(object.object)) {
            State state = stateOfElement(spread.getExpression(), object.module, null, new HashSet<>(), 0);
            if (state instanceof ObjectState spreadObject && isComponent(spreadObject)) {
                return true;
            }
        }
        return false;
    }

    private static void collectFields(ObjectState object, Set<PsiElement> visited, Set<String> seen,
                                      List<Field> fields) {
        if (object == null || !visited.add(object.object())) {
            return;
        }
        for (JSProperty property : object.object.getProperties()) {
            String name = property.getName();
            if (name == null || name.isEmpty() || !seen.add(name)) {
                continue;
            }
            JSExpression value = property.getValue();
            fields.add(new Field(name, value instanceof JSFunction, object.module.sourceName,
                    lineOf(object.module, property), lineText(object.module, property),
                    hostElement(object.module, property)));
        }
        for (JSSpreadExpression spread : spreads(object.object)) {
            State state = stateOfElement(spread.getExpression(), object.module, null, new HashSet<>(), 0);
            if (state instanceof ObjectState spreadObject) {
                collectFields(spreadObject, visited, seen, fields);
            }
        }
    }

    private static int lineOf(Module module, PsiElement element) {
        int line = StringUtil.offsetToLineNumber(module.psiFile.getText(), element.getTextOffset());
        return module.lineOffset + line + 1;
    }

    private static String lineText(Module module, PsiElement element) {
        String text = module.psiFile.getText();
        int line = StringUtil.offsetToLineNumber(text, element.getTextOffset());
        String[] lines = text.split("\n", -1);
        return line >= 0 && line < lines.length ? stripCr(lines[line]).trim() : "";
    }

    private static @Nullable PsiElement hostElement(Module module, PsiElement element) {
        if (element == null || !module.synthetic || module.hostFile == null) {
            return element;
        }
        int offset = module.scriptStartOffset + element.getTextOffset();
        PsiElement leaf = module.hostFile.findElementAt(offset);
        PsiElement current = leaf;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof JSProperty || current instanceof JSVariable || current instanceof JSFunction
                    || current instanceof ES6ExportDefaultAssignment || current instanceof JSObjectLiteralExpression) {
                return current;
            }
            current = current.getParent();
        }
        return element;
    }

    private static @Nullable Decl resolveName(Module module, String name, Set<String> visited) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        PsiElement declaration = module.declarations.get(name);
        if (declaration != null) {
            return new Decl(module, declaration);
        }
        ImportRef imported = module.imports.get(name);
        if (imported == null || !visited.add(module.virtualFile.getPath() + ":" + name)) {
            return null;
        }
        VirtualFile target = resolveFile(module.virtualFile.getParent(), imported.target());
        if (target == null) {
            return null;
        }
        Module other = module(module.project, target);
        if (other == null) {
            return null;
        }
        if (imported.isDefault()) {
            return other.defaultExport == null ? null : new Decl(other, other.defaultExport);
        }
        return resolveName(other, imported.name(), visited);
    }

    private static @Nullable VirtualFile resolveFile(@Nullable VirtualFile baseDir, @Nullable String spec) {
        if (baseDir == null || spec == null || spec.isEmpty()) {
            return null;
        }
        if (!spec.startsWith("@/") && !spec.startsWith(".")) {
            return null;
        }
        VirtualFile dir = baseDir;
        String target = spec;
        if (target.startsWith("@/")) {
            VirtualFile root = MixinResolver.projectRoot(baseDir);
            if (root == null) {
                return null;
            }
            dir = root.findChild("src");
            if (dir == null) {
                return null;
            }
            target = target.substring(2);
        }
        for (String extension : new String[]{"", ".js", "/index.js", ".vue", "/index.vue"}) {
            VirtualFile file = dir.findFileByRelativePath(target + extension);
            if (file != null && !file.isDirectory()) {
                return file;
            }
        }
        return null;
    }

    private static @Nullable Module module(Project project, VirtualFile target) {
        if (target == null || target.isDirectory() || !target.isValid()) {
            return null;
        }
        String text = MixinResolver.textOf(target);
        String scriptText;
        int scriptStartOffset;
        int lineOffset;
        PsiFile hostFile = null;
        JSFile psiFile = null;
        boolean synthetic = false;
        if (target.getName().endsWith(".vue")) {
            int[] range = scriptRange(text);
            if (range == null) {
                return null;
            }
            scriptText = text.substring(range[0], range[1]);
            scriptStartOffset = range[0];
            lineOffset = StringUtil.offsetToLineNumber(text, range[0]);
            hostFile = PsiManager.getInstance(project).findFile(target);
            PsiFile created = PsiFileFactory.getInstance(project)
                    .createFileFromText(target.getName() + ".js", JavaScriptFileType.INSTANCE, scriptText);
            if (!(created instanceof JSFile jsFile)) {
                return null;
            }
            psiFile = jsFile;
            synthetic = true;
        } else {
            PsiFile physical = PsiManager.getInstance(project).findFile(target);
            if (physical instanceof JSFile jsFile) {
                hostFile = physical;
                psiFile = jsFile;
                scriptText = jsFile.getText();
            } else {
                scriptText = text;
                PsiFile created = PsiFileFactory.getInstance(project)
                        .createFileFromText(target.getName() + ".js", JavaScriptFileType.INSTANCE, scriptText);
                if (!(created instanceof JSFile jsFile)) {
                    return null;
                }
                psiFile = jsFile;
                synthetic = true;
            }
            scriptStartOffset = 0;
            lineOffset = 0;
        }
        Map<String, Module> modules = modules(project);
        String key = target.getPath();
        Module cached = modules.get(key);
        if (cached != null && cached.scriptText.equals(scriptText)
                && cached.psiFile.isValid()
                && (cached.hostFile == null || cached.hostFile.isValid())
                && cached.scriptStartOffset == scriptStartOffset
                && cached.lineOffset == lineOffset) {
            return cached;
        }
        Map<String, PsiElement> declarations = new HashMap<>();
        for (JSFunction function : PsiTreeUtil.findChildrenOfType(psiFile, JSFunction.class)) {
            String name = function.getName();
            if (name != null && !name.isEmpty() && !isNested(function)) {
                declarations.putIfAbsent(name, function);
            }
        }
        for (JSVariable variable : PsiTreeUtil.findChildrenOfType(psiFile, JSVariable.class)) {
            String name = variable.getName();
            if (name != null && !name.isEmpty() && !isNested(variable)) {
                declarations.putIfAbsent(name, variable);
            }
        }
        ES6ExportDefaultAssignment export =
                PsiTreeUtil.findChildOfType(psiFile, ES6ExportDefaultAssignment.class);
        JSExpression defaultExport = export == null ? null : export.getExpression();
        Module built = new Module(project, target, hostFile, target.getName(), scriptStartOffset, lineOffset,
                scriptText, psiFile, synthetic, declarations, imports(scriptText), defaultExport);
        modules.put(key, built);
        return built;
    }

    private static Map<String, Module> modules(Project project) {
        Map<String, Module> modules = project.getUserData(MODULES);
        if (modules == null) {
            modules = new ConcurrentHashMap<>();
            project.putUserData(MODULES, modules);
        }
        return modules;
    }

    private static boolean isNested(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, JSFunction.class, true) != null
                || PsiTreeUtil.getParentOfType(element, JSProperty.class, true) != null
                || PsiTreeUtil.getParentOfType(element, JSObjectLiteralExpression.class, true) != null
                || PsiTreeUtil.getParentOfType(element, JSBlockStatement.class, true) != null
                || PsiTreeUtil.getParentOfType(element, JSForInStatement.class, true) != null;
    }

    private static Map<String, ImportRef> imports(String scriptText) {
        Map<String, ImportRef> imports = new HashMap<>();
        for (String raw : scriptText.split("\n", -1)) {
            String line = stripCr(raw);
            Matcher named = IMPORT_NAMED.matcher(line);
            if (named.find()) {
                addNamedImports(imports, named.group(1), named.group(2));
                continue;
            }
            Matcher def = IMPORT_DEFAULT.matcher(line);
            if (def.find()) {
                imports.putIfAbsent(def.group(1), new ImportRef(def.group(3), null, true));
                if (def.group(2) != null) {
                    addNamedImports(imports, def.group(2), def.group(3));
                }
            }
        }
        return imports;
    }

    private static void addNamedImports(Map<String, ImportRef> imports, String list, String target) {
        for (String item : list.split(",")) {
            Matcher specifier = IMPORT_SPECIFIER.matcher(item.trim());
            if (specifier.find()) {
                String imported = specifier.group(1);
                String local = specifier.group(2) == null ? imported : specifier.group(2);
                imports.putIfAbsent(local, new ImportRef(target, imported, false));
            }
        }
    }

    private static int[] scriptRange(String text) {
        String[] lines = text.split("\n", -1);
        int offset = 0;
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            if (start < 0 && line.trim().startsWith("<script")) {
                int gt = line.indexOf('>');
                if (gt >= 0 && !line.substring(gt + 1).trim().isEmpty()) {
                    start = offset + gt + 1;
                } else {
                    start = offset + line.length() + 1;
                }
            } else if (start >= 0 && line.trim().startsWith("</script")) {
                return new int[]{Math.min(start, offset), offset};
            }
            offset += line.length() + 1;
        }
        return start < 0 ? null : new int[]{Math.min(start, text.length()), text.length()};
    }

    private static @Nullable State templateNameState(@Nullable String name, Context context,
                                                      Set<PsiElement> visited, int depth) {
        if (name == null || name.isEmpty() || depth > MAX_DEPTH) {
            return null;
        }
        for (int i = context.vForScopes.size() - 1; i >= 0; i--) {
            VForScope scope = context.vForScopes.get(i);
            if (scope.alias() != null && scope.alias().equals(name)) {
                State expression = templateChainState(scope.expression(), context, visited, depth + 1);
                return expression instanceof ListState list ? list.item() : null;
            }
        }
        Origem origem = context.origens.get(name);
        return origem == null ? null : resolveOrigem(origem, name, context, visited, depth + 1);
    }

    private static @Nullable State templateChainState(String expression, Context context,
                                                      Set<PsiElement> visited, int depth) {
        if (expression == null || expression.isEmpty() || depth > MAX_DEPTH) {
            return null;
        }
        String[] parts = expression.trim().split("\\.");
        State state = templateNameState(parts[0].trim(), context, visited, depth + 1);
        for (int i = 1; i < parts.length && state != null; i++) {
            state = fieldState(state, parts[i].trim(), context, visited, depth + 1);
        }
        return state;
    }

    private static List<VForScope> vForScopes(PsiFile host, int offset) {
        if (host == null || !host.getName().endsWith(".vue") || offset <= 0) {
            return List.of();
        }
        String text = host.getText();
        int template = text.indexOf("<template");
        if (template < 0 || offset < template) {
            return List.of();
        }
        List<VForScope> stack = new ArrayList<>();
        int limit = Math.min(offset, text.length());
        int position = template;
        while (position < limit) {
            int open = text.indexOf('<', position);
            if (open < 0 || open >= limit) {
                break;
            }
            int mustache = text.indexOf("{{", position);
            if (mustache >= 0 && mustache < open) {
                int end = text.indexOf("}}", mustache + 2);
                if (end < 0) {
                    break;
                }
                position = end + 2;
                continue;
            }
            if (open + 1 < text.length()) {
                char next = text.charAt(open + 1);
                if (!Character.isLetter(next) && next != '/' && next != '!' && next != '?') {
                    position = open + 1;
                    continue;
                }
            }
            if (text.startsWith("<!--", open)) {
                int close = text.indexOf("-->", open + 4);
                if (close < 0) {
                    break;
                }
                position = close + 3;
                continue;
            }
            if (text.startsWith("</", open)) {
                int close = text.indexOf('>', open + 2);
                if (close < 0) {
                    break;
                }
                String name = text.substring(open + 2, close).trim();
                for (int i = stack.size() - 1; i >= 0; i--) {
                    if (stack.get(i).tag().equalsIgnoreCase(name)) {
                        stack.subList(i, stack.size()).clear();
                        break;
                    }
                }
                position = close + 1;
                continue;
            }
            if (text.startsWith("<!", open) || text.startsWith("<?", open)) {
                int close = text.indexOf('>', open + 2);
                if (close < 0) {
                    break;
                }
                position = close + 1;
                continue;
            }
            int close = tagEnd(text, open + 1);
            if (close < 0) {
                break;
            }
            String tagText = text.substring(open + 1, close);
            String tag = tagName(tagText);
            boolean selfClosing = tagText.trim().endsWith("/") || VOID_TAGS.contains(tag.toLowerCase());
            if (!selfClosing && tag != null) {
                VForScope scope = vForScope(tag, tagText);
                stack.add(scope);
            } else if (tag != null) {
                // no-op: self-closing or void tag
            }
            position = close + 1;
        }
        List<VForScope> scopes = new ArrayList<>();
        for (VForScope scope : stack) {
            if (scope.alias() != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private static VForScope vForScope(String tag, String tagText) {
        Matcher attribute = V_FOR_ATTR.matcher(tagText);
        if (!attribute.find()) {
            return new VForScope(tag, null, null);
        }
        String value = attribute.group(1) != null ? attribute.group(1) : attribute.group(2);
        if (value == null) {
            return new VForScope(tag, null, null);
        }
        Matcher vFor = V_FOR.matcher(value.trim());
        if (!vFor.find()) {
            return new VForScope(tag, null, null);
        }
        return new VForScope(tag, vFor.group(1), vFor.group(2).trim());
    }

    private static int tagEnd(String text, int from) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable String tagName(String tagText) {
        String trimmed = tagText.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("/")) {
            return null;
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))
                && trimmed.charAt(end) != '/') {
            end++;
        }
        return end == 0 ? null : trimmed.substring(0, end);
    }

    private static @Nullable String methodName(JSExpression method) {
        if (method instanceof JSReferenceExpression reference) {
            return reference.getReferenceName();
        }
        return method == null ? null : lastSegment(method.getText().trim());
    }

    private static @Nullable PsiElement methodQualifier(JSExpression method) {
        return method instanceof JSReferenceExpression reference ? reference.getQualifier() : null;
    }

    private static String lastSegment(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static String stripCall(String value) {
        return value.endsWith("()") ? value.substring(0, value.length() - 2) : value;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
