import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** Dependency-free build: run java Build.java from this directory with JDK 21+. */
public class Build {
    private static final String JAR = "dbeaver-license-tool.jar";
    private static final String ZIP = "dbeaver-license-tool-portable.zip";
    private static final List<String> LAUNCHERS = List.of("run.cmd", "run.sh", "run.command");

    public static void main(String[] args) {
        try { build(args); }
        catch (Exception error) {
            System.err.println("Build failed: " + error.getMessage());
            for (Throwable suppressed : error.getSuppressed()) System.err.println("Cleanup/rollback: " + suppressed);
            System.exit(1);
        }
    }

    private static void build(String[] args) throws Exception {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help") || args[i].equals("-h")) {
                System.out.println("JDK 21+ build: java Build.java [--root SOURCE_DIRECTORY] [--dist OUTPUT_DIRECTORY]");
                System.out.println("Defaults: current directory, dist. No external build tools or downloads.");
                return;
            }
            String key = args[i];
            if (!Set.of("--root", "--dist").contains(key) || i + 1 == args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Unknown option or missing value: " + key);
            if (options.putIfAbsent(key, args[++i]) != null) throw new IllegalArgumentException("Repeated option: " + key);
        }
        if (Runtime.version().feature() < 21) throw new IllegalStateException("JDK 21+ required");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK 21+ is required to compile sources");
        Path root = Path.of(options.getOrDefault("--root", ".")).toAbsolutePath().normalize();
        Path dist = options.containsKey("--dist") ? Path.of(options.get("--dist")).toAbsolutePath().normalize() : root.resolve("dist");
        var inputs = new ArrayList<Path>(List.of(root.resolve("src/io/github/oiltea94/dbeaverlicensetool/Main.java")));
        for (String launcher : LAUNCHERS) inputs.add(root.resolve("launchers").resolve(launcher));
        for (Path input : inputs) if (!Files.isRegularFile(input)) throw new IOException("Required input missing: " + input);
        Path stage = Files.createTempDirectory("dbeaver-tool-build-");
        try {
            Path classes = Files.createDirectory(stage.resolve("classes"));
            var command = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-d", classes.toString()));
            try (var paths = Files.walk(root.resolve("src/io/github/oiltea94/dbeaverlicensetool"))) {
                paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString()));
            }
            if (compiler.run(null, System.out, System.err, command.toArray(String[]::new)) != 0)
                throw new IOException("Compilation failed; existing distribution was not changed");
            Path artifact = stage.resolve(JAR);
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "io.github.oiltea94.dbeaverlicensetool.Main");
            try (var jar = new JarOutputStream(Files.newOutputStream(artifact), manifest); var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(0);
                    jar.putNextEntry(entry); Files.copy(file, jar); jar.closeEntry();
                }
            }
            var files = new LinkedHashMap<String, byte[]>();
            files.put(JAR, Files.readAllBytes(artifact));
            for (String launcher : LAUNCHERS) files.put(launcher, Files.readAllBytes(root.resolve("launchers").resolve(launcher)));
            Path archive = stage.resolve(ZIP);
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.setLevel(9);
                for (var item : files.entrySet()) {
                    var entry = new ZipEntry(item.getKey()); entry.setTime(0);
                    zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
                }
            }
            unixArchiveModes(archive);
            try (var zip = new ZipFile(archive.toFile())) {
                if (zip.size() != files.size()) throw new IOException("Archive entry count mismatch");
                for (var item : files.entrySet())
                    if (!Arrays.equals(item.getValue(), zip.getInputStream(zip.getEntry(item.getKey())).readAllBytes()))
                        throw new IOException("Archive readback mismatch: " + item.getKey());
            }
            var outputs = new LinkedHashMap<String, byte[]>();
            outputs.put(ZIP, Files.readAllBytes(archive));
            publish(dist, outputs);
            System.out.println("Compiler: JDK " + Runtime.version());
            System.out.println("ZIP: " + dist.resolve(ZIP));
            System.out.println("Extract the ZIP to run the tool. Only the ZIP is published to the output directory.");
            System.out.println("Package verified: four files (tool JAR and three launchers); no READMEs, screenshots, target plugins or keys.");
        } finally {
            // Only this invocation's newly allocated staging directory is removed.
            try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static boolean executable(String name) { return name.equals("run.sh") || name.equals("run.command"); }

    private static void unixArchiveModes(Path path) throws IOException {
        // Standard ZIP central-directory fields; this small archive never needs ZIP64.
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        if (end < 0 || buffer.getInt(end) != 0x06054b50) throw new IOException("Invalid ZIP end record");
        int cursor = buffer.getInt(end + 16);
        for (int i = 0; i < Short.toUnsignedInt(buffer.getShort(end + 10)); i++) {
            if (cursor < 0 || cursor + 46 > end || buffer.getInt(cursor) != 0x02014b50) throw new IOException("Invalid ZIP central entry");
            int length = Short.toUnsignedInt(buffer.getShort(cursor + 28));
            String name = new String(bytes, cursor + 46, length, StandardCharsets.UTF_8);
            buffer.putShort(cursor + 4, (short) ((3 << 8) | 20));
            buffer.putInt(cursor + 38, (executable(name) ? 0100755 : 0100644) << 16);
            cursor += 46 + length + Short.toUnsignedInt(buffer.getShort(cursor + 30)) + Short.toUnsignedInt(buffer.getShort(cursor + 32));
        }
        Files.write(path, bytes);
    }

    private static void publish(Path dist, LinkedHashMap<String, byte[]> files) throws IOException {
        Files.createDirectories(dist);
        var originals = new LinkedHashMap<Path, byte[]>();
        for (String name : files.keySet()) {
            Path destination = dist.resolve(name);
            if (Files.exists(destination) && !Files.isRegularFile(destination)) throw new IOException("Output is not a file: " + destination);
            originals.put(destination, Files.exists(destination) ? Files.readAllBytes(destination) : null);
        }
        var written = new ArrayList<Path>();
        try {
            for (var item : files.entrySet()) {
                Path destination = dist.resolve(item.getKey());
                replace(destination, item.getValue()); written.add(destination);
                if (!Arrays.equals(item.getValue(), Files.readAllBytes(destination))) throw new IOException("Output readback mismatch: " + destination);
            }
        } catch (IOException error) {
            Collections.reverse(written);
            for (Path destination : written) {
                try {
                    byte[] original = originals.get(destination);
                    if (original == null) Files.deleteIfExists(destination); else replace(destination, original);
                } catch (IOException rollback) { error.addSuppressed(rollback); }
            }
            throw error;
        }
    }

    private static void replace(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".build-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString(executable(destination.getFileName().toString()) ? "rwxr-xr-x" : "rw-r--r--")); }
            catch (UnsupportedOperationException ignored) { /* Windows uses its own ACLs. */ }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
