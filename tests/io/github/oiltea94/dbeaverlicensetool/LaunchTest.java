package io.github.oiltea94.dbeaverlicensetool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Starts only a Java probe, never DBeaver. Uses temporary installation fixtures. */
public final class LaunchTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--probe")) {
            System.out.println("probe cwd=" + System.getProperty("user.dir"));
            System.out.println(String.join("\n", args));
            if (args[1].equals("fail")) System.exit(7);
            return;
        }
        I18n.select(I18n.Language.EN, false);
        Path temp = Files.createTempDirectory("launch test & spaces ");
        try {
            Path home = temp.resolve("DBeaver & sample"); Files.createDirectories(home);
            Path license = home.resolve("license & sample.txt"); Files.writeString(license, "test fixture");
            for (String name : List.of("dbeaver.exe", "dbeaver")) executable(home.resolve(name));
            check(ClientLauncher.command(home, license, "Windows 11").equals(List.of(home.resolve("dbeaver.exe").toString())), "Windows normal launch without license argument");
            check(ClientLauncher.command(home, license, "Linux").getFirst().equals(home.resolve("dbeaver").toString()), "Linux executable");
            Path mac = temp.resolve("App Name.app/Contents/Eclipse"); Files.createDirectories(mac);
            executable(mac.resolve("../MacOS/dbeaver").normalize());
            check(ClientLauncher.command(mac, license, "Mac OS X").getFirst().equals(mac.resolve("../MacOS/dbeaver").normalize().toString()), "macOS executable");
            rejects(() -> ClientLauncher.command(temp, license, "Windows 11"), "missing executable");
            rejects(() -> ClientLauncher.command(home, temp.resolve("absent.txt"), "Linux"), "missing license");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = Path.of("license-tool-universal/build/classes").toAbsolutePath().toString();
            ClientLauncher.Result result = ClientLauncher.launch(home, license, builder -> {
                check(builder.command().size()==1, "normal launch has no license argument");
                check(builder.command().getFirst().equals(home.resolve("dbeaver.exe").toString()), "executable spaces and shell characters preserved");
                check(builder.directory().toPath().equals(home), "working directory is selected installation");
                check(builder.redirectErrorStream(), "stderr captured");
                check(builder.redirectOutput().type() == ProcessBuilder.Redirect.Type.APPEND, "log is append-only");
                var command = new ArrayList<>(List.of(java, "-cp", classpath, LaunchTest.class.getName(), "--probe", "success"));
                command.addAll(builder.command()); builder.command(command);
                Process child = builder.start();
                try { check(child.waitFor() == 0, "real probe exits successfully"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                return child;
            });
            check(result.pid() > 0 && Files.readString(result.log()).contains("probe cwd=" + home), "real child output and PID captured");
            check(!Files.readString(result.log()).contains("-license"), "child uses persisted registration");
            try {
                ClientLauncher.launch(home, license, builder -> {
                    builder.command(java, "-cp", classpath, LaunchTest.class.getName(), "--probe", "fail");
                    Process child = builder.start();
                    try { child.waitFor(); } catch (InterruptedException ex) { throw new IOException(ex); }
                    return child;
                });
                throw new AssertionError("Expected startup failure");
            } catch (IOException ex) {
                check(ex.getMessage().contains("code: 7") && ex.getMessage().contains("launch.log"), "failure includes exit code and log path");
            }
            check(Files.readString(result.log()).contains("success"), "previous launch log retained");
            rejects(() -> ClientLauncher.launch(home, license, builder -> { throw new IOException("process start failed"); }), "process creation error propagates");
            Path plugins=home.resolve("plugins"); Files.createDirectories(plugins);
            Path app=plugins.resolve("app.jar"), model=plugins.resolve("model.jar"), api=plugins.resolve("api.jar");
            for (Path p : List.of(app, model, api)) Files.writeString(p,"fixture");
            Target target=new Target(home, plugins, app, model, api, "dbeaver-ee", "26.2", "2026-11-05", "keys/dbeaver-ee-public.key");
            check(!Engine.hasLocalLicense(target), "fresh installation cannot launch with local license");
            rejects(() -> Engine.launch(target), "engine requires installed record before spawning");
            Path state=home.resolve(".local-license-tool/dbeaver-ee"); Files.createDirectories(state);
            Files.writeString(state.resolve("license.txt"), "fixture");
            Files.writeString(state.resolve("manifest.properties"), "status=restored\n");
            check(!Engine.hasLocalLicense(target), "restored installation is not launchable");
            Files.writeString(state.resolve("manifest.properties"), "status=installed\nformat=bad\n");
            rejects(() -> Engine.launch(target), "invalid manifest rejected before spawning");
            if (Arrays.asList(args).contains("--swing")) swing(target, state);
            System.out.println("LaunchTest: " + checks + " checks passed; only Java probe processes started");
        } finally {
            try (var paths=Files.walk(temp)) { for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    @SuppressWarnings("unchecked")
    private static void swing(Target target, Path state) throws Exception {
        var launches = new java.util.concurrent.CopyOnWriteArrayList<Target>();
        DesktopUi ui=edt(() -> new DesktopUi(t -> {
            launches.add(t); return new ClientLauncher.Result(1234, state.resolve("launch.log"));
        }));
        JFrame frame=field(ui,"frame",JFrame.class);
        try {
            edt(() -> {
                frame.setVisible(true);
                check(!field(ui,"autoLaunch",JCheckBox.class).isSelected(), "automatic launch defaults off after persistent import");
                field(ui,"autoLaunch",JCheckBox.class).setSelected(true);
                check(!field(ui,"launch",JButton.class).isEnabled(), "launch initially disabled");
                set(ui,"updatingTargets",true);
                field(ui,"targets",JComboBox.class).addItem(target);
                set(ui,"updatingTargets",false);
                simulateInstall(ui,target,state);
                return null;
            });
            await(ui, launches, 1);
            edt(() -> {
                check(launches.getFirst().equals(target), "auto-launch uses installed target");
                check(field(ui,"launch",JButton.class).isEnabled(), "manual launch enabled after installation");
                check(field(ui,"status",JLabel.class).getText().contains("Launch request submitted"), "launch status shown");
                field(ui,"autoLaunch",JCheckBox.class).setSelected(false);
                simulateInstall(ui,target,state);
                return null;
            });
            edt(() -> { check(launches.size()==1, "unchecked option does not auto-launch"); field(ui,"launch",JButton.class).doClick(); return null; });
            await(ui, launches, 2);
            edt(() -> {
                check(launches.get(1).equals(target), "manual button launches selected target");
                field(ui,"install",JTextField.class).setText("another installation");
                check(!field(ui,"launch",JButton.class).isEnabled(), "path change clears launch target");
                return null;
            });
        } finally { edt(() -> { frame.dispose(); return null; }); }
    }
    private static void simulateInstall(DesktopUi ui,Target t,Path state)throws Exception {
        set(ui,"busy",true);
        var method=DesktopUi.class.getDeclaredMethod("installationFinished",Target.class,Engine.Result.class);method.setAccessible(true);
        method.invoke(ui,t,new Engine.Result(state.resolve("license.txt"),state.resolve("backup"),"fixture installation completed"));
        set(ui,"busy",false);
        var refresh=DesktopUi.class.getDeclaredMethod("refreshControls");refresh.setAccessible(true);refresh.invoke(ui);
    }
    private static void await(DesktopUi ui,List<Target> launches,int size)throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            if(edt(() -> launches.size()==size && !field(ui,"busy",Boolean.class)))return;
            Thread.sleep(30);
        }
        throw new AssertionError("Launch task timed out");
    }
    private static void executable(Path p)throws IOException { Files.createDirectories(p.getParent());Files.write(p,new byte[0]);p.toFile().setExecutable(true); }
    private static <T>T field(Object o,String name,Class<T> type)throws Exception { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o)); }
    private static void set(Object o,String name,Object value)throws Exception { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
    private static <T>T edt(Callable<T> action)throws Exception { var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get(); }
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label);checks++; }
    private static void rejects(Action action,String label)throws Exception { try{action.run();throw new AssertionError(label);}catch(IOException ex){check(true,label);} }
    @FunctionalInterface private interface Action { void run()throws Exception; }
}
