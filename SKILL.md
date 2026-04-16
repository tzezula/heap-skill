---
name: heap-cli
description: Analyze Java heap dumps with the `heap-cli` command. First try `heap-cli` from `$PATH`; if unavailable, clone `git@github.com:tzezula/heap-skill.git`, build it, and use the executable from `<project_home>/target`.
---

# heap-cli

Use this skill when working with Java heap dumps and memory-leak analysis.

This repository provides a CLI named `heap-cli` built on the VisualVM JFluid heap library. The preferred way to use it is as a GraalVM native executable because startup time is much shorter than the jar form.

## Trigger Heuristics

Use this skill when the user asks to:

- inspect a Java heap dump (`.hprof`)
- find large or suspicious object graphs
- identify likely memory leaks
- explain why an object is still retained
- analyze GC roots or referrers
- list heap types, instances, or retained-size suspects

## Preconditions

1. Confirm the heap dump path from the user.
2. First try `heap-cli` from `$PATH`.
   - `command -v heap-cli`
3. If `heap-cli` is available on `$PATH`, use it directly.
4. If `heap-cli` is not available on `$PATH`, clone the project to a local `<project_home>` directory:
   - `git clone git@github.com:tzezula/heap-skill.git <project_home>`
5. Build the native executable from `<project_home>`:
   - `cd <project_home> && mvn -Pnative package`
6. Use the built executable from the project target directory:
   - `<project_home>/target/heap-cli`
7. Fallback to the jar only if the native executable is unavailable:
   - `java -jar <project_home>/target/heap-cli-1.0-jar-with-dependencies.jar ...`

## Preferred Invocation

If `heap-cli` is on `$PATH`, use:

```bash
heap-cli <heap-dump.hprof> <command> ...
```

If you had to build the project locally, use:

```bash
<project_home>/target/heap-cli <heap-dump.hprof> <command> ...
```

Alternative form:

```bash
heap-cli --heap <heap-dump.hprof> <command> ...
```

Jar fallback:

```bash
java -jar <project_home>/target/heap-cli-1.0-jar-with-dependencies.jar <heap-dump.hprof> <command> ...
```

## Core Workflow

For possible memory leaks, use this workflow in order:

1. **Find suspicious classes**
   - `types-retained` to find classes dominating retained memory
   - `types` to find explosions in object count
2. **Find dominating objects**
   - `biggest` to locate the largest retained objects
3. **Drill into suspect types**
   - `instances <type>` to list individual objects
4. **Inspect object contents**
   - `inspect <instance-id>` to see fields, sizes, and array previews
5. **Explain reachability**
   - `refs-in <instance-id>` for referrers
   - `refs-out <instance-id>` for outgoing references
6. **Explain retention roots**
   - `gcroot <instance-id>` for the shortest path
   - `gcroots <instance-id>` when one path is not enough

## Command Reference

### Count-oriented type listing

```bash
heap-cli dump.hprof types [--filter <text>] [start] [count]
```

Use for:
- identifying unusually common classes
- spotting runaway collections, wrappers, strings, AST nodes, etc.

### Retained-size type listing

```bash
heap-cli dump.hprof types-retained [--filter <text>] [start] [count]
```

Use for:
- finding classes that dominate retained heap
- prioritizing leak suspects

### Biggest retained objects

```bash
heap-cli dump.hprof biggest [start] [count]
```

Use for:
- quickly locating a few objects retaining the most memory

### Instances of one type

```bash
heap-cli dump.hprof instances <type> [start] [count]
```

Use for:
- enumerating candidate leaking instances
- selecting a concrete `instance-id` for deeper inspection

### Inspect one object

```bash
heap-cli dump.hprof inspect <instance-id>
```

Use for:
- checking class, shallow size, retained size
- seeing field values and referenced objects
- previewing array contents

### Incoming references

```bash
heap-cli dump.hprof refs-in <instance-id> [start] [count]
```

Use for:
- identifying who retains the object
- detecting caches, static registries, thread locals, listeners, maps, and arrays

### Outgoing references

```bash
heap-cli dump.hprof refs-out <instance-id> [start] [count]
```

Use for:
- understanding what the object itself retains
- checking if a small object anchors a much larger graph

### Single GC root path

```bash
heap-cli dump.hprof gcroot <instance-id>
```

Use for:
- the shortest explanation of why an object is alive

### Multiple GC root paths

```bash
heap-cli dump.hprof gcroots <instance-id> [count]
```

Use for:
- cases where one shortest path is incomplete or misleading
- comparing alternate retaining roots

## Practical Leak Recipes

### Recipe: “Find what dominates memory”

```bash
heap-cli dump.hprof types-retained 0 30
heap-cli dump.hprof biggest 0 30
```

Then inspect the top few suspects.

### Recipe: “This type seems to leak”

```bash
heap-cli dump.hprof instances com.example.MyType 0 20
heap-cli dump.hprof inspect <instance-id>
heap-cli dump.hprof refs-in <instance-id> 0 20
heap-cli dump.hprof gcroots <instance-id> 5
```

### Recipe: “Large string / map / list growth”

```bash
heap-cli dump.hprof types --filter String 0 20
heap-cli dump.hprof types-retained --filter HashMap 0 20
heap-cli dump.hprof types-retained --filter ArrayList 0 20
heap-cli dump.hprof biggest 0 20
```

### Recipe: “Explain a suspected leak clearly”

For a chosen object:

1. `inspect <id>`
2. `refs-in <id>`
3. `refs-out <id>`
4. `gcroot <id>` or `gcroots <id>`

Then summarize:
- what the object is
- how large it is
- who retains it
- what root ultimately keeps it alive
- why that looks expected vs suspicious

## Interpretation Hints

Common suspicious patterns:

- large retained sizes rooted from static fields
- unbounded maps/lists/sets
- thread-local retention on long-lived threads
- listener registries never cleaned up
- classloader retention
- repeated domain objects with identical retention chains
- one small manager object retaining a large subgraph

Be careful with:

- framework singletons that are intentionally long-lived
- interned strings
- compiler/runtime metadata expected to stay alive
- caches that are large but intentionally configured

## Guardrails

- Prefer `types-retained` and `biggest` over raw count-only views for leak hunting.
- Do not conclude “memory leak” from one GC root path alone; inspect referrers and object contents too.
- Explain whether the retention looks expected, suspicious, or clearly wrong.
- When possible, give the user a concrete retention narrative, not just raw command output.
- Try `heap-cli` from `$PATH` first.
- If `heap-cli` is not on `$PATH`, clone and build the project, then use `<project_home>/target/heap-cli`.
- If native executable is available, prefer it over the jar because startup time matters for iterative analysis.

## Examples

### Example: first-pass leak triage

```bash
heap-cli app.hprof types-retained 0 20
heap-cli app.hprof biggest 0 20
```

### Example: investigate one suspicious type

```bash
heap-cli app.hprof instances com.example.CacheEntry 0 20
heap-cli app.hprof inspect 12345678
heap-cli app.hprof refs-in 12345678 0 20
heap-cli app.hprof gcroots 12345678 5
```

### Example: count-oriented scan for string growth

```bash
heap-cli app.hprof types --filter String 0 20
```

## References

- Project repository: `git@github.com:tzezula/heap-skill.git`
- Project documentation: `README.md`
- Main entrypoint: `src/main/java/org/tzezula/heapcli/Main.java`
- Heap analysis implementation: `src/main/java/org/tzezula/heapcli/HeapExplorer.java`
