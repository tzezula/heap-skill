# heap-skill

CLI for exploring Java heap dumps with the VisualVM JFluid heap library.

## Build

### Preferred: native executable

The preferred way to build and use this tool is as a GraalVM native executable, because heap-dump analysis is often run as a short-lived CLI command and native-image gives much faster startup time.

Build the native executable with the Maven `native` profile:

```bash
mvn -Pnative package
```

Native artifact:

- `target/heap-cli`

Run it directly:

```bash
target/heap-cli help
```

### Alternative: runnable jar

A regular jar build is still available:

```bash
mvn package
```

Jar artifacts:

- `target/heap-skill-1.0.jar`
- `target/heap-skill-1.0-jar-with-dependencies.jar`

Runnable jar:

```bash
java -jar target/heap-skill-1.0-jar-with-dependencies.jar help
```

## Usage

Preferred executable form:

```text
heap-cli <heap-dump.hprof> types [--filter <text>] [start] [count]
heap-cli <heap-dump.hprof> types-retained [--filter <text>] [start] [count]
heap-cli <heap-dump.hprof> biggest [start] [count]
heap-cli <heap-dump.hprof> instances <type> [start] [count]
heap-cli <heap-dump.hprof> inspect <instance-id>
heap-cli <heap-dump.hprof> refs-in <instance-id> [start] [count]
heap-cli <heap-dump.hprof> refs-out <instance-id> [start] [count]
heap-cli <heap-dump.hprof> gcroot <instance-id>
heap-cli <heap-dump.hprof> gcroots <instance-id> [count]
```

Alternative form:

```text
heap-cli --heap <heap-dump.hprof> <command> ...
```

If you are using the jar build, the same commands can be run via:

```bash
java -jar target/heap-skill-1.0-jar-with-dependencies.jar <heap-dump.hprof> <command> ...
```

Defaults:

- `start = 0`
- `count = 20`
- `gcroots count = 5`

## Commands

### `types [--filter <text>] [start] [count]`

Prints Java types ordered by instance count.

Columns:

- `NAME`
- `COUNT`
- `SIZE` (human-readable)
- `TOTAL_SIZE` (human-readable)

### `types-retained [--filter <text>] [start] [count]`

Prints Java types ordered by retained size.

Columns:

- `NAME`
- `COUNT`
- `SIZE`
- `TOTAL_SIZE`
- `RETAINED_SIZE`

### `biggest [start] [count]`

Prints biggest objects by retained size.

Columns:

- `INSTANCE_ID`
- `CLASS`
- `SIZE`
- `RETAINED_SIZE`

### `instances <type> [start] [count]`

Prints instances of a given type.

Columns:

- `INSTANCE_ID`
- `SIZE`
- `RETAINED_SIZE`

### `inspect <instance-id>`

Prints detailed information about one instance, including:

- class
- shallow size
- retained size
- nearest GC root pointer
- incoming/outgoing reference counts
- fields or array preview

### `refs-in <instance-id> [start] [count]`

Prints referrers of an instance.

Columns:

- `REFERRER_ID`
- `REFERRER_CLASS`
- `VIA`
- `SIZE`
- `RETAINED_SIZE`

### `refs-out <instance-id> [start] [count]`

Prints outgoing object references from an instance.

Columns:

- `TARGET_ID`
- `TARGET_CLASS`
- `VIA`
- `SIZE`
- `RETAINED_SIZE`

### `gcroot <instance-id>`

Prints the shortest GC root path in the same style as `GCUtils.HeapDumpAnalyser`.

### `gcroots <instance-id> [count]`

Prints several shortest GC root paths, useful when one shortest path is not enough to explain retention.

## Examples

Preferred native executable:

```bash
target/heap-cli mydump.hprof types --filter String 0 20
target/heap-cli mydump.hprof types-retained --filter cache 0 20
target/heap-cli mydump.hprof biggest 0 20
target/heap-cli mydump.hprof inspect 12345678
target/heap-cli mydump.hprof refs-in 12345678 0 20
target/heap-cli mydump.hprof refs-out 12345678 0 20
target/heap-cli mydump.hprof gcroot 12345678
target/heap-cli mydump.hprof gcroots 12345678 5
```

Jar fallback:

```bash
java -jar target/heap-skill-1.0-jar-with-dependencies.jar mydump.hprof types --filter String 0 20
```

## Leak-analysis workflow

A practical manual workflow is usually:

1. `types-retained` to find suspicious classes.
2. `biggest` to find dominating individual objects.
3. `instances <type>` to drill into a suspect type.
4. `inspect <id>` to understand object contents.
5. `refs-in <id>` and `refs-out <id>` to explain retention.
6. `gcroot` or `gcroots` to identify the retaining root path.

## Install as a pi skill

This repository includes a project-local skill file:

- `SKILL.md`

To install it into your global pi skills directory, copy it to:

- `~/.pi/agent/skills/pi-skills/heap-cli/SKILL.md`

Example:

```bash
mkdir -p ~/.pi/agent/skills/pi-skills/heap-cli
cp SKILL.md ~/.pi/agent/skills/pi-skills/heap-cli/SKILL.md
```

After copying it, you can reference the skill from your pi agent setup like any other skill.

## Tests

Run:

```bash
mvn test
```

The smoke test creates a temporary heap dump and exercises the main analysis commands.
