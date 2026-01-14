# ParseRIP: Multiview Program-Graph Construction and Static Evidence Extraction (CFG/DFG + RIP-Structured Input)

Anonymous artifact link:  
**https://anonymous.4open.science/r/ParseRIP-8B58/**

This repository provides a prototype toolchain that aligns **source-level differences (original p vs. mutant m)** to **Jimple IR**, and extracts static evidence around mutation-relevant paths (CFG/DFG/path predicates/control dependencies/propagation and kill information, etc.). It ultimately produces a **unified structured JSON input** that can be directly consumed by different types of LLMs. Optionally, it can also generate `.dot/.jpg` visualizations of AST/CFG/DFG to help reviewers quickly sanity-check the results.

---

## 1. What can be reproduced?

Given a pair of program versions (p and m), the toolchain outputs:

- `graph/output.json`: a unified structured evidence file (top-level includes operator/diff/assumptions/sinks/jimpleChanges; and provides origin vs. mutated fields such as content/IR/affected/paths/cpg)
- Optional visualization artifacts (for sanity checks):
  - `IR.jimple`
  - `AST.txt / AST.dot`
  - `CFG.txt / CFG.dot`
  - `DFG.txt / DFG.dot`
  - If Graphviz is installed: `.dot` files are automatically converted into `.jpg`

---

## 2. Why are there many independently runnable `main()` files?

Many Java files in this repo contain `public static void main(String[] args)`. These `main()` methods are **not duplicate entry points**, but rather **stage-specific runnable drivers** that allow you to validate/debug/reproduce the pipeline step by step:

1. **Stage-by-stage reproduction**: reviewers can verify diff → mapping → graphs → evidence → JSON incrementally.
2. **Fast fault isolation**: when something fails, you can quickly localize it to the diff layer, Soot layer, path/graph layer, or output assembly layer.
3. **Run only what you need**: e.g., only compute diff line ranges, only extract method text, or only run batch generation.

> Recommended reviewer workflow: **first run a single-pair pipeline**, then run the **batch pipeline**.

---

## 3. Main runnable entry points and what they do

> Reviewers can also search for `public static void main` in the IDE to list all runnable drivers. Below are the most important and commonly used ones.

### 3.1 `org.rip.RipParser`

**Purpose**: End-to-end analysis of one (p,m) pair. Produces `graph/output.json` and optionally AST/CFG/DFG/IR artifacts.

**How to use**:

- Option A: Edit the demo constants in `RipParser` (p/m source paths, class names, method signature, class output dirs), then run `main`.
- Option B: Use `new RipParser(MutationConfig)` (used by batch/programmable pipelines; see Section 6).

**Outputs**:

- `<mutant_dir>/graph/output.json` (core)
- `<mutant_dir>/graph/*.dot/*.txt` (optional)
- `<mutant_dir>/graph/*.jpg` (optional, requires Graphviz)

---

### 3.2 `org.DataGenerator`

**Purpose**: Reads mutant configurations row-by-row from an Excel file, builds `MutationConfig`, and invokes `RipParser` to generate `output.json` in batch.

**How to use**:

1. Prepare the Excel file (column definitions in Section 6).
2. Set the Excel path in `DataGenerator.main()`.
3. Run `main`.

**Outputs**:

- For each mutant directory: `graph/output.json` and related artifacts
- Logs: `logs/<excel-filename>.log`

---

### 3.3 `org.astjimple.DiffWithLineRanges`

**Purpose**: Validate whether the source-level changes between p and m are correctly localized. This output is also used to map changes to Jimple/CFG/DFG later.

**How to use**:

- Set the demo inputs: `SRC_FILE_P / SRC_FILE_M`
- Run `main` and inspect line ranges and snippets for each change action

**Output**: Printed changes in console (p/m side, action type, line range, node summary/snippet).

---

### 3.4 `org.astjimple.MethodContent`

**Purpose**: Extracts the target method from Java source, removes comments, and outputs a one-line/single-block string (typically used as the JSON `content` field).

**How to use**:

- Set `SRC_FILE_M / CLASS_NAME / METHOD_SIGNATURE`
- Run `main`

**Output**: Prints (or writes, if configured) the extracted method string.

---

### 3.5 `org.astjimple.AstToJimpleBridge`

**Purpose**: Validate the Soot/Jimple alignment and analysis in a more “low-level” manner than `RipParser` (useful for debugging Soot/path/graph issues).

**How to use**:

- Configure p/m source paths, class output directories, class names, and method signature
- Run `main`

**Output**: Console prints affected Jimple units, paths through the affected point, CFG edges, and DFG edges.

---

### 3.6 `org.utils.DotToImageConverter`

**Purpose**: Batch-convert `graph/*.dot` to images so reviewers can open and inspect them directly.

**How to use**:

- Install Graphviz and ensure `dot` is available (e.g., `dot -V`)
- Run `main` (or rely on `RipParser` to invoke it automatically)

**Output**: `.jpg` files generated in the same directory.

---

## 4. Requirements

### Required

- JDK 11+ (JDK 17 recommended)
- Maven 3.8+

### Optional (strongly recommended)

- Graphviz (for dot → jpg visualization)

---
