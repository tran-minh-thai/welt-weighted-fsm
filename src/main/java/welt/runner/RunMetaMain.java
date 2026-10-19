package welt.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the provenance record of a run directory ({@code meta.json}) or the repository's
 * measurement-machine declaration.
 *
 * <pre>
 *   java welt.runner.RunMetaMain start  &lt;meta.json&gt; key=value ...   # machine, JVM, git, start time
 *   java welt.runner.RunMetaMain finish &lt;meta.json&gt;                 # adds the end time
 *   java welt.runner.RunMetaMain machine &lt;file.txt&gt;                  # machine declaration only
 * </pre>
 *
 * <p>Everything is read from the running JVM and the operating system (no hand-typed
 * values). The CPU name and physical core count are best effort per OS; a value that
 * cannot be read is written as {@code "unknown"}, never guessed. The JVM fields describe
 * THIS JVM, so the experiment scripts call it with the same {@code JAVA_OPTS} as the runs.
 */
public final class RunMetaMain {

    private RunMetaMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RunMetaMain start|finish|machine <file> [key=value ...]");
            System.exit(2);
        }
        Path file = Path.of(args[1]);
        switch (args[0]) {
            case "start": {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("startTime", now());
                m.putAll(machine());
                m.putAll(jvm());
                m.putAll(git());
                for (int i = 2; i < args.length; i++) {
                    int eq = args[i].indexOf('=');
                    if (eq <= 0) throw new IllegalArgumentException("expected key=value, got " + args[i]);
                    m.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                }
                Files.writeString(file, toJson(m), StandardCharsets.UTF_8);
                break;
            }
            case "finish": {
                String json = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!json.endsWith("}")) throw new IOException(file + " is not a meta.json written by 'start'");
                json = json.substring(0, json.length() - 1).replaceAll("\\s+$", "")
                        + ",\n  \"endTime\": " + quote(now()) + "\n}\n";
                Files.writeString(file, json, StandardCharsets.UTF_8);
                break;
            }
            case "machine": {
                Map<String, String> m = machine();
                m.putAll(jvm());
                StringBuilder sb = new StringBuilder("# Measurement machine, written by welt.runner.RunMetaMain on ")
                        .append(now()).append('\n');
                for (Map.Entry<String, String> e : m.entrySet())
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
                break;
            }
            default:
                System.err.println("unknown command " + args[0]);
                System.exit(2);
        }
        System.out.println("wrote " + file);
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static Map<String, String> machine() {
        Map<String, String> m = new LinkedHashMap<>();
        String os = System.getProperty("os.name", "");
        m.put("hostname", hostname());
        m.put("os", os + " " + System.getProperty("os.version", ""));
        m.put("arch", System.getProperty("os.arch", ""));
        m.put("cpu", cpuName(os));
        m.put("physicalCores", physicalCores(os));
        m.put("logicalProcessors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        long ram = -1;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            ram = ((com.sun.management.OperatingSystemMXBean) bean).getTotalMemorySize();
        }
        m.put("ramGB", ram > 0 ? String.format(java.util.Locale.ROOT, "%.1f", ram / 1073741824.0) : "unknown");
        return m;
    }

    static Map<String, String> jvm() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("javaVendor", System.getProperty("java.vendor", ""));
        m.put("javaRuntimeVersion", System.getProperty("java.runtime.version", ""));
        m.put("javaVmName", System.getProperty("java.vm.name", ""));
        m.put("jvmArgs", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        m.put("maxHeapMB", String.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        String opts = System.getenv("JAVA_OPTS");
        m.put("JAVA_OPTS", opts == null ? "" : opts);
        return m;
    }

    /** Commit and working-tree state: a commit hash describes the code only if the tree is clean. */
    static Map<String, String> git() {
        Map<String, String> m = new LinkedHashMap<>();
        String head = firstLine(run("git", "rev-parse", "HEAD"));
        m.put("gitCommit", head.isEmpty() ? "unknown" : head);
        List<String> dirty = run("git", "status", "--porcelain", "--", ".", ":(exclude)results");
        m.put("gitTree", head.isEmpty() ? "unknown" : (dirty.isEmpty() ? "clean" : "MODIFIED (" + dirty.size() + " files)"));
        return m;
    }

    private static String hostname() {
        String env = System.getenv("COMPUTERNAME"); // Windows
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String cpuName(String os) {
        String v = "";
        if (os.startsWith("Mac")) {
            v = firstLine(run("sysctl", "-n", "machdep.cpu.brand_string"));
        } else if (os.startsWith("Windows")) {
            for (String line : run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString")) {
                int i = line.indexOf("REG_SZ");
                if (i >= 0) v = line.substring(i + "REG_SZ".length()).trim();
            }
        } else {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo")))
                    if (line.startsWith("model name")) { v = line.substring(line.indexOf(':') + 1).trim(); break; }
            } catch (IOException ignored) {
                // left empty -> unknown
            }
        }
        return v.isEmpty() ? "unknown" : v;
    }

    private static String physicalCores(String os) {
        String v = "";
        if (os.startsWith("Mac")) {
            v = firstLine(run("sysctl", "-n", "hw.physicalcpu"));
        } else if (os.startsWith("Windows")) {
            v = firstLine(run("powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum"));
        } else {
            v = firstLine(run("sh", "-c", "lscpu -p=CORE,SOCKET 2>/dev/null | grep -v '^#' | sort -u | wc -l"));
        }
        return v.matches("\\d+") && !v.equals("0") ? v : "unknown";
    }

    /** Run a command and return its stdout lines; an empty list when it cannot run. */
    private static List<String> run(String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (!line.isBlank()) out.add(line);
            }
            if (p.waitFor() != 0) out.clear();
        } catch (IOException | InterruptedException e) {
            out.clear();
        }
        return out;
    }

    private static String firstLine(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String toJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append("  ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            sb.append(++i < m.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }
}
