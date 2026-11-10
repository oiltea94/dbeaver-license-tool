import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Run from the project directory: java tests/BuildTest.java */
public class BuildTest {
    private static int checks;
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    public static void main(String[] args) throws Exception {
        Path builder = Path.of("Build.java").toAbsolutePath();
        check(Files.isRegularFile(builder), "JDK-only build entry exists");
        Path work = Files.createTempDirectory("build-test-with-spaces-");
        try {
            Path root = work.resolve("source project"), dist = work.resolve("portable output");
            Files.createDirectories(root.resolve("src/io/github/oiltea94/dbeaverlicensetool"));
            Files.createDirectories(root.resolve("launchers"));
            Path source = root.resolve("src/io/github/oiltea94/dbeaverlicensetool/Main.java");
            String valid = "package io.github.oiltea94.dbeaverlicensetool; public class Main { public static void main(String[] args) { System.out.println(\"BUILD_TEST_APP\"); } }";
            Files.writeString(source, valid);
            for (String name : List.of("run.cmd", "run.sh", "run.command"))
                Files.writeString(root.resolve("launchers").resolve(name), "launcher " + name);
            var readmes = new LinkedHashMap<String, String>();
            readmes.put("README.md", "[中文](README.zh-CN.md)\n\n<!-- source-only:start -->\n![Screenshot](assets/screenshots/tool-en.png)\n\n*Example caption.*\n<!-- source-only:end -->\n\n## Usage\nRun the launcher.\n\n## Restore\nKeep backups.\n\n## Disclaimer\nProvided as is.\n\n*Keep this ordinary emphasis.*\n");
            readmes.put("README.zh-CN.md", "[English](README.md)\n\n<!-- source-only:start -->\n![截图](assets/screenshots/tool-zh-CN.png)\n\n*示例图注。*\n<!-- source-only:end -->\n\n## 使用\n运行启动脚本。\n\n## 还原\n保留备份。\n\n## 免责声明\n按现状提供。\n");
            for (var entry : readmes.entrySet()) Files.writeString(root.resolve(entry.getKey()), entry.getValue());
            Files.createDirectories(root.resolve("assets/screenshots"));
            for (String name : List.of("tool-en.png", "tool-zh-CN.png"))
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", root.resolve("assets/screenshots").resolve(name).toFile());
            Files.writeString(root.resolve("private.pem"), "must not be packaged");
            Files.writeString(root.resolve("unrelated.jar"), "must not be packaged");
            invoke(builder, root, true, "--dist", dist.toString());
            checkZipOnly(dist);
            Path extracted = Files.createDirectory(work.resolve("extracted package"));
            Set<String> packaged = Set.of("dbeaver-license-tool.jar", "run.cmd", "run.sh", "run.command");
            try (var zip = new ZipFile(dist.resolve("dbeaver-license-tool-portable.zip").toFile())) {
                check(zip.stream().map(e -> e.getName()).collect(java.util.stream.Collectors.toSet()).equals(packaged), "strict package whitelist");
                for (String name : packaged) {
                    try (var input = zip.getInputStream(zip.getEntry(name))) {
                        Files.copy(input, extracted.resolve(name));
                    }
                    if (!name.endsWith(".jar"))
                        check(Arrays.equals(Files.readAllBytes(extracted.resolve(name)), Files.readAllBytes(root.resolve("launchers").resolve(name))), "ZIP launcher matches source: " + name);
                }
            }
            for (var entry : readmes.entrySet()) {
                String original = entry.getValue();
                check(!Files.exists(dist.resolve(entry.getKey())), "README excluded from distribution: " + entry.getKey());
                check(Files.readString(root.resolve(entry.getKey())).equals(original), "source README preserved: " + entry.getKey());
                Files.delete(root.resolve(entry.getKey()));
            }
            check(!Files.exists(dist.resolve("assets")), "no assets in distribution");
            for (String name : List.of("tool-en.png", "tool-zh-CN.png")) {
                Path screenshot = root.resolve("assets/screenshots").resolve(name);
                check(javax.imageio.ImageIO.read(screenshot.toFile()).getWidth() == 2, "source screenshot preserved: " + name);
                Files.delete(screenshot);
            }
            try (var jar = new JarFile(extracted.resolve("dbeaver-license-tool.jar").toFile())) {
                check("io.github.oiltea94.dbeaverlicensetool.Main".equals(jar.getManifest().getMainAttributes().getValue("Main-Class")), "executable JAR manifest");
                byte[] main = jar.getInputStream(jar.getJarEntry("io/github/oiltea94/dbeaverlicensetool/Main.class")).readAllBytes();
                check(ByteBuffer.wrap(main).getShort(6) == 65, "Java 21 bytecode target");
            }
            var run = new ProcessBuilder(JAVA, "-jar", extracted.resolve("dbeaver-license-tool.jar").toString()).redirectErrorStream(true).start();
            String result = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(run.waitFor() == 0 && result.contains("BUILD_TEST_APP"), "packaged JAR runs");
            checkUnixModes(dist.resolve("dbeaver-license-tool-portable.zip"));
            Map<String, byte[]> before = new TreeMap<>();
            try (var files = Files.walk(dist)) { for (Path p : files.filter(Files::isRegularFile).toList()) before.put(dist.relativize(p).toString(), Files.readAllBytes(p)); }
            Files.writeString(source, "invalid Java source");
            invoke(builder, root, false, "--dist", dist.toString());
            for (var entry : before.entrySet()) check(Arrays.equals(entry.getValue(), Files.readAllBytes(dist.resolve(entry.getKey()))), "compile failure preserves " + entry.getKey());
            Files.writeString(source, valid);
            invoke(builder, root, true);
            checkZipOnly(root.resolve("dist"));
            invoke(builder, root, true);
            checkZipOnly(root.resolve("dist"));
            check(!Files.exists(root.resolve("dist-import")), "no retired distribution recreated");
            invoke(builder, root, false, "--unknown");
            System.out.println("BUILD_TEST_PASS checks=" + checks + "; subprocess PATH excludes Python and external build tools");
        } finally {
            try (var paths = Files.walk(work)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }

    private static void checkZipOnly(Path dist) throws IOException {
        try (var files = Files.list(dist)) {
            check(files.map(p -> p.getFileName().toString()).toList().equals(List.of("dbeaver-license-tool-portable.zip")),
                    "distribution contains only the portable ZIP: " + dist);
        }
    }

    private static void invoke(Path builder, Path root, boolean success, String... args) throws Exception {
        var command = new ArrayList<>(List.of(JAVA, builder.toString(), "--root", root.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true);
        process.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
        var child = process.start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = child.waitFor();
        check((exit == 0) == success, "build status " + exit + ": " + output);
    }

    private static void checkUnixModes(Path zip) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        check(buffer.getInt(end) == 0x06054b50, "ZIP end record");
        int cursor = buffer.getInt(end + 16);
        for (int i = 0; i < Short.toUnsignedInt(buffer.getShort(end + 10)); i++) {
            check(buffer.getInt(cursor) == 0x02014b50, "ZIP central entry");
            int length = Short.toUnsignedInt(buffer.getShort(cursor + 28));
            String name = new String(bytes, cursor + 46, length, StandardCharsets.UTF_8);
            int expected = name.equals("run.sh") || name.equals("run.command") ? 0100755 : 0100644;
            check(buffer.getInt(cursor + 38) >>> 16 == expected, "ZIP Unix permissions: " + name);
            cursor += 46 + length + Short.toUnsignedInt(buffer.getShort(cursor + 30)) + Short.toUnsignedInt(buffer.getShort(cursor + 32));
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
