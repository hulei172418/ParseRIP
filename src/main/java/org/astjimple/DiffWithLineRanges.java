package org.astjimple;

import com.github.gumtreediff.actions.ActionGenerator;
import com.github.gumtreediff.actions.model.Action;
import com.github.gumtreediff.actions.model.Insert;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.optimal.zs.ZsMatcher;
import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.TreeContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * ------- Line number differences between original and mutant programs ------
 * ---------- GumTree section: Change → Line Number
 */

public class DiffWithLineRanges {
    public static List<ChangeRange> diffWithLineRanges(String fileP, String fileM) throws Exception {
        TreeContext c1 = new JdtTreeGenerator().generateFromFile(new File(fileP));
        TreeContext c2 = new JdtTreeGenerator().generateFromFile(new File(fileM));
        Tree src = (Tree) c1.getRoot();
        Tree dst = (Tree) c2.getRoot();

        // Use the more accurate ZsMatcher for matching
        MappingStore store = new MappingStore();
        new ZsMatcher(src, dst, store).match(); // Use ZsMatcher for precise tree matching

        ActionGenerator gen = new ActionGenerator(src, dst, store);
        List<Action> actions = gen.generate();

        String textP = readUtf8(fileP);
        String textM = readUtf8(fileM);
        int[] lineStartP = lineStartOffsets(textP);
        int[] lineStartM = lineStartOffsets(textM);

        List<ChangeRange> ranges = new ArrayList<>();
        for (Action a : actions) {
            ITree n = a.getNode();
            int pos = n.getPos(), len = n.getLength();
            if (pos < 0 || len < 0)
                continue; // Some syntax nodes may not have position information
            boolean onSrcSide = !(a instanceof Insert); // Insert applies to dst; others default to src
            int start, end;
            if (onSrcSide) {
                start = offsetToLine(pos, lineStartP);
                end = offsetToLine(Math.max(pos + len - 1, pos), lineStartP);
            } else {
                start = offsetToLine(pos, lineStartM);
                end = offsetToLine(Math.max(pos + len - 1, pos), lineStartM);
            }
            String kind = a.getClass().getSimpleName();
            String where = n.getLabel();
            String snippet = safeSnippet(onSrcSide ? textP : textM, pos, len); // Extract the changed code snippet
            ranges.add(new ChangeRange(kind, onSrcSide, start, end, where + " :: " + snippet));
        }

        // Directly return changes without merging adjacent line numbers
        return ranges;
    }

    /**
     * Compute the starting offset of each line (used for binary search from offset
     * to line number)
     */
    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n')
                starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Get the line number corresponding to a character offset */
    private static int offsetToLine(int offset, int[] lineStarts) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        if (idx >= 0)
            return idx + 1;
        int ins = -idx - 1;
        return Math.max(1, ins); // 1-based line number
    }

    /** Extract the code snippet text content */
    private static String safeSnippet(String s, int pos, int len) {
        int trimmedLen = 1024;
        int a = Math.max(0, pos), b = Math.min(s.length(), pos + len);
        String t = s.substring(a, b).replace("\n", "⏎").trim();
        return t.length() > trimmedLen ? t.substring(0, trimmedLen) + "…" : t;
    }

    // Read file as UTF-8 string
    private static String readUtf8(String file) throws Exception {
        return new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String mID = "m4"; // Mutant ID
        String fileP = "src/main/java/demo/origin/ConstantPoolEntry.java"; // Original program path
        String fileM = "src/main/java/demo/" + mID + "/ConstantPoolEntry.java"; // Mutant path
        List<ChangeRange> changes = diffWithLineRanges(fileP, fileM);

        System.out.println("=== AST Changes (with Line Numbers) ===");
        changes.forEach(System.out::println);
    }
}
