package io.github.oiltea94.dbeaverlicensetool;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Run with java -ea -cp build/classes io.github.oiltea94.dbeaverlicensetool.UiTest. No display needed. */
public final class UiTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var profile = DesktopUi.validateProfile(" 张三 ", " test@example.org ", " 示例组织 ",
                LocalDate.now().plusDays(90).toString(), "2");
        check(profile.name().equals("张三"), "trim name");
        check(profile.email().equals("test@example.org"), "trim email");
        check(profile.users() == 2, "seat count");
        rejected(() -> DesktopUi.validateProfile("", "a@b.test", "c", "2099-01-01", "1"));
        rejected(() -> DesktopUi.validateProfile("a", "invalid", "c", "2099-01-01", "1"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", "2026-02-30", "1"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", "2099-01-01", "0"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", "2099-01-01", "1.5"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", LocalDate.now().minusDays(1).toString(), "1"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", LocalDate.now().toString(), "1"));
        rejected(() -> DesktopUi.validateProfile("测".repeat(11), "a@b.test", "c", "2099-01-01", "1"));
        rejected(() -> DesktopUi.validateProfile("a", "a@b.test", "c", "2099-01-01", "32768"));
        var options = Main.parseOptions(new String[]{"activate", "--install", "folder with spaces", "--name", "张三"});
        check(options.get("install").equals("folder with spaces"), "spaces in path");
        rejected(() -> Main.parseOptions(new String[]{"scan", "--install"}));
        rejected(() -> Main.parseOptions(new String[]{"scan", "--unknown", "x"}));
        rejected(() -> Main.parseOptions(new String[]{"scan", "--install", "x", "--install", "y"}));
        var first = target("enterprise", "26.1");
        var second = target("ultimate", "26.2");
        check(Main.selectTarget(List.of(first), null) == first, "single target");
        check(Main.selectTarget(List.of(first, second), "ultimate") == second, "explicit product");
        rejected(() -> Main.selectTarget(List.of(first, second), null));
        rejected(() -> Main.selectTarget(List.of(first), "missing"));
        rejected(() -> Main.selectTarget(List.of(first, target("enterprise", "26.2")), "enterprise"));
        var captured = new ByteArrayOutputStream();
        var stream = new PrintStream(captured);
        check(Main.run(new String[]{"--help"}, stream, stream) == 0, "help exit status");
        check(captured.toString().contains("--expires"), "help content");
        check(Main.run(new String[]{"unknown"}, stream, stream) == 2, "invalid command exit status");
        check(Main.run(new String[]{"scan"}, stream, stream) == 2, "missing path exit status");
        if (List.of(args).contains("--swing")) swingSmoke();
        System.out.println("UiTest: " + checks + " checks passed");
    }
    private static void swingSmoke() throws Exception {
        check(!GraphicsEnvironment.isHeadless(), "real graphical environment");
        var instance = onEdt(() -> {
            var constructor = DesktopUi.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        });
        JFrame frame = field(instance, "frame", JFrame.class);
        Path empty = Files.createTempDirectory("license-ui-smoke-");
        Timer dismiss = onEdt(() -> {
            Timer timer = new Timer(50, event -> {
                for (Window window : frame.getOwnedWindows())
                    if (window instanceof JDialog && window.isShowing()) window.dispose();
            });
            timer.start();
            return timer;
        });
        try {
            onEdt(() -> {
                frame.setVisible(true);
                check(frame.isDisplayable() && frame.isShowing(), "real Swing window visible");
                check(!field(instance, "activate", JButton.class).isEnabled(), "activation initially disabled");
                check(!field(instance, "restore", JButton.class).isEnabled(), "restore initially disabled");
                field(instance, "install", JTextField.class).setText(empty.resolve("missing-install").toString());
                field(instance, "scan", JButton.class).doClick();
                check(field(instance, "busy", Boolean.class), "scan runs asynchronously");
                check(!field(instance, "scan", JButton.class).isEnabled(), "concurrent scan disabled");
                check(!field(instance, "install", JTextField.class).isEnabled(), "path editing disabled while busy");
                check(!field(instance, "language", javax.swing.JComboBox.class).isEnabled(), "language switching disabled while busy");
                return null;
            });
            awaitIdle(instance);
            onEdt(() -> {
                check(field(instance, "scan", JButton.class).isEnabled(), "scan restored after failure");
                check(field(instance, "install", JTextField.class).isEnabled(), "path restored after failure");
                check(!field(instance, "activate", JButton.class).isEnabled(), "activation remains disabled after failure");
                check(field(instance, "language", javax.swing.JComboBox.class).isEnabled(), "language switching restored after failure");
                check(field(instance, "status", JLabel.class).getText().equals(I18n.t("操作失败，详细原因见日志。")), "failure status visible");
                check(field(instance, "log", JTextArea.class).getText().contains("missing-install"), "real failure logged");
                field(instance, "install", JTextField.class).setText(empty.toString());
                field(instance, "scan", JButton.class).doClick();
                return null;
            });
            awaitIdle(instance);
            onEdt(() -> {
                check(field(instance, "status", JLabel.class).getText().equals(I18n.t("未找到可识别的 DBeaver 安装。")), "empty scan recovers successfully");
                check(field(instance, "scan", JButton.class).isEnabled(), "scan restored after success");
                check(!field(instance, "detect", JButton.class).isEnabled(), "inspect disabled without target");
                frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
                check(!frame.isDisplayable(), "window closes normally");
                return null;
            });
            System.out.println("Swing smoke: real window create/close; background scan failure and recovery verified; no activation invoked.");
        } finally {
            onEdt(() -> {
                dismiss.stop();
                for (Window owned : frame.getOwnedWindows()) owned.dispose();
                frame.dispose();
                return null;
            });
            Files.delete(empty);
        }
    }
    private static void awaitIdle(DesktopUi instance) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (onEdt(() -> !field(instance, "busy", Boolean.class))) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Swing operation did not finish within 15 seconds");
    }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static <T> T field(DesktopUi instance, String name, Class<T> type) throws Exception {
        var field = DesktopUi.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(instance));
    }
    private static Target target(String product, String version) {
        var root = Path.of("sample", product, version);
        return new Target(root, root, null, null, null, product, version, "", "");
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
