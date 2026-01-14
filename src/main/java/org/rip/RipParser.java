package org.rip;

import java.util.*;
import java.util.stream.Collectors;

import static org.astjimple.MethodContent.*;
import static org.rip.RipExtractor.*;

import org.astjimple.*;
import org.graph.ASTVisualizer;
import org.graph.CFGVisualizer;
import org.graph.DFGVisualizer;
import org.model.Bundle;
import org.model.DFG;
import org.model.Info.InfoItem;
import org.utils.DotToImageConverter;
import org.utils.MutationConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static org.astjimple.AstToJimpleBridge.*;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

import java.io.File;
import java.nio.file.Paths;

public class RipParser {
    // ==== Main input (examples consistent with prompts, can also pass from
    // main) ====
    private String mID = "m6"; // Mutant ID
    private String OID = "p"; // Original ID
    private String Operator = "COR"; // operator
    private String Diff = "bucket256(sample) == (targetBucket & 0xFF) && sample.getKey() < min && sample.getValue() > 0"; // Differing
                                                                                                                          // statement
    private String SRC_FILE_P = "src/main/java/demo/origin/Bucket.java"; // ource code of p
    private String SRC_FILE_M = "src/main/java/demo/" + mID + "/Bucket.java"; // Source code of m

    private String CLASS_NAME_P = "Bucket"; // Fully qualified class name of p (include package if any)
    private String CLASS_NAME_M = "Bucket"; // Fully qualified class name of m (usually same)
    private String METHOD_NAME_SUBSTR = "double_getSupportLowerBound(double,int)"; // Only analyze methods whose names
                                                                                   // contain this
    // substring
    private String CLASSES_DIR_P = "target/classes/demo/" + "origin"; // p's .class directory
    private String CLASSES_DIR_M = "target/classes/demo/" + mID; // m's .class directory

    // Output path for strategy dataset
    private String outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
    private String codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                                    // strategy
    private String zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
    private String fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
    private String fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy

    private List<String> SPEC_OBSERVED = Arrays.asList("return", "exception", "state");
    private List<String> DOMAIN_ASSUMPTIONS = List.of();

    private List<ChangeRange> ranges;

    public RipParser() {
    }

    public RipParser(MutationConfig config) {
        mID = config.operator.replaceAll("\\D+", "");
        OID = "";
        Operator = config.operator.replaceAll("_\\d+$", "");
        Diff = config.mutationStatement;
        SRC_FILE_P = Paths.get(config.filepath).getParent().getParent().getParent().toString() + "/original/"
                + config.classNameF + ".java";
        SRC_FILE_M = config.filepath + "/" + config.className + ".java";
        CLASS_NAME_P = config.classNameF;
        CLASS_NAME_M = config.classNameF;
        METHOD_NAME_SUBSTR = config.methodName;
        String fileDirP = Paths.get(config.filepath).getParent().getParent().getParent().toString();
        CLASSES_DIR_P = fileDirP + "/original/";
        CLASSES_DIR_M = config.filepath + "/";

        // Output path for strategy dataset
        outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
        codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                         // strategy
        zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
        fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
        fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy
    }

    public static void main(String[] args) throws Exception {
        RipParser parser = new RipParser();
        String json = parser.analyzePairToJson();
        System.out.println(json);
    }

    public void test() throws Exception {
        String json = analyzePairToJson();
        System.out.println(json);
    }

    public String analyzePairToJson() throws Exception {
        Bundle bundle = buildCommonInfo();
        buildOriginSide(bundle);
        buildMutantSide(bundle);

        Map<String, Object> out = assembleOutput(bundle);

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(outputPath), out);
        return om.writeValueAsString(out);
    }

    private Bundle buildCommonInfo() throws Exception {
        Bundle bundle = new Bundle();
        bundle.operator.item = Operator;
        bundle.diff.item = Diff;
        bundle.domainAssumptions.items = DOMAIN_ASSUMPTIONS;
        bundle.specObserved.items = SPEC_OBSERVED;

        ranges = DiffWithLineRanges.diffWithLineRanges(SRC_FILE_P, SRC_FILE_M);
        bundle.jimpleChanges.items = ranges.stream().map(ChangeRange::toString).collect(Collectors.toList());

        return bundle;
    }

    private void buildOriginSide(Bundle bundle) throws Exception {
        bundle.origin.item.id.item = OID;
        bundle.origin.item.content.item = extractMethodAsOneLine(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR, true);

        AstToJimpleBridge.initSoot(CLASSES_DIR_P);
        Body body = AstToJimpleBridge.getBody(CLASS_NAME_P, METHOD_NAME_SUBSTR);
        bundle.origin.item.IR.item = body.toString();

        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.origin.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.origin.item.Paths.items = validePaths.stream().map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            InfoItem item = buildInfoItem(path, body, affected);
            bundle.origin.item.CPG.add(item);
        }
        String outputDir = CLASSES_DIR_P + "/" + METHOD_NAME_SUBSTR;
        saveCPG(CLASS_NAME_P, METHOD_NAME_SUBSTR, outputDir);
    }

    private void buildMutantSide(Bundle bundle) throws Exception {
        bundle.mutant.item.id.item = mID;
        bundle.mutant.item.content.item = extractMethodAsOneLine(SRC_FILE_M, CLASS_NAME_M, METHOD_NAME_SUBSTR, true);

        AstToJimpleBridge.initSoot(CLASSES_DIR_M);
        Body body = AstToJimpleBridge.getBody(CLASS_NAME_M, METHOD_NAME_SUBSTR);
        bundle.mutant.item.IR.item = body.toString();

        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.mutant.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.mutant.item.Paths.items = validePaths.stream().map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            InfoItem item = buildInfoItem(path, body, affected);
            bundle.mutant.item.CPG.add(item);
        }
        saveCPG(CLASS_NAME_M, METHOD_NAME_SUBSTR, CLASSES_DIR_M);
    }

    // ===== Construct InfoItem based on affected path (textual CFG/DFG) =====
    private InfoItem buildInfoItem(List<Unit> path, Body body, List<Unit> affectedList) {
        InfoItem item = new InfoItem();
        item.Path = pathToString(path);

        UnitGraph ug = new ExceptionalUnitGraph(body);
        BlockGraph bg = new BriefBlockGraph(body);
        DominatorsFinder<Unit> dom = new MHGDominatorsFinder<>(ug);
        DominatorsFinder<Unit> pdom = new MHGPostDominatorsFinder<>(ug);

        // Aggregate and deduplicate (affected nodes may be >1)
        LinkedHashSet<String> domSummaries = new LinkedHashSet<>();
        LinkedHashSet<String> pathPreds = new LinkedHashSet<>();
        LinkedHashSet<String> ctrlDeps = new LinkedHashSet<>();

        for (Unit affected : affectedList) {
            Unit mutUnit = locateByText(body, affected.toString());
            if (mutUnit == null)
                continue;

            // ---- CFG.dom: Dominator chain summary for basic blocks ----
            domSummaries.addAll(unitsToBlockSummaries(bg, dom.getDominators(mutUnit)));

            // ---- CFG.path_predicates: If conditions along dominator chain (Top-K) ----
            for (Unit u : dom.getDominators(mutUnit)) {
                if (u instanceof IfStmt) {
                    pathPreds.add(((IfStmt) u).getCondition().toString());
                }
            }

            // ---- CFG.control_deps_out: Control dependencies based on post-dominators ----
            ctrlDeps.addAll(computeControlDepsText(bg, pdom, mutUnit));

            // ---- DFG: Build from the affected point ----
            buildDFG(item, mutUnit, ug, pdom, SPEC_OBSERVED);
        }

        // Write back (clip Top-K to avoid excessive length)
        item.CFG.dom.items = new ArrayList<>(domSummaries);
        item.CFG.path_predicates.items = pathPreds.stream().limit(8).collect(Collectors.toList());
        item.CFG.control_deps_out.items = new ArrayList<>(ctrlDeps);

        return item;
    }

    // ===== Build DFG (defs_at_mut / uses_toward_output / kill_set etc.) =====
    private static void buildDFG(InfoItem item, Unit mutUnit, UnitGraph ug,
            DominatorsFinder<Unit> pdom, List<String> specObserved) {

        SimpleLocalDefs sdefs = new SimpleLocalDefs(ug);
        SimpleLocalUses suses = new SimpleLocalUses(ug, sdefs);

        // ---------- 1) Identify true variables carrying Δ (tracked set) ----------
        LinkedHashSet<String> tracked = new LinkedHashSet<>();

        if (mutUnit instanceof AssignStmt) {
            AssignStmt as = (AssignStmt) mutUnit;
            Value lhs = as.getLeftOp();

            if (lhs instanceof Local && !isTemp((Local) lhs)) {
                // Mutation point directly defines a real variable (e.g., numEntries =
                // numEntries + entries)
                DFG.KV def = new DFG.KV();
                def.var = lhs.toString();
                def.unit = mutUnit.toString();
                item.DFG.defs_point.add(def);
                tracked.add(def.var);
            } else {
                // Mutation point defines a temp var (e.g., $stack5 = neg entries), find next
                // real assignment via uses
                for (UnitValueBoxPair use : suses.getUsesOf(mutUnit)) {
                    if (use.unit instanceof AssignStmt) {
                        Value realLhs = ((AssignStmt) use.unit).getLeftOp();
                        if (realLhs instanceof Local && !isTemp((Local) realLhs)) {
                            DFG.KV def = new DFG.KV();
                            def.var = realLhs.toString();
                            def.unit = use.unit.toString();
                            item.DFG.defs_point.add(def);
                            tracked.add(def.var);
                        }
                    }
                }
            }
        }
        if (tracked.isEmpty()) {
            // For pure control flow changes, may consider pseudo-vars; here we return
            // directly to avoid noise
            return;
        }

        // ---------- 2) uses_toward_output: Forward slice until sink ----------
        Set<Unit> visited = new HashSet<>();
        Deque<Unit> work = new ArrayDeque<>();
        work.add(mutUnit);

        while (!work.isEmpty()) {
            Unit u = work.poll();
            if (!visited.add(u))
                continue;
            if (u instanceof IfStmt && specObserved.contains("return")) {
                if (guardsReturn(u, ug, specObserved)) {
                    // Locals used in condition and tracked (carrying Δ) → count as observable usage
                    // at return (via control)
                    for (ValueBox vb : u.getUseBoxes()) {
                        Value v = vb.getValue();
                        if (v instanceof Local) {
                            String name = v.toString();
                            if (tracked.contains(name)) {
                                DFG.SinkUse su = new DFG.SinkUse();
                                su.var = name;
                                su.unit = u.toString();
                                su.sink = "return";
                                item.DFG.uses_toward_output.add(su);
                            }
                        }
                    }
                }
            }

            if (isSink(u, specObserved)) {
                // Only record tracked variable usages at sink
                for (ValueBox vb : u.getUseBoxes()) {
                    Value v = vb.getValue();
                    if (v instanceof Local) {
                        String name = v.toString();
                        if (tracked.contains(name)) {
                            DFG.SinkUse use = new DFG.SinkUse();
                            use.var = name;
                            use.unit = u.toString();
                            use.sink = sinkKind(u);
                            item.DFG.uses_toward_output.add(use);
                        }
                    }
                }
                continue; // Stop expanding branch once sink is reached
            }

            for (UnitValueBoxPair p : suses.getUsesOf(u)) {
                work.add(p.unit);
            }
        }

        // --- 3) kill_set: Overwriting definitions of tracked vars before any sink ---
        LinkedHashSet<String> killSig = new LinkedHashSet<>();
        for (Unit u : ug) {
            if (!(u instanceof AssignStmt))
                continue;
            Value lhs = ((AssignStmt) u).getLeftOp();
            if (!(lhs instanceof Local))
                continue;
            String var = lhs.toString();
            if (isTemp((Local) lhs))
                continue; // Skip temporaries
            if (!tracked.contains(var))
                continue; // Focus only on Δ-carrying vars
            if (u == mutUnit)
                continue; // Don’t count mutation point itself as a kill

            boolean pdByAnySink = postdominatedByAnySink(u, ug, pdom, specObserved);
            String sig = var + "|" + u.toString() + "|" + pdByAnySink;
            if (killSig.add(sig)) {
                DFG.Kill k = new DFG.Kill();
                k.var = var;
                k.unit = u.toString();
                k.postdominated_by_sink = pdByAnySink;
                item.DFG.kill_set.add(k);
            }
        }

        // ---------- 4) Deduplicate (defs / uses) ----------
        dedupDFGLists(item);
    }

    // ====== Utilities ======
    private static boolean isTemp(Local l) {
        // return l.getName().startsWith("$");
        return false; // Not filtering yet; allow tracking temporaries
    }

    private static boolean postdominatedByAnySink(Unit u, UnitGraph ug,
            DominatorsFinder<Unit> pdom,
            List<String> specObserved) {
        for (Unit cand : ug) {
            if (isSink(cand, specObserved)) {
                if (pdom.getDominators(u).contains(cand))
                    return true;
            }
        }
        return false;
    }

    private static boolean isSink(Unit u, List<String> specObserved) {
        if (specObserved.contains("return") && u instanceof ReturnStmt)
            return true;
        if (specObserved.contains("exception") && u instanceof ThrowStmt)
            return true;
        // "state" sink depends on project definition (e.g., external state writes); not
        // handled here yet
        return false;
    }

    private static String sinkKind(Unit u) {
        if (u instanceof ReturnStmt)
            return "return";
        if (u instanceof ThrowStmt)
            return "exception";
        return "state";
    }

    // Whether If guards a return: any successor must reach return sink within
    // bounded steps
    private static boolean guardsReturn(Unit ifUnit, UnitGraph ug, List<String> specObserved) {
        if (!(ifUnit instanceof IfStmt))
            return false;
        // First, check if direct successor is return
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (isSink(s, specObserved))
                return true;
        }
        // Otherwise, do shallow forward search (to avoid full graph cost)
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (reachesSinkWithin(s, ug, specObserved, 12))
                return true; // Step count can be adjusted
        }
        return false;
    }

    private static boolean reachesSinkWithin(Unit start, UnitGraph ug,
            List<String> specObserved, int maxSteps) {
        Set<Unit> seen = new HashSet<>();
        Deque<Unit> q = new ArrayDeque<>();
        q.add(start);
        int steps = 0;
        while (!q.isEmpty() && steps++ < maxSteps) {
            Unit cur = q.poll();
            if (!seen.add(cur))
                continue;
            if (isSink(cur, specObserved))
                return true;
            for (Unit nxt : ug.getSuccsOf(cur))
                q.add(nxt);
        }
        return false;
    }

    private static void dedupDFGLists(InfoItem item) {
        // Deduplicate defs
        LinkedHashSet<String> sig = new LinkedHashSet<>();
        item.DFG.defs_point.items.removeIf(kv -> !sig.add(kv.var + "|" + kv.unit));

        // Deduplicate uses
        sig.clear();
        item.DFG.uses_toward_output.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.sink));

        // Deduplicate kill_set
        sig.clear();
        item.DFG.kill_set.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.postdominated_by_sink));
    }

    private static List<String> formatAffectedUnits(List<Unit> units) {
        return units.stream().map(u -> {
            int[] lr = unitLineRange(u);
            return String.format("JIMPLE [%d-%d] %s%n", lr[0], lr[1], u);
        }).collect(Collectors.toList());
    }

    private static Map<String, Object> assembleOutput(Bundle bundle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", bundle.operator);
        out.put("Diff", bundle.diff);
        out.put("DomainAssumptions", bundle.domainAssumptions);
        out.put("SpecObserved", bundle.specObserved);
        out.put("JimpleChanges", bundle.jimpleChanges);
        out.put("origin", bundle.origin);
        out.put("mutated", bundle.mutant);
        return out;
    }

    public static void saveCPG(String clsName, String methodName, String outputDir) throws Exception {

        // Load target class
        Scene.v().loadNecessaryClasses();
        SootClass sc = Scene.v().forceResolve(clsName, SootClass.BODIES);
        sc.setApplicationClass();
        Scene.v().loadClassAndSupport(clsName);

        // Perform AST analysis
        ASTVisualizer ast = new ASTVisualizer(clsName, methodName, outputDir);
        ast.visualize();

        // Perform CFG analysis
        CFGVisualizer cfg = new CFGVisualizer(clsName, methodName, outputDir);
        cfg.visualize();

        // Perform DFG analysis
        DFGVisualizer dfg = new DFGVisualizer(clsName, methodName, outputDir);
        dfg.analyze();

        // Convert all generated .dot files to images
        DotToImageConverter.convertDotFilesInDirectory(new File(outputDir + "/graph"));
    }

}
