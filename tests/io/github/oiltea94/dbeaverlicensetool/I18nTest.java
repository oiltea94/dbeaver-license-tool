package io.github.oiltea94.dbeaverlicensetool;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Language tests use temporary preferences and never activate a client. */
public final class I18nTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("license-i18n-");
        String originalSettings = System.getProperty("locallicense.settings");
        Locale originalLocale = Locale.getDefault();
        System.setProperty("locallicense.settings", temp.resolve("settings.properties").toString());
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            check(I18n.initialLanguage() == I18n.Language.ZH, "Chinese system default");
            Locale.setDefault(Locale.GERMAN);
            check(I18n.initialLanguage() == I18n.Language.EN, "other system locales fall back to English");
            Files.writeString(I18n.settingsPath(), "language=bad\n");
            check(I18n.initialLanguage() == I18n.Language.EN, "unknown saved locale falls back");
            Files.writeString(I18n.settingsPath(), "language=\\uXXYY\n");
            check(I18n.initialLanguage() == I18n.Language.EN, "malformed settings fall back");
            Files.writeString(I18n.settingsPath(), "unrelated=keep\n");
            I18n.select(I18n.Language.ZH, true);
            check(Files.readString(I18n.settingsPath()).contains("unrelated=keep"), "save preserves unrelated settings");
            check(I18n.initialLanguage() == I18n.Language.ZH, "saved preference overrides system");
            check(I18n.t("扫描版本").equals("扫描版本"), "Chinese lookup");
            I18n.select(I18n.Language.EN, false);
            check(I18n.t("扫描版本").equals("Scan Versions"), "English lookup");
            check(I18n.t("external diagnostic").equals("external diagnostic"), "external message retained");
            for (var entry : Messages.EN.entrySet()) {
                check(!entry.getValue().isBlank(), "nonempty translation: " + entry.getKey());
                check(entry.getValue().codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                        "English catalog has no untranslated Chinese: " + entry.getKey());
            }
            String[] filtered = I18n.arguments(new String[]{"scan", "--install", "C:/名字 with spaces", "--lang", "en"});
            check(Arrays.equals(filtered, new String[]{"scan", "--install", "C:/名字 with spaces"}), "CLI preserves user paths");
            check(I18n.initialLanguage() == I18n.Language.ZH, "CLI does not overwrite saved preference");
            var capture = new ByteArrayOutputStream();
            var output = new PrintStream(capture, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{"--lang", "en", "--help"}, output, output) == 0, "English help succeeds");
            check(capture.toString(StandardCharsets.UTF_8).contains("Global language option"), "English help translated");
            capture.reset();
            check(Main.run(new String[]{"--lang", "zh", "--help"}, output, output) == 0, "Chinese help succeeds");
            check(capture.toString(StandardCharsets.UTF_8).contains("全局语言参数"), "Chinese help translated");
            for (String[] invalid : new String[][]{{"--lang"}, {"--lang", "fr"}, {"--lang", "en", "--lang", "zh"}}) {
                check(Main.run(invalid, output, output) == 2, "invalid language option rejected");
            }
            I18n.select(I18n.Language.EN, false);
            checkFailure(() -> DesktopUi.validateProfile("", "a@b.test", "org", "2099-01-01", "1"), "Name is required");
            checkFailure(() -> DesktopUi.validateProfile("a", "bad", "org", "2099-01-01", "1"), "valid email");
            checkFailure(() -> DesktopUi.validateProfile("a", "a@b.test", "org", "bad", "1"), "YYYY-MM-DD");
            checkFailure(() -> DesktopUi.validateProfile("a", "a@b.test", "org", "2099-01-01", "many"), "positive integer");
            checkFailure(() -> DesktopUi.validateProfile("a", "a@b.test", "org", "2099-01-01", "32768"), "between 1 and 32767");
            checkFailure(() -> Engine.scan(temp.resolve("不存在")), "Directory not found");
            checkFailure(() -> ClassPatch.inspect(new byte[8]), "Not a Java class");
            checkFailure(() -> DesktopUi.validateProfile("张三".repeat(10), "a@b.test", "org", "2099-01-01", "1"), "UTF-8 bytes");
            var profile = DesktopUi.validateProfile("张三", "test@example.org", "研究室", LocalDate.now().plusYears(1).toString(), "2");
            check(profile.name().equals("张三") && profile.company().equals("研究室"), "registration values stay unchanged");
            I18n.select(I18n.Language.ZH, true);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Duser.language=en",
                    "-Dlocallicense.settings=" + I18n.settingsPath(), "-cp", System.getProperty("java.class.path"),
                    "io.github.oiltea94.dbeaverlicensetool.Main", "--help").redirectErrorStream(true).start();
            String restored = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(child.waitFor() == 0 && restored.contains("全局语言参数"), "saved language restored in a fresh JVM");
            if (Arrays.asList(args).contains("--swing")) swing();
            System.out.println("I18nTest: " + checks + " checks passed; no client activation invoked");
        } finally {
            Locale.setDefault(originalLocale);
            if (originalSettings == null) System.clearProperty("locallicense.settings");
            else System.setProperty("locallicense.settings", originalSettings);
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void swing() throws Exception {
        I18n.select(I18n.Language.ZH, false);
        DesktopUi ui = edt(() -> {
            var ctor = DesktopUi.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        });
        JFrame frame = field(ui, "frame", JFrame.class);
        try {
            edt(() -> {
                frame.setVisible(true);
                field(ui, "name", JTextField.class).setText("张三");
                field(ui, "email", JTextField.class).setText("person@example.org");
                field(ui, "company", JTextField.class).setText("测试组织");
                field(ui, "install", JTextField.class).setText("C:/名字 with spaces");
                field(ui, "log", JTextArea.class).setText("原有日志\n");
                String expiry = field(ui, "expires", JTextField.class).getText();
                var updating = DesktopUi.class.getDeclaredField("updatingTargets");
                updating.setAccessible(true); updating.set(ui, true);
                var targets = field(ui, "targets", JComboBox.class);
                Path home = Path.of("sample");
                Target target = new Target(home, home, home, home, home, "dbeaver-ee", "26.2", "2026-11-05", "key");
                targets.addItem(target); targets.setSelectedItem(target);
                updating.set(ui, false);
                var choice = field(ui, "language", JComboBox.class);
                check(choice.getItemCount() == 2, "exactly two selectable languages");
                choice.setSelectedItem(I18n.Language.EN);
                check(frame.getTitle().equals("DBeaver Local License Tool"), "live window title translated");
                check(field(ui, "scan", JButton.class).getText().equals("Scan Versions"), "live button translated");
                check(field(ui, "status", JLabel.class).getText().startsWith("The path changed"), "current dynamic status translated");
                check(field(ui, "expires", JTextField.class).getToolTipText().startsWith("Format:"), "tooltip translated");
                check(field(ui, "name", JTextField.class).getText().equals("张三"), "name preserved on switch");
                check(field(ui, "email", JTextField.class).getText().equals("person@example.org"), "email preserved on switch");
                check(field(ui, "company", JTextField.class).getText().equals("测试组织"), "organization preserved on switch");
                check(field(ui, "expires", JTextField.class).getText().equals(expiry), "expiry preserved on switch");
                check(targets.getSelectedItem() == target, "version selection preserved on switch");
                check(field(ui, "install", JTextField.class).getText().equals("C:/名字 with spaces"), "path preserved on switch");
                check(field(ui, "log", JTextArea.class).getText().equals("原有日志\n"), "historical log preserved");
                check(I18n.initialLanguage() == I18n.Language.EN, "UI choice saved");
                checkLabels(frame);
                snapshot(frame, "i18n-en.png");
                choice.setSelectedItem(I18n.Language.ZH);
                check(frame.getTitle().equals("DBeaver 本地授权工具"), "switch back to Chinese");
                check(field(ui, "status", JLabel.class).getText().startsWith("路径已修改"), "Chinese status restored");
                snapshot(frame, "i18n-zh.png");
                return null;
            });
        } finally { edt(() -> { frame.dispose(); return null; }); }
    }

    private static void checkLabels(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null)
                check(label.getText().codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN), "visible English label: " + label.getText());
            if (child instanceof Container container) checkLabels(container);
        }
    }
    private static void snapshot(JFrame frame, String name) throws Exception {
        frame.validate();
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { frame.paint(graphics); } finally { graphics.dispose(); }
        Path folder = Path.of("license-tool-universal", "build"); Files.createDirectories(folder);
        ImageIO.write(image, "png", folder.resolve(name).toFile());
    }
    private static void checkFailure(Action action, String expected) throws Exception {
        try { action.run(); throw new AssertionError("Expected error: " + expected); }
        catch (IllegalArgumentException | java.io.IOException ex) { check(Main.errorMessage(ex).contains(expected), "localized error: " + Main.errorMessage(ex)); }
    }
    private static <T> T field(Object instance, String name, Class<T> type) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(instance));
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action); SwingUtilities.invokeAndWait(future); return future.get();
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); checks++;
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
