package org.astjimple;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.type.Type;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MethodContent {
        private static final String mID = "m4"; // Mutant ID
        // Source code of mutant m
        private static final String SRC_FILE_M = "src/main/java/demo/" + mID + "/ConstantPoolEntry.java";
        // Fully-qualified class name of m (usually same)
        private static final String CLASS_NAME_M = "ConstantPoolEntry";
        // Only analyze methods whose names contain this substring
        private static final String METHOD_NAME_SUBSTR = "boolean_PoolEntry(int,int)";

        public static void main(String[] args) throws Exception {
                String className = CLASS_NAME_M; // 如: Demo 或 Outer.Inner.MoreInner
                String methodName = METHOD_NAME_SUBSTR;
                boolean includeSignature = Boolean.parseBoolean("true");
                String oneLine = extractMethodAsOneLine(SRC_FILE_M, className, methodName, includeSignature);
                System.out.println(oneLine);
        }

        public static void test() throws Exception {
                String className = CLASS_NAME_M; // e.g., Demo or Outer.Inner.MoreInner
                String methodName = "PoolEntry";
                boolean includeSignature = Boolean.parseBoolean("true");
                String paramsStr = "int, int"; // Comma-separated parameter type list; empty means ignore overloads
                List<String> wantedParams = Arrays.stream(paramsStr.split(",")).map(String::trim)
                                .collect(Collectors.toList());
                String oneLine1 = extractMethodAsOneLine1(SRC_FILE_M, className, methodName,
                                includeSignature,
                                wantedParams);
                System.out.println(oneLine1);
        }

        public static String extractMethodAsOneLine(
                        String javaFile,
                        String classPathOrNull, // Can be null; if set, only search within this class; supports
                                                // "Outer.Inner"
                        String sootSignature, // Like "boolean_PoolEntry(int,int)"
                        boolean includeSignature // true=include signature; false=method body only
        ) throws Exception {
                Path file = Paths.get(javaFile);

                ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
                JavaParser parser = new JavaParser(cfg);
                var result = parser.parse(file);
                if (!result.getResult().isPresent()) {
                        throw new Exception("Parse failed: " + result.getProblems());
                }
                CompilationUnit cu = result.getResult().get();

                // Remove all comments to avoid including them in output
                cu.getAllContainedComments().forEach(Comment::remove);

                Optional<MethodDeclaration> mdOpt = findMethodBySootLikeSignature(cu, sootSignature, classPathOrNull);
                if (mdOpt.isEmpty()) {
                        throw new IllegalArgumentException("No method matched soot signature: " + sootSignature
                                        + (classPathOrNull == null ? "" : (" within class " + classPathOrNull)));
                }
                MethodDeclaration md = mdOpt.get();

                String code = includeSignature
                                ? md.toString() // No comments remaining
                                : md.getBody().map(Object::toString).orElse(""); // Method body only
                // return code.replaceAll("\\s+", " ").trim(); // Flatten to single line
                return code;
        }

        /**
         * Find method by Soot-style signature in CompilationUnit; can restrict to
         * specific class including inner paths
         */
        public static Optional<MethodDeclaration> findMethodBySootLikeSignature(CompilationUnit cu,
                        String sootSig,
                        String classPathOrNull) {
                Sig s = parseSootSig(sootSig); // returnType, name, params

                // Choose search scope: specific class or whole file
                Collection<MethodDeclaration> candidates;
                if (classPathOrNull != null && !classPathOrNull.isBlank()) {
                        Optional<TypeDeclaration<?>> typeOpt = findTypeByPath(cu, classPathOrNull);
                        if (typeOpt.isEmpty())
                                return Optional.empty();
                        candidates = typeOpt.get().findAll(MethodDeclaration.class);
                } else {
                        candidates = cu.findAll(MethodDeclaration.class);
                }

                return candidates.stream()
                                .filter(md -> md.getNameAsString().equals(s.name))
                                .filter(md -> md.getParameters().size() == s.paramTypes.size())
                                .filter(md -> {
                                        // Compare parameters item by item (standardized as comparable strings)
                                        List<String> mdParams = md.getParameters().stream()
                                                        .map(p -> canonicalFromRawTypeString(p.getType().asString(),
                                                                        p.isVarArgs()))
                                                        .collect(Collectors.toList());
                                        return typesListEquals(mdParams, s.paramTypes);
                                })
                                .filter(md -> {
                                        // Return type comparison
                                        String mdRet = canonicalFromRawTypeString(md.getType().asString(), false);
                                        return mdRet.equals(s.returnType);
                                })
                                .findFirst();
        }

        /*
         * === Signature Parsing, Normalization & Utilities ===
         */

        /** Parse "ReturnType_MethodName(T1,T2,...)" -> Sig */
        private static Sig parseSootSig(String sig) {
                int us = sig.indexOf('_');
                int lp = sig.indexOf('(');
                int rp = sig.lastIndexOf(')');
                if (us <= 0 || lp < us || rp < lp) {
                        throw new IllegalArgumentException("Bad soot-like signature: " + sig);
                }
                String returnType = canonicalFromSignaturePart(sig.substring(0, us));
                String name = sig.substring(us + 1, lp).trim();
                String inside = sig.substring(lp + 1, rp).trim();
                List<String> params = inside.isEmpty()
                                ? List.of()
                                : Arrays.stream(inside.split(","))
                                                .map(MethodContent::canonicalFromSignaturePart)
                                                .collect(Collectors.toList());
                return new Sig(returnType, name, params);
        }

        /**
         * Normalize type in signature: remove annotations/generics/whitespace; varargs
         * -> []; keep array notation []
         */
        private static String canonicalFromSignaturePart(String raw) {
                String t = stripTypeAnnotations(raw);
                t = stripGenerics(t);
                t = t.replaceAll("\\s+", " ").trim();
                t = t.replace("...", "[]"); // 兜底
                return t;
        }

        /**
         * Normalize raw type string from AST (no symbol resolution, no classpath
         * dependency)
         */
        private static String canonicalFromRawTypeString(String raw, boolean isVarArgs) {
                String t = stripTypeAnnotations(raw);
                t = stripGenerics(t);
                t = t.replaceAll("\\s+", " ").trim();
                if (isVarArgs && !t.endsWith("[]")) {
                        t = t.replace("...", "");
                        t = t + "[]";
                }
                // Extract and restore array dimensions to ensure consistency
                int dims = 0;
                while (t.endsWith("[]")) {
                        dims++;
                        t = t.substring(0, t.length() - 2);
                }
                StringBuilder sb = new StringBuilder(t);
                for (int i = 0; i < dims; i++)
                        sb.append("[]");
                return sb.toString();
        }

        private static boolean typesListEquals(List<String> a, List<String> b) {
                if (a.size() != b.size())
                        return false;
                for (int i = 0; i < a.size(); i++)
                        if (!a.get(i).equals(b.get(i)))
                                return false;
                return true;
        }

        /** Remove all generic angle-bracket content (supports nested) */
        private static String stripGenerics(String type) {
                StringBuilder out = new StringBuilder();
                int depth = 0;
                for (int i = 0; i < type.length(); i++) {
                        char c = type.charAt(i);
                        if (c == '<') {
                                depth++;
                                continue;
                        }
                        if (c == '>') {
                                depth--;
                                continue;
                        }
                        if (depth == 0)
                                out.append(c);
                }
                return out.toString();
        }

        /** Remove type annotations (e.g., @Nonnull, @A(@B)) */
        private static String stripTypeAnnotations(String type) {
                return type.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        }

        /** Simple data structure to store method signature */
        private static class Sig {
                final String returnType;
                final String name;
                final List<String> paramTypes;

                Sig(String r, String n, List<String> p) {
                        this.returnType = r;
                        this.name = n;
                        this.paramTypes = p;
                }
        }

        public static String extractMethodAsOneLine1(String filePath, String className, String methodName,
                        boolean includeSignature, List<String> wantedParams) throws Exception {
                Path file = Paths.get(filePath);

                ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
                JavaParser parser = new JavaParser(cfg);
                var result = parser.parse(file);

                if (!result.getResult().isPresent()) {
                        throw new Exception("Parse failed: " + result.getProblems());
                }
                CompilationUnit cu = result.getResult().get();

                // Remove all comments
                cu.getAllContainedComments().forEach(Comment::remove);

                // Find target class (supports inner class like Outer.Inner)
                Optional<TypeDeclaration<?>> typeOpt = findTypeByPath(cu, className);
                if (typeOpt.isEmpty()) {
                        throw new Exception("Class not found: " + className);
                }
                TypeDeclaration<?> targetType = typeOpt.get();

                // Find methods in this class (not subclasses; use findAll for inner-class
                // variants)
                List<MethodDeclaration> methods = targetType.getMembers().stream()
                                .filter(m -> m instanceof MethodDeclaration)
                                .map(m -> (MethodDeclaration) m)
                                .filter(md -> md.getNameAsString().equals(methodName))
                                .collect(Collectors.toList());

                if (methods.isEmpty()) {
                        throw new Exception("No method named '" + methodName + "' found in class " + className);
                }

                MethodDeclaration picked = pickOverload(methods, wantedParams)
                                .orElse(null);

                if (picked == null) {
                        throw new Exception("No overload matched for params: "
                                        + (wantedParams == null ? "[]" : wantedParams));
                }

                // Generate single line text
                String code = includeSignature
                                ? picked.toString() // Comments removed
                                : picked.getBody().map(Object::toString).orElse("");
                return code;
        }

        /**
         * Find type in CompilationUnit by class path. Supports dot-separated inner
         * class paths: Outer.Inner.MoreInner
         */
        private static Optional<TypeDeclaration<?>> findTypeByPath(CompilationUnit cu, String classPath) {
                String[] parts = classPath.split("\\.");
                // Start from top-level TypeDeclarations in CU
                List<TypeDeclaration<?>> currentLevel = cu.getTypes();

                TypeDeclaration<?> current = null;
                for (int i = 0; i < parts.length; i++) {
                        String want = parts[i];
                        Optional<TypeDeclaration<?>> next = currentLevel.stream()
                                        .filter(td -> td.getNameAsString().equals(want))
                                        .findFirst();

                        if (next.isEmpty()) {
                                // Looser match: allow single-segment (e.g., "Inner") if uniquely found in file
                                if (parts.length == 1) {
                                        List<TypeDeclaration<?>> allTypes = getAllTypes(cu);
                                        List<TypeDeclaration<?>> hits = allTypes.stream()
                                                        .filter(td -> td.getNameAsString().equals(want))
                                                        .collect(Collectors.toList());
                                        if (hits.size() == 1)
                                                return Optional.of(hits.get(0));
                                }
                                return Optional.empty();
                        }

                        current = next.get();
                        // Next level: go into nested type
                        currentLevel = getNestedTypes(current);
                }
                return Optional.ofNullable(current);
        }

        /** Get all nested types under a node */
        private static List<TypeDeclaration<?>> getNestedTypes(TypeDeclaration<?> td) {
                return td.getMembers().stream()
                                .filter(m -> m instanceof TypeDeclaration<?>)
                                .map(m -> (TypeDeclaration<?>) m)
                                .collect(Collectors.toList());
        }

        /** Get all types in the file (including nested ones) */
        private static List<TypeDeclaration<?>> getAllTypes(CompilationUnit cu) {
                List<TypeDeclaration<?>> out = new ArrayList<>();
                cu.findAll(TypeDeclaration.class).forEach(out::add);
                return out;
        }

        /** Select overload matching parameter list; if none provided, pick first */
        private static Optional<MethodDeclaration> pickOverload(List<MethodDeclaration> methods,
                        List<String> wantedParams) {
                if (wantedParams == null) {
                        return methods.stream().findFirst();
                }
                List<String> wanted = wantedParams.stream().map(
                                MethodContent::normalizeType)
                                .collect(Collectors.toList());
                return methods.stream()
                                .filter(md -> {
                                        List<String> actual = md.getParameters().stream()
                                                        .map(p -> normalizeType(p.getType()))
                                                        .collect(Collectors.toList());
                                        return actual.equals(wanted);
                                })
                                .findFirst();
        }

        public static String getSimpleSignature(MethodDeclaration md) {
                String returnType = md.getType().asString(); // Return type
                String methodName = md.getNameAsString(); // Method name
                String paramTypes = md.getParameters().stream()
                                .map(p -> p.getType().asString())
                                .collect(Collectors.joining(","));
                return returnType + " " + methodName + "(" + paramTypes + ")";
        }

        // ---- Normalization tools: follow JavaParser Type rules ----
        private static String normalizeType(Type t) {
                return t.asString().replaceAll("\\s+", "");
        }

        private static String normalizeType(String s) {
                return s.replaceAll("\\s+", "");
        }
}
