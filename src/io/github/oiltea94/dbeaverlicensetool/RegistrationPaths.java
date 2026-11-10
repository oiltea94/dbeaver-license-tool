package io.github.oiltea94.dbeaverlicensetool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/** Matches the inspected RuntimeUtils.getWorkingDirectory rules without OSGi globals. */
final class RegistrationPaths {
    private RegistrationPaths() {}
    static Path directory() throws IOException {
        return resolve(System.getProperty("os.name"), Path.of(System.getProperty("user.home")),
                System.getenv("DBEAVER_DATA"), System.getenv("APPDATA"), System.getProperty("XDG_DATA_HOME"));
    }
    static Path directory(Target target)throws IOException {
        // This adapter is for the inspected embedded storage family. Fail preflight
        // when its directory entry point changes rather than importing elsewhere.
        String service=new String(Installations.entry(target.modelJar(),ClassPatch.ENTRY),StandardCharsets.ISO_8859_1);
        if(!service.contains(".jkiss-lm")||!service.contains("getWorkingDirectory"))
            throw new IOException(I18n.t("尚未适配此版本的授权存储位置。"));
        Path home=Path.of(System.getProperty("user.home"));
        String xdg=System.getProperty("XDG_DATA_HOME");
        Path ini=target.home().resolve("dbeaver.ini");
        if(Files.isRegularFile(ini)) {
            boolean vmargs=false;
            for(String raw:Files.readAllLines(ini)) {
                String line=raw.trim();
                if(line.equals("-vmargs")){vmargs=true;continue;}
                if(!vmargs)continue;
                if(line.startsWith("-Duser.home=")) {
                    home=Path.of(line.substring("-Duser.home=".length()));
                    if(!home.isAbsolute())throw new IOException(I18n.t("客户端 user.home 必须为绝对路径。"));
                }
                if(line.startsWith("-DXDG_DATA_HOME="))xdg=line.substring("-DXDG_DATA_HOME=".length());
            }
        }
        return resolve(System.getProperty("os.name"),home,System.getenv("DBEAVER_DATA"),System.getenv("APPDATA"),xdg);
    }
    static Path resolve(String os, Path home, String data, String appData, String xdg) throws IOException {
        if (data != null && !data.isBlank()) {
            Path base=Path.of(data);
            if(!base.isAbsolute())throw new IOException(I18n.t("DBEAVER_DATA 必须为绝对路径，才能确定普通启动的授权位置。"));
            return base.resolve(".jkiss-lm").normalize();
        }
        String platform=os.toLowerCase(Locale.ROOT);
        if(platform.startsWith("windows"))return (appData==null?home:Path.of(appData)).resolve(".jkiss-lm").toAbsolutePath().normalize();
        if(platform.contains("mac"))return home.resolve("Library/.jkiss-lm").toAbsolutePath().normalize();
        Path base=xdg==null?home.resolve(".local/share"):Path.of(xdg);
        Path standard=base.resolve(".jkiss-lm"),legacy=base.resolve("..jkiss-lm");
        return (Files.exists(standard)||!Files.exists(legacy)?standard:legacy).toAbsolutePath().normalize();
    }
}
