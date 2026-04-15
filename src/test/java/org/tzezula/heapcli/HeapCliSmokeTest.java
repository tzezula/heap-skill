package org.tzezula.heapcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import javax.management.MBeanServer;

import org.graalvm.visualvm.lib.jfluid.heap.Heap;
import org.graalvm.visualvm.lib.jfluid.heap.HeapFactory;
import org.graalvm.visualvm.lib.jfluid.heap.Instance;
import org.graalvm.visualvm.lib.jfluid.heap.JavaClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.management.HotSpotDiagnosticMXBean;

class HeapCliSmokeTest {

    @SuppressWarnings("unused")
    private static final Marker ROOTED = new Marker("heap-cli-smoke-marker");

    private static Path heapDump;
    private static long markerInstanceId;
    private static long markerInstanceSize;

    @BeforeAll
    static void createHeapDump() throws Exception {
        System.gc();
        heapDump = Files.createTempFile("heap-cli-smoke-", ".hprof");
        Files.deleteIfExists(heapDump);
        dumpHeap(heapDump);

        Heap heap = HeapFactory.createHeap(heapDump.toFile());
        JavaClass markerClass = heap.getJavaClassByName(Marker.class.getName());
        assertNotNull(markerClass, "Marker class must be present in the heap dump.");

        Iterator<Instance> instances = markerClass.getInstancesIterator();
        assertTrue(instances.hasNext(), "Marker instance must be present in the heap dump.");

        Instance marker = instances.next();
        markerInstanceId = marker.getInstanceId();
        markerInstanceSize = marker.getSize();
    }

    @AfterAll
    static void cleanupHeapDump() throws IOException {
        if (heapDump != null) {
            Files.deleteIfExists(heapDump);
        }
    }

    @Test
    void helpCommandPrintsUsage() throws Exception {
        RunResult result = run("help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("types-retained"));
        assertTrue(result.stdout().contains("refs-in"));
        assertTrue(result.stdout().contains("gcroots"));
    }

    @Test
    void typesCommandPrintsHeapTypes() throws Exception {
        RunResult result = run(heapDump.toString(), "types", "0", "200");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("NAME"));
        assertTrue(result.stdout().contains("COUNT"));
        assertTrue(result.stdout().contains("java.lang.String"));
        assertTrue(result.stdout().contains(" B"));
    }

    @Test
    void typesRetainedCommandPrintsRetainedSizes() throws Exception {
        RunResult result = run(heapDump.toString(), "types-retained", "--filter", "marker", "0", "20");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("RETAINED_SIZE"));
        assertTrue(result.stdout().contains(Marker.class.getName()));
    }

    @Test
    void biggestCommandPrintsBiggestObjects() throws Exception {
        RunResult result = run(heapDump.toString(), "biggest", "0", "10");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("INSTANCE_ID"));
        assertTrue(result.stdout().contains("RETAINED_SIZE"));
    }

    @Test
    void instancesCommandPrintsMarkerInstance() throws Exception {
        RunResult result = run(heapDump.toString(), "instances", Marker.class.getName(), "0", "20");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("INSTANCE_ID"));
        assertTrue(result.stdout().contains(Long.toString(markerInstanceId)));
        assertTrue(result.stdout().contains(Long.toString(markerInstanceSize)));
        assertTrue(result.stdout().contains(" B"));
    }

    @Test
    void inspectCommandPrintsInstanceDetails() throws Exception {
        RunResult result = run(heapDump.toString(), "inspect", Long.toString(markerInstanceId));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("INSTANCE_ID: " + markerInstanceId));
        assertTrue(result.stdout().contains("CLASS: " + Marker.class.getName()));
        assertTrue(result.stdout().contains("FIELD"));
        assertTrue(result.stdout().contains("name"));
    }

    @Test
    void refsInCommandPrintsIncomingReferences() throws Exception {
        RunResult result = run(heapDump.toString(), "refs-in", Long.toString(markerInstanceId), "0", "20");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("REFERRER_ID"));
        assertTrue(result.stdout().contains("ROOTED") || result.stdout().contains("<class>"));
    }

    @Test
    void refsOutCommandPrintsOutgoingReferences() throws Exception {
        RunResult result = run(heapDump.toString(), "refs-out", Long.toString(markerInstanceId), "0", "20");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("TARGET_ID"));
        assertTrue(result.stdout().contains(".name"));
    }

    @Test
    void gcrootCommandPrintsPathForMarkerInstance() throws Exception {
        RunResult result = run(heapDump.toString(), "gcroot", Long.toString(markerInstanceId));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Shortest GC root path:"));
        assertTrue(result.stdout().contains("(instance id " + markerInstanceId + ")"));
        assertTrue(result.stdout().contains(Marker.class.getName()));
    }

    @Test
    void gcrootsCommandPrintsMultiplePathsView() throws Exception {
        RunResult result = run(heapDump.toString(), "gcroots", Long.toString(markerInstanceId), "3");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Path 1:"));
        assertTrue(result.stdout().contains("GC root path:"));
        assertTrue(result.stdout().contains(Marker.class.getName()));
    }

    private static RunResult run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                        PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(args, out, err);
        }
        return new RunResult(
                        exitCode,
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8));
    }

    private static void dumpHeap(Path outputFile) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean bean = ManagementFactory.newPlatformMXBeanProxy(
                        server,
                        "com.sun.management:type=HotSpotDiagnostic",
                        HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(outputFile.toString(), true);
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }

    private static final class Marker {
        @SuppressWarnings("unused")
        private final String name;

        private Marker(String name) {
            this.name = name;
        }
    }
}
