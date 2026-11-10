package io.github.oiltea94.dbeaverlicensetool;

import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try { args = I18n.arguments(args); }
        catch (Exception ex) { System.err.println(ex.getMessage()); System.exit(2); return; }
        if (args.length == 0) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println(I18n.t("当前环境没有图形显示，请使用命令行；运行 --help 查看参数。"));
                System.exit(2);
            }
            SwingUtilities.invokeLater(DesktopUi::show);
            return;
        }
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            args = I18n.arguments(args);
            if (args.length == 0) args = new String[]{"--help"};
            if (args.length == 1 && Set.of("--help", "-h", "help").contains(args[0])) {
                out.println(I18n.t("DBeaver 本地授权工具"));
                out.println(I18n.t("无参数：启动图形界面；语言菜单可切换中文和英文。"));
                out.println("scan     --install PATH\ninspect  --install PATH [--product ID]\n"
                        + "activate --install PATH [--product ID] --name NAME --email EMAIL\n"
                        + "         --company COMPANY --expires YYYY-MM-DD --users N\n"
                        + "restore  --install PATH [--product ID]\n");
                out.println(I18n.t("全局语言参数：--lang zh 或 --lang en（本次运行有效）。"));
                out.println(I18n.t("PATH 可为安装目录、macOS .app 或包含安装目录的父文件夹。"));
                out.println(I18n.t("找到多个产品时，请使用 --product；同一产品有多个版本时，指定准确安装目录。"));
                out.println(I18n.t("执行前请关闭 DBeaver。结构兼容性以 inspect 的检测结果为准。"));
                return 0;
            }
            String command = args[0];
            if (!Set.of("scan", "inspect", "activate", "restore").contains(command))
                throw new IllegalArgumentException(I18n.t("未知命令：") + command + I18n.t("；运行 --help 查看用法。"));
            Map<String, String> options = parseOptions(args);
            Set<String> allowed = command.equals("activate")
                    ? Set.of("install", "product", "name", "email", "company", "expires", "users")
                    : command.equals("scan") ? Set.of("install") : Set.of("install", "product");
            for (String key : options.keySet())
                if (!allowed.contains(key)) throw new IllegalArgumentException(command + I18n.t(" 不使用参数 --") + key);
            var targets = Engine.scan(Path.of(required(options, "install")));
            if (command.equals("scan")) {
                if (targets.isEmpty()) throw new IllegalArgumentException(I18n.t("所选路径中未找到可识别的 DBeaver 安装。"));
                for (var target : targets) out.println(describe(target));
                return 0;
            }
            Target target = selectTarget(targets, options.get("product"));
            out.println(describe(target));
            if (command.equals("restore")) {
                out.println(Engine.restore(target, out::println));
                return 0;
            }
            var compatibility = Engine.inspect(target);
            out.println((compatibility.supported() ? I18n.t("结构兼容：") : I18n.t("结构不兼容：")) + compatibility.detail());
            if (!compatibility.supported()) return 3;
            if (command.equals("activate")) {
                Profile profile = DesktopUi.validateProfile(required(options, "name"), required(options, "email"),
                        required(options, "company"), required(options, "expires"), required(options, "users"));
                var result = Engine.activate(target, profile, out::println);
                out.println(result.summary());
                out.println(I18n.t("授权文件：") + result.license());
                out.println(I18n.t("备份目录：") + result.backup());
                if(result.registration()!=null)out.println(I18n.t("注册信息保存位置：") + result.registration());
            }
            return 0;
        } catch (IllegalArgumentException ex) {
            err.println(I18n.t("参数或输入错误：") + ex.getMessage());
            return 2;
        } catch (Exception ex) {
            err.println(I18n.t("操作失败：") + errorMessage(ex));
            return 1;
        }
    }

    static Map<String, String> parseOptions(String[] args) {
        var options = new LinkedHashMap<String, String>();
        var known = Set.of("install", "product", "name", "email", "company", "expires", "users");
        for (int i = 1; i < args.length; i += 2) {
            String argument = args[i];
            if (!argument.startsWith("--") || !known.contains(argument.substring(2)))
                throw new IllegalArgumentException(I18n.t("未知参数：") + argument);
            if (i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException(argument + I18n.t(" 缺少值。"));
            if (options.putIfAbsent(argument.substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException(I18n.t("参数重复：") + argument);
        }
        return options;
    }

    static Target selectTarget(List<Target> targets, String product) {
        if (targets.isEmpty()) throw new IllegalArgumentException(I18n.t("所选路径中未找到可识别的 DBeaver 安装。"));
        var selected = product == null ? targets : targets.stream().filter(t -> t.productId().equals(product)).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException(I18n.t("没有匹配的产品：") + product);
        if (selected.size() != 1) throw new IllegalArgumentException(product == null
                ? I18n.t("找到多个安装，请用 --product ID 选择产品，或将 --install 指向准确安装目录。")
                : I18n.t("同一产品存在多个安装，请将 --install 指向准确安装目录。"));
        return selected.getFirst();
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(I18n.t("缺少 --") + key);
        return value;
    }

    static String describe(Target t) { return t.productId() + " | " + t.version() + " | " + t.home(); }
    static String errorMessage(Throwable ex) {
        while ((ex instanceof java.util.concurrent.ExecutionException
                || ex instanceof java.lang.reflect.InvocationTargetException)
                && ex.getCause() != null) ex = ex.getCause();
        return ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
    }
}
