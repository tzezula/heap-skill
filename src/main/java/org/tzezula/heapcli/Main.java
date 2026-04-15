package org.tzezula.heapcli;

import java.io.PrintStream;
import java.nio.file.Path;

public final class Main {

    private static final int DEFAULT_START = 0;
    private static final int DEFAULT_COUNT = 20;
    private static final int DEFAULT_GCROOT_PATH_COUNT = 5;

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            CommandLine commandLine = CommandLine.parse(args);
            if (commandLine.command() == Command.HELP) {
                printUsage(out);
                return 0;
            }

            HeapExplorer explorer = new HeapExplorer(commandLine.heapDump());
            switch (commandLine.command()) {
                case TYPES -> explorer.printTypes(commandLine.typeFilter(), commandLine.start(), commandLine.count(), out);
                case TYPES_RETAINED -> explorer.printTypesRetained(commandLine.typeFilter(), commandLine.start(), commandLine.count(), out);
                case BIGGEST -> explorer.printBiggest(commandLine.start(), commandLine.count(), out);
                case INSTANCES -> explorer.printInstances(commandLine.typeName(), commandLine.start(), commandLine.count(), out);
                case INSPECT -> explorer.printInspect(commandLine.instanceId(), out);
                case REFS_IN -> explorer.printIncomingReferences(commandLine.instanceId(), commandLine.start(), commandLine.count(), out);
                case REFS_OUT -> explorer.printOutgoingReferences(commandLine.instanceId(), commandLine.start(), commandLine.count(), out);
                case GCROOT -> explorer.printGcRoot(commandLine.instanceId(), out);
                case GCROOTS -> explorer.printGcRoots(commandLine.instanceId(), commandLine.count(), out);
                case HELP -> throw new IllegalStateException("Help should have been handled already.");
            }
            return 0;
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            printUsage(err);
            return 2;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  heap-skill <heap-dump.hprof> types [--filter <text>] [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> types-retained [--filter <text>] [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> biggest [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> instances <type> [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> inspect <instance-id>");
        out.println("  heap-skill <heap-dump.hprof> refs-in <instance-id> [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> refs-out <instance-id> [start] [count]");
        out.println("  heap-skill <heap-dump.hprof> gcroot <instance-id>");
        out.println("  heap-skill <heap-dump.hprof> gcroots <instance-id> [count]");
        out.println("  heap-skill --heap <heap-dump.hprof> <command> ...");
        out.println();
        out.println("Commands:");
        out.println("  types                  Print Java types ordered by instance count.");
        out.println("  types-retained         Print Java types ordered by retained size.");
        out.println("  biggest                Print biggest objects by retained size.");
        out.println("  instances <type>       Print instances of the given Java type.");
        out.println("  inspect <instance-id>  Print detailed information about an instance.");
        out.println("  refs-in <instance-id>  Print incoming references to an instance.");
        out.println("  refs-out <instance-id> Print outgoing references from an instance.");
        out.println("  gcroot <instance-id>   Print the shortest path to a GC root.");
        out.println("  gcroots <instance-id>  Print several shortest GC root paths.");
        out.println();
        out.println("Defaults:");
        out.println("  start = " + DEFAULT_START);
        out.println("  count = " + DEFAULT_COUNT);
        out.println("  gcroots count = " + DEFAULT_GCROOT_PATH_COUNT);
    }

    private enum Command {
        TYPES,
        TYPES_RETAINED,
        BIGGEST,
        INSTANCES,
        INSPECT,
        REFS_IN,
        REFS_OUT,
        GCROOT,
        GCROOTS,
        HELP
    }

    private record CommandLine(Path heapDump, Command command, String typeFilter, String typeName, long instanceId, int start, int count) {

        static CommandLine parse(String[] args) throws UsageException {
            if (args.length == 0) {
                throw new UsageException("Missing arguments.");
            }
            if (isHelpToken(args[0])) {
                return help();
            }

            Path heapDump = null;
            int index = 0;

            if ("--heap".equals(args[0]) || "-f".equals(args[0])) {
                if (args.length < 2) {
                    throw new UsageException("Missing heap dump path after " + args[0] + '.');
                }
                heapDump = Path.of(args[1]);
                index = 2;
            } else if (!isCommandToken(args[0])) {
                heapDump = Path.of(args[0]);
                index = 1;
            }

            if (index >= args.length) {
                throw new UsageException("Missing command.");
            }

            Command command = parseCommand(args[index++]);
            if (command == Command.HELP) {
                return help();
            }
            if (heapDump == null) {
                throw new UsageException("Missing heap dump path. Use '<heap-dump> <command> ...' or '--heap <heap-dump> <command> ...'.");
            }

            return switch (command) {
                case TYPES -> parseTypeListing(heapDump, command, args, index);
                case TYPES_RETAINED -> parseTypeListing(heapDump, command, args, index);
                case BIGGEST -> parseBiggest(heapDump, args, index);
                case INSTANCES -> parseInstances(heapDump, args, index);
                case INSPECT -> parseInspect(heapDump, args, index);
                case REFS_IN -> parseReferenceListing(heapDump, command, args, index);
                case REFS_OUT -> parseReferenceListing(heapDump, command, args, index);
                case GCROOT -> parseGcRoot(heapDump, args, index);
                case GCROOTS -> parseGcRoots(heapDump, args, index);
                case HELP -> help();
            };
        }

        private static CommandLine parseTypeListing(Path heapDump, Command command, String[] args, int index) throws UsageException {
            ParsedTypeOptions options = parseTypeOptions(args, index);
            int start = parseOptionalInt(args, options.nextIndex(), DEFAULT_START, "start");
            int count = parseOptionalInt(args, options.nextIndex() + 1, DEFAULT_COUNT, "count");
            ensureNoExtraArguments(args, options.nextIndex() + 2);
            validatePaging(start, count);
            return new CommandLine(heapDump, command, options.typeFilter(), null, -1L, start, count);
        }

        private static ParsedTypeOptions parseTypeOptions(String[] args, int index) throws UsageException {
            String typeFilter = null;
            while (index < args.length && args[index].startsWith("--")) {
                String option = args[index];
                if ("--filter".equals(option)) {
                    if (index + 1 >= args.length) {
                        throw new UsageException("Missing filter text after --filter.");
                    }
                    typeFilter = requireNonBlank(args[index + 1], "Filter text must not be blank.");
                    index += 2;
                } else if (option.startsWith("--filter=")) {
                    typeFilter = requireNonBlank(option.substring("--filter=".length()), "Filter text must not be blank.");
                    index++;
                } else {
                    throw new UsageException("Unknown option: " + option + '.');
                }
            }
            return new ParsedTypeOptions(typeFilter, index);
        }

        private static CommandLine parseBiggest(Path heapDump, String[] args, int index) throws UsageException {
            int start = parseOptionalInt(args, index, DEFAULT_START, "start");
            int count = parseOptionalInt(args, index + 1, DEFAULT_COUNT, "count");
            ensureNoExtraArguments(args, index + 2);
            validatePaging(start, count);
            return new CommandLine(heapDump, Command.BIGGEST, null, null, -1L, start, count);
        }

        private static CommandLine parseInstances(Path heapDump, String[] args, int index) throws UsageException {
            if (index >= args.length) {
                throw new UsageException("Missing type name for instances command.");
            }
            String typeName = args[index++];
            int start = parseOptionalInt(args, index, DEFAULT_START, "start");
            int count = parseOptionalInt(args, index + 1, DEFAULT_COUNT, "count");
            ensureNoExtraArguments(args, index + 2);
            validatePaging(start, count);
            return new CommandLine(heapDump, Command.INSTANCES, null, typeName, -1L, start, count);
        }

        private static CommandLine parseInspect(Path heapDump, String[] args, int index) throws UsageException {
            if (index >= args.length) {
                throw new UsageException("Missing instance identifier for inspect command.");
            }
            long instanceId = parseLong(args[index], "instance-id");
            ensureNoExtraArguments(args, index + 1);
            return new CommandLine(heapDump, Command.INSPECT, null, null, instanceId, DEFAULT_START, DEFAULT_COUNT);
        }

        private static CommandLine parseReferenceListing(Path heapDump, Command command, String[] args, int index) throws UsageException {
            if (index >= args.length) {
                throw new UsageException("Missing instance identifier.");
            }
            long instanceId = parseLong(args[index++], "instance-id");
            int start = parseOptionalInt(args, index, DEFAULT_START, "start");
            int count = parseOptionalInt(args, index + 1, DEFAULT_COUNT, "count");
            ensureNoExtraArguments(args, index + 2);
            validatePaging(start, count);
            return new CommandLine(heapDump, command, null, null, instanceId, start, count);
        }

        private static CommandLine parseGcRoot(Path heapDump, String[] args, int index) throws UsageException {
            if (index >= args.length) {
                throw new UsageException("Missing instance identifier for gcroot command.");
            }
            long instanceId = parseLong(args[index], "instance-id");
            ensureNoExtraArguments(args, index + 1);
            return new CommandLine(heapDump, Command.GCROOT, null, null, instanceId, DEFAULT_START, DEFAULT_COUNT);
        }

        private static CommandLine parseGcRoots(Path heapDump, String[] args, int index) throws UsageException {
            if (index >= args.length) {
                throw new UsageException("Missing instance identifier for gcroots command.");
            }
            long instanceId = parseLong(args[index++], "instance-id");
            int count = parseOptionalInt(args, index, DEFAULT_GCROOT_PATH_COUNT, "count");
            ensureNoExtraArguments(args, index + 1);
            if (count <= 0) {
                throw new UsageException("count must be greater than zero.");
            }
            return new CommandLine(heapDump, Command.GCROOTS, null, null, instanceId, DEFAULT_START, count);
        }

        private static void validatePaging(int start, int count) throws UsageException {
            if (start < 0) {
                throw new UsageException("start must be non-negative.");
            }
            if (count <= 0) {
                throw new UsageException("count must be greater than zero.");
            }
        }

        private static String requireNonBlank(String value, String message) throws UsageException {
            if (value == null || value.isBlank()) {
                throw new UsageException(message);
            }
            return value;
        }

        private static int parseOptionalInt(String[] args, int index, int defaultValue, String name) throws UsageException {
            if (index >= args.length) {
                return defaultValue;
            }
            return parseInt(args[index], name);
        }

        private static int parseInt(String value, String name) throws UsageException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new UsageException("Invalid " + name + ": " + value + '.', e);
            }
        }

        private static long parseLong(String value, String name) throws UsageException {
            try {
                return Long.decode(value);
            } catch (NumberFormatException e) {
                throw new UsageException("Invalid " + name + ": " + value + '.', e);
            }
        }

        private static void ensureNoExtraArguments(String[] args, int index) throws UsageException {
            if (index < args.length) {
                throw new UsageException("Too many arguments.");
            }
        }

        private static Command parseCommand(String token) throws UsageException {
            return switch (token) {
                case "types" -> Command.TYPES;
                case "types-retained" -> Command.TYPES_RETAINED;
                case "biggest" -> Command.BIGGEST;
                case "instances" -> Command.INSTANCES;
                case "inspect" -> Command.INSPECT;
                case "refs-in" -> Command.REFS_IN;
                case "refs-out" -> Command.REFS_OUT;
                case "gcroot" -> Command.GCROOT;
                case "gcroots" -> Command.GCROOTS;
                case "help", "--help", "-h" -> Command.HELP;
                default -> throw new UsageException("Unknown command: " + token + '.');
            };
        }

        private static boolean isCommandToken(String token) {
            return switch (token) {
                case "types", "types-retained", "biggest", "instances", "inspect", "refs-in", "refs-out", "gcroot", "gcroots", "help", "--help", "-h" -> true;
                default -> false;
            };
        }

        private static boolean isHelpToken(String token) {
            return switch (token) {
                case "help", "--help", "-h" -> true;
                default -> false;
            };
        }

        private static CommandLine help() {
            return new CommandLine(null, Command.HELP, null, null, -1L, DEFAULT_START, DEFAULT_COUNT);
        }
    }

    private record ParsedTypeOptions(String typeFilter, int nextIndex) {
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        private UsageException(String message) {
            super(message);
        }

        private UsageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
