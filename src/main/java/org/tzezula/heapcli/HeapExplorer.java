package org.tzezula.heapcli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.graalvm.visualvm.lib.jfluid.heap.ArrayItemValue;
import org.graalvm.visualvm.lib.jfluid.heap.FieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.GCRoot;
import org.graalvm.visualvm.lib.jfluid.heap.Heap;
import org.graalvm.visualvm.lib.jfluid.heap.HeapFactory;
import org.graalvm.visualvm.lib.jfluid.heap.Instance;
import org.graalvm.visualvm.lib.jfluid.heap.JavaClass;
import org.graalvm.visualvm.lib.jfluid.heap.JavaFrameGCRoot;
import org.graalvm.visualvm.lib.jfluid.heap.JniLocalGCRoot;
import org.graalvm.visualvm.lib.jfluid.heap.ObjectArrayInstance;
import org.graalvm.visualvm.lib.jfluid.heap.ObjectFieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.PrimitiveArrayInstance;
import org.graalvm.visualvm.lib.jfluid.heap.ThreadObjectGCRoot;
import org.graalvm.visualvm.lib.jfluid.heap.Value;

final class HeapExplorer {

    private static final int INSPECT_PREVIEW_LIMIT = 20;

    private final Path heapDump;
    private final Heap heap;

    HeapExplorer(Path heapDump) throws IOException {
        if (!Files.isRegularFile(heapDump)) {
            throw new IllegalArgumentException("Heap dump file not found: " + heapDump);
        }
        this.heapDump = heapDump;
        this.heap = HeapFactory.createHeap(heapDump.toFile());
    }

    void printTypes(String typeFilter, int start, int count, PrintStream out) {
        List<TypeRow> rows = new ArrayList<>();
        for (JavaClass javaClass : heap.getAllClasses()) {
            if (matchesTypeFilter(javaClass.getName(), typeFilter)) {
                rows.add(new TypeRow(javaClass.getName(), javaClass.getInstancesCount(), javaClass.getInstanceSize(), javaClass.getAllInstancesSize(), -1L));
            }
        }
        rows.sort(Comparator.comparingInt(TypeRow::count).reversed().thenComparing(TypeRow::name));

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"NAME", "COUNT", "SIZE", "TOTAL_SIZE"});

        Slice slice = slice(start, count, rows.size());
        for (int index = slice.start(); index < slice.endExclusive(); index++) {
            TypeRow row = rows.get(index);
            table.add(new String[]{row.name(), Integer.toString(row.count()), formatSize(row.size()), formatSize(row.totalSize())});
        }

        printTable(out, table, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, slice, rows.size(), typeFilter == null ? "types" : "types matching '" + typeFilter + "'");
    }

    void printTypesRetained(String typeFilter, int start, int count, PrintStream out) {
        List<TypeRow> rows = new ArrayList<>();
        for (JavaClass javaClass : heap.getAllClasses()) {
            if (matchesTypeFilter(javaClass.getName(), typeFilter)) {
                rows.add(new TypeRow(
                                javaClass.getName(),
                                javaClass.getInstancesCount(),
                                javaClass.getInstanceSize(),
                                javaClass.getAllInstancesSize(),
                                javaClass.getRetainedSizeByClass()));
            }
        }
        rows.sort(Comparator.comparingLong(TypeRow::retainedSize).reversed().thenComparing(TypeRow::name));

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"NAME", "COUNT", "SIZE", "TOTAL_SIZE", "RETAINED_SIZE"});

        Slice slice = slice(start, count, rows.size());
        for (int index = slice.start(); index < slice.endExclusive(); index++) {
            TypeRow row = rows.get(index);
            table.add(new String[]{
                            row.name(),
                            Integer.toString(row.count()),
                            formatSize(row.size()),
                            formatSize(row.totalSize()),
                            formatSize(row.retainedSize())
            });
        }

        printTable(out, table, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, slice, rows.size(), typeFilter == null ? "retained-size types" : "retained-size types matching '" + typeFilter + "'");
    }

    void printBiggest(int start, int count, PrintStream out) {
        int requested = Math.max(start + count, count);
        List<Instance> biggest = heap.getBiggestObjectsByRetainedSize(requested);
        List<BiggestRow> rows = new ArrayList<>();
        for (Instance instance : biggest) {
            rows.add(new BiggestRow(instance.getInstanceId(), displayClassName(instance), instance.getSize(), instance.getRetainedSize()));
        }

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"INSTANCE_ID", "CLASS", "SIZE", "RETAINED_SIZE"});

        Slice slice = slice(start, count, rows.size());
        for (int index = slice.start(); index < slice.endExclusive(); index++) {
            BiggestRow row = rows.get(index);
            table.add(new String[]{
                            Long.toString(row.instanceId()),
                            row.className(),
                            formatSize(row.size()),
                            formatSize(row.retainedSize())
            });
        }

        printTable(out, table, Alignment.RIGHT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, slice, rows.size(), "biggest objects by retained size");
    }

    void printInstances(String typeName, int start, int count, PrintStream out) {
        JavaClass javaClass = heap.getJavaClassByName(typeName);
        if (javaClass == null) {
            throw new IllegalArgumentException("Type not found: " + typeName);
        }

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"INSTANCE_ID", "SIZE", "RETAINED_SIZE"});

        Iterator<Instance> instances = javaClass.getInstancesIterator();
        skip(instances, start);

        int emitted = 0;
        while (instances.hasNext() && emitted < count) {
            Instance instance = instances.next();
            table.add(new String[]{
                            Long.toString(instance.getInstanceId()),
                            formatSize(instance.getSize()),
                            formatSize(instance.getRetainedSize())
            });
            emitted++;
        }

        printTable(out, table, Alignment.RIGHT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, new Slice(start, start + emitted), javaClass.getInstancesCount(), "instances of " + typeName);
    }

    void printInspect(long instanceId, PrintStream out) {
        Instance instance = requireInstance(instanceId);
        List<ReferenceRow> outgoing = outgoingReferenceRows(instance);

        out.println("INSTANCE_ID: " + instance.getInstanceId());
        out.println("CLASS: " + displayClassName(instance));
        out.println("INSTANCE_NUMBER: " + instance.getInstanceNumber());
        out.println("SHALLOW_SIZE: " + formatSize(instance.getSize()));
        out.println("RETAINED_SIZE: " + formatSize(instance.getRetainedSize()));
        out.println("IS_GC_ROOT: " + instance.isGCRoot());
        out.println("NEAREST_GC_ROOT_POINTER: " + describeNullableInstance(instance.getNearestGCRootPointer()));
        out.println("INCOMING_REFERENCES: " + instance.getReferences().size());
        out.println("OUTGOING_REFERENCES: " + outgoing.size());
        out.println();

        if (instance instanceof ObjectArrayInstance objectArrayInstance) {
            printObjectArrayPreview(objectArrayInstance, out);
        } else if (instance instanceof PrimitiveArrayInstance primitiveArrayInstance) {
            printPrimitiveArrayPreview(primitiveArrayInstance, out);
        } else {
            printFieldValues(instance, out);
        }
    }

    void printIncomingReferences(long instanceId, int start, int count, PrintStream out) {
        Instance instance = requireInstance(instanceId);
        List<ReferenceRow> rows = incomingReferenceRows(instance);
        rows.sort(Comparator.comparing(ReferenceRow::className).thenComparingLong(ReferenceRow::instanceId).thenComparing(ReferenceRow::via));

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"REFERRER_ID", "REFERRER_CLASS", "VIA", "SIZE", "RETAINED_SIZE"});

        Slice slice = slice(start, count, rows.size());
        for (int index = slice.start(); index < slice.endExclusive(); index++) {
            ReferenceRow row = rows.get(index);
            table.add(new String[]{
                            Long.toString(row.instanceId()),
                            row.className(),
                            row.via(),
                            formatSize(row.size()),
                            formatSize(row.retainedSize())
            });
        }

        printTable(out, table, Alignment.RIGHT, Alignment.LEFT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, slice, rows.size(), "incoming references to " + displayClassName(instance) + '@' + instance.getInstanceId());
    }

    void printOutgoingReferences(long instanceId, int start, int count, PrintStream out) {
        Instance instance = requireInstance(instanceId);
        List<ReferenceRow> rows = outgoingReferenceRows(instance);
        rows.sort(Comparator.comparing(ReferenceRow::via).thenComparingLong(ReferenceRow::instanceId));

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"TARGET_ID", "TARGET_CLASS", "VIA", "SIZE", "RETAINED_SIZE"});

        Slice slice = slice(start, count, rows.size());
        for (int index = slice.start(); index < slice.endExclusive(); index++) {
            ReferenceRow row = rows.get(index);
            table.add(new String[]{
                            Long.toString(row.instanceId()),
                            row.className(),
                            row.via(),
                            formatSize(row.size()),
                            formatSize(row.retainedSize())
            });
        }

        printTable(out, table, Alignment.RIGHT, Alignment.LEFT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT);
        printPageSummary(out, slice, rows.size(), "outgoing references from " + displayClassName(instance) + '@' + instance.getInstanceId());
    }

    void printGcRoot(long instanceId, PrintStream out) {
        Instance instance = requireInstance(instanceId);
        List<Instance> chain = nearestGCRootChain(instance);
        if (chain.isEmpty()) {
            out.println("Instance " + instance.getInstanceId() + " is not reachable from a GC root in " + heapDump + '.');
            return;
        }
        GCRoot gcRoot = firstGCRoot(chain.get(chain.size() - 1));
        out.println(formatGcRootPath(chain, gcRoot, "Shortest GC root path"));
    }

    void printGcRoots(long instanceId, int count, PrintStream out) {
        Instance instance = requireInstance(instanceId);
        List<GCRootPath> paths = collectGcRootPaths(instance, count);
        if (paths.isEmpty()) {
            out.println("Instance " + instance.getInstanceId() + " is not reachable from a GC root in " + heapDump + '.');
            return;
        }
        for (int index = 0; index < paths.size(); index++) {
            if (index > 0) {
                out.println();
            }
            out.println("Path " + (index + 1) + ':');
            out.println(formatGcRootPath(paths.get(index).instances(), paths.get(index).gcRoot(), "GC root path"));
        }
    }

    private Instance requireInstance(long instanceId) {
        Instance instance = heap.getInstanceByID(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Instance not found: " + instanceId);
        }
        return instance;
    }

    private void printFieldValues(Instance instance, PrintStream out) {
        List<FieldValue> fieldValues = instance.getFieldValues();
        if (fieldValues.isEmpty()) {
            out.println("No instance fields.");
            return;
        }

        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"FIELD", "TYPE", "VALUE"});
        for (FieldValue fieldValue : fieldValues) {
            String fieldName = fieldValue.getField().getName();
            String fieldType = fieldValue.getField().getType().getName();
            String value = formatFieldValue(fieldValue);
            table.add(new String[]{fieldName, fieldType, value});
        }
        printTable(out, table, Alignment.LEFT, Alignment.LEFT, Alignment.LEFT);
    }

    private void printObjectArrayPreview(ObjectArrayInstance instance, PrintStream out) {
        out.println("OBJECT_ARRAY_LENGTH: " + instance.getLength());
        out.println();
        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"INDEX", "VALUE"});
        List<Instance> values = instance.getValues();
        int previewSize = Math.min(values.size(), INSPECT_PREVIEW_LIMIT);
        for (int index = 0; index < previewSize; index++) {
            table.add(new String[]{Integer.toString(index), describeNullableInstance(values.get(index))});
        }
        printTable(out, table, Alignment.RIGHT, Alignment.LEFT);
        if (values.size() > previewSize) {
            out.println();
            out.println("... " + (values.size() - previewSize) + " more elements omitted.");
        }
    }

    private void printPrimitiveArrayPreview(PrimitiveArrayInstance instance, PrintStream out) {
        out.println("PRIMITIVE_ARRAY_LENGTH: " + instance.getLength());
        out.println();
        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"INDEX", "VALUE"});
        List<String> values = instance.getValues();
        int previewSize = Math.min(values.size(), INSPECT_PREVIEW_LIMIT);
        for (int index = 0; index < previewSize; index++) {
            table.add(new String[]{Integer.toString(index), values.get(index)});
        }
        printTable(out, table, Alignment.RIGHT, Alignment.LEFT);
        if (values.size() > previewSize) {
            out.println();
            out.println("... " + (values.size() - previewSize) + " more elements omitted.");
        }
    }

    private List<ReferenceRow> incomingReferenceRows(Instance instance) {
        List<ReferenceRow> rows = new ArrayList<>();
        for (Value value : instance.getReferences()) {
            Instance referrer = value.getDefiningInstance();
            if (referrer != null) {
                rows.add(new ReferenceRow(referrer.getInstanceId(), displayClassName(referrer), describeReferenceValue(value), referrer.getSize(), referrer.getRetainedSize()));
            }
        }
        return rows;
    }

    private List<ReferenceRow> outgoingReferenceRows(Instance instance) {
        List<ReferenceRow> rows = new ArrayList<>();
        if (instance instanceof ObjectArrayInstance objectArrayInstance) {
            for (ArrayItemValue item : objectArrayInstance.getItems()) {
                Instance target = item.getInstance();
                if (target != null) {
                    rows.add(new ReferenceRow(target.getInstanceId(), displayClassName(target), '[' + Integer.toString(item.getIndex()) + ']', target.getSize(), target.getRetainedSize()));
                }
            }
            return rows;
        }
        if (instance instanceof PrimitiveArrayInstance) {
            return rows;
        }
        for (FieldValue fieldValue : instance.getFieldValues()) {
            if (fieldValue instanceof ObjectFieldValue objectFieldValue) {
                Instance target = objectFieldValue.getInstance();
                if (target != null) {
                    rows.add(new ReferenceRow(target.getInstanceId(), displayClassName(target), '.' + objectFieldValue.getField().getName(), target.getSize(), target.getRetainedSize()));
                }
            }
        }
        return rows;
    }

    private List<Instance> nearestGCRootChain(Instance instance) {
        List<Instance> chain = new ArrayList<>();
        Instance current = instance;
        while (current != null) {
            chain.add(current);
            if (current.isGCRoot()) {
                return chain;
            }
            current = current.getNearestGCRootPointer();
        }
        return List.of();
    }

    private List<GCRootPath> collectGcRootPaths(Instance instance, int maxCount) {
        List<GCRootPath> paths = new ArrayList<>();
        Deque<PathNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.addLast(new PathNode(instance, null));
        visited.add(instance.getInstanceId());

        while (!queue.isEmpty() && paths.size() < maxCount) {
            PathNode node = queue.removeFirst();
            Instance current = node.instance();
            if (current.isGCRoot()) {
                Collection<GCRoot> gcRoots = heap.getGCRoots(current);
                if (gcRoots.isEmpty()) {
                    paths.add(new GCRootPath(reconstructPath(node), null));
                } else {
                    for (GCRoot gcRoot : gcRoots) {
                        paths.add(new GCRootPath(reconstructPath(node), gcRoot));
                        if (paths.size() >= maxCount) {
                            break;
                        }
                    }
                }
                continue;
            }

            for (Value value : current.getReferences()) {
                Instance referrer = value.getDefiningInstance();
                if (referrer != null && visited.add(referrer.getInstanceId())) {
                    queue.addLast(new PathNode(referrer, node));
                }
            }
        }
        return paths;
    }

    private static List<Instance> reconstructPath(PathNode node) {
        List<Instance> reversed = new ArrayList<>();
        for (PathNode current = node; current != null; current = current.parent()) {
            reversed.add(current.instance());
        }
        List<Instance> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    private GCRoot firstGCRoot(Instance instance) {
        Collection<GCRoot> gcRoots = heap.getGCRoots(instance);
        return gcRoots.isEmpty() ? null : gcRoots.iterator().next();
    }

    private String formatGcRootPath(List<Instance> chain, GCRoot gcRoot, String title) {
        StringBuilder sb = new StringBuilder(title).append(':').append('\n');
        boolean first = true;
        for (int index = 0; index < chain.size(); index++) {
            Instance current = chain.get(index);
            Instance previous = index == 0 ? null : chain.get(index - 1);
            InstanceReference reference = buildInstanceReference(current, previous);
            if (!first) {
                sb.append('\n');
            }
            sb.append("        ").append(reference.className());
            switch (reference.kind()) {
                case OBJECT_FIELD -> sb.append('.').append(reference.memberName());
                case ARRAY_ELEMENT -> sb.append('[').append(reference.memberName()).append(']');
            }
            if (first) {
                sb.append(" (instance id ").append(chain.get(0).getInstanceId()).append(')');
                first = false;
            }
        }
        sb.append(" (").append(gcRoot != null ? gcRoot.getKind() : "unknown").append(')');
        appendGcRootMetadata(sb, gcRoot);
        return sb.toString();
    }

    private InstanceReference buildInstanceReference(Instance current, Instance previous) {
        if (previous == null) {
            return new InstanceReference(ReferenceKind.OBJECT_FIELD, displayClassName(current), "this");
        }
        Value value = findReferenceValue(current, previous);
        if (value instanceof ArrayItemValue arrayItemValue) {
            return new InstanceReference(ReferenceKind.ARRAY_ELEMENT, displayClassName(current), Integer.toString(arrayItemValue.getIndex()));
        }
        if (value instanceof FieldValue fieldValue) {
            return new InstanceReference(ReferenceKind.OBJECT_FIELD, displayClassName(current), fieldValue.getField().getName());
        }
        return new InstanceReference(ReferenceKind.OBJECT_FIELD, displayClassName(current), "<?>" );
    }

    private static Value findReferenceValue(Instance parent, Instance child) {
        for (Value value : child.getReferences()) {
            Instance definingInstance = value.getDefiningInstance();
            if (definingInstance != null && definingInstance.getInstanceId() == parent.getInstanceId()) {
                return value;
            }
        }
        return null;
    }

    private static void appendGcRootMetadata(StringBuilder sb, GCRoot gcRoot) {
        if (gcRoot == null) {
            return;
        }
        if (GCRoot.JAVA_FRAME.equals(gcRoot.getKind())) {
            JavaFrameGCRoot frameGCRoot = (JavaFrameGCRoot) gcRoot;
            ThreadObjectGCRoot threadGCRoot = frameGCRoot.getThreadGCRoot();
            appendStackTrace(sb, getThreadId(threadGCRoot), stackUpToAllocationFrame(threadGCRoot, frameGCRoot.getFrameNumber()));
        } else if (GCRoot.JNI_LOCAL.equals(gcRoot.getKind())) {
            JniLocalGCRoot jniLocalGCRoot = (JniLocalGCRoot) gcRoot;
            ThreadObjectGCRoot threadGCRoot = jniLocalGCRoot.getThreadGCRoot();
            appendStackTrace(sb, getThreadId(threadGCRoot), stackUpToAllocationFrame(threadGCRoot, jniLocalGCRoot.getFrameNumber()));
        }
    }

    private static void appendStackTrace(StringBuilder sb, long threadId, List<StackTraceElement> stackTrace) {
        sb.append('\n').append("held by Java thread with thread id ").append(threadId);
        for (StackTraceElement frame : stackTrace) {
            sb.append('\n').append("        ").append(frame);
        }
    }

    private static List<StackTraceElement> stackUpToAllocationFrame(ThreadObjectGCRoot threadObject, int frameNumber) {
        StackTraceElement[] stack = threadObject.getStackTrace();
        if (frameNumber < 0 || stack.length == 0) {
            return List.of();
        }
        int start = Math.min(frameNumber, stack.length);
        return Arrays.asList(Arrays.copyOfRange(stack, start, stack.length));
    }

    private static long getThreadId(ThreadObjectGCRoot threadObjectGCRoot) {
        Object tid = threadObjectGCRoot.getInstance().getValueOfField("tid");
        return tid instanceof Number number ? number.longValue() : -1L;
    }

    private String displayClassName(Instance instance) {
        String className = instance.getJavaClass().getName();
        if (Class.class.getName().equals(className)) {
            JavaClass javaClass = heap.getJavaClassByID(instance.getInstanceId());
            if (javaClass != null) {
                return javaClass.getName();
            }
        }
        return className;
    }

    private String describeNullableInstance(Instance instance) {
        return instance == null ? "<none>" : displayClassName(instance) + '@' + instance.getInstanceId();
    }

    private static String describeReferenceValue(Value value) {
        if (value instanceof ArrayItemValue arrayItemValue) {
            return '[' + Integer.toString(arrayItemValue.getIndex()) + ']';
        }
        if (value instanceof FieldValue fieldValue) {
            return '.' + fieldValue.getField().getName();
        }
        return "<?>";
    }

    private String formatFieldValue(FieldValue fieldValue) {
        if (fieldValue instanceof ObjectFieldValue objectFieldValue) {
            Instance target = objectFieldValue.getInstance();
            return target == null ? "null" : describeNullableInstance(target);
        }
        return fieldValue.getValue();
    }

    private static boolean matchesTypeFilter(String typeName, String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) {
            return true;
        }
        return typeName.toLowerCase(Locale.ROOT).contains(typeFilter.toLowerCase(Locale.ROOT));
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }

        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }

        String formattedValue = value >= 10
                        ? String.format(Locale.ROOT, "%.0f %s", value, units[unitIndex])
                        : String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
        return formattedValue + " (" + bytes + " B)";
    }

    private static void skip(Iterator<?> iterator, int count) {
        for (int index = 0; index < count && iterator.hasNext(); index++) {
            iterator.next();
        }
    }

    private static Slice slice(int start, int count, int total) {
        int safeStart = Math.min(Math.max(start, 0), total);
        int safeEnd = Math.min(safeStart + Math.max(count, 0), total);
        return new Slice(safeStart, safeEnd);
    }

    private static void printPageSummary(PrintStream out, Slice slice, int total, String label) {
        out.println();
        if (total == 0) {
            out.println("No " + label + " found.");
            return;
        }
        if (slice.start() >= total) {
            out.println("Requested range is empty. Total " + label + ": " + total + '.');
            return;
        }
        out.println("Showing " + slice.start() + '-' + (slice.endExclusive() - 1) + " of " + total + ' ' + label + '.');
    }

    private static void printTable(PrintStream out, List<String[]> rows, Alignment... alignments) {
        int[] widths = new int[alignments.length];
        for (String[] row : rows) {
            for (int column = 0; column < row.length; column++) {
                widths[column] = Math.max(widths[column], row[column].length());
            }
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            String[] row = rows.get(rowIndex);
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < row.length; column++) {
                if (column > 0) {
                    line.append("  ");
                }
                line.append(pad(row[column], widths[column], alignments[column]));
            }
            out.println(line);
            if (rowIndex == 0) {
                StringBuilder separator = new StringBuilder();
                for (int column = 0; column < widths.length; column++) {
                    if (column > 0) {
                        separator.append("  ");
                    }
                    separator.append("-".repeat(widths[column]));
                }
                out.println(separator);
            }
        }
    }

    private static String pad(String value, int width, Alignment alignment) {
        int padding = width - value.length();
        if (padding <= 0) {
            return value;
        }
        String spaces = " ".repeat(padding);
        return alignment == Alignment.RIGHT ? spaces + value : value + spaces;
    }

    private enum Alignment {
        LEFT,
        RIGHT
    }

    private enum ReferenceKind {
        OBJECT_FIELD,
        ARRAY_ELEMENT
    }

    private record Slice(int start, int endExclusive) {
    }

    private record TypeRow(String name, int count, long size, long totalSize, long retainedSize) {
    }

    private record BiggestRow(long instanceId, String className, long size, long retainedSize) {
    }

    private record ReferenceRow(long instanceId, String className, String via, long size, long retainedSize) {
    }

    private record InstanceReference(ReferenceKind kind, String className, String memberName) {
    }

    private record PathNode(Instance instance, PathNode parent) {
    }

    private record GCRootPath(List<Instance> instances, GCRoot gcRoot) {
    }
}
