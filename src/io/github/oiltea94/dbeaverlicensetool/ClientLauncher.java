package io.github.oiltea94.dbeaverlicensetool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Starts the selected executable with separate arguments, without a shell. */
final class ClientLauncher {
    record Result(long pid, Path log) {}
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws IOException; }
    private ClientLauncher() {}

    static List<String> command(Path home, Path license, String os) throws IOException {
        home = home.toAbsolutePath().normalize();
        license = license.toAbsolutePath().normalize();
        if (!Files.isRegularFile(license)) throw new IOException(I18n.t("授权文件不存在：") + license);
        String platform = os.toLowerCase(Locale.ROOT);
        Path executable = platform.startsWith("windows") ? home.resolve("dbeaver.exe")
                : platform.contains("mac") ? home.resolve("../MacOS/dbeaver").normalize() : home.resolve("dbeaver");
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IOException(I18n.t("客户端程序不存在或没有执行权限：") + executable);
        return List.of(executable.toString());
    }

    static Result launch(Path home, Path license, Starter starter) throws IOException {
        return launch(home,license,license.toAbsolutePath().getParent(),starter);
    }
    static Result launch(Path home, Path license, Path output, Starter starter) throws IOException {
        List<String> command = command(home, license, System.getProperty("os.name"));
        Path log = output.toAbsolutePath().resolve("launch.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = starter.start(builder);
        process.getOutputStream().close();
        try {
            if (process.waitFor(600, TimeUnit.MILLISECONDS) && process.exitValue() != 0)
                throw new IOException(I18n.t("客户端启动后退出，退出码：") + process.exitValue()
                        + I18n.t("；启动日志：") + log);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("等待启动结果时被中断；启动日志：") + log, ex);
        }
        return new Result(process.pid(), log);
    }
}
