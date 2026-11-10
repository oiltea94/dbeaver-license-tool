package io.github.oiltea94.dbeaverlicensetool;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

public final class Engine {
    public record Compatibility(boolean supported,String detail) {}
    public record Result(Path license,Path backup,String summary,Path registration) {
        public Result(Path license,Path backup,String summary){this(license,backup,summary,null);}
    }
    private Engine(){}
    public static List<Target> scan(Path selected)throws IOException{return Installations.scan(selected);}
    public static List<Path> defaultLocations() {
        var result=new LinkedHashSet<Path>();String configured=System.getenv("DBEAVER_HOME");
        if(configured!=null&&!configured.isBlank())result.add(Path.of(configured));
        result.add(Path.of(System.getProperty("user.dir")));
        for(String location:List.of("/Applications","/opt","/usr/share","/usr/local/share"))if(Files.isDirectory(Path.of(location)))result.add(Path.of(location));
        for(String env:List.of("ProgramFiles","LOCALAPPDATA")) {
            String value=System.getenv(env);if(value!=null)for(String name:List.of("DBeaver","DBeaverEE","DBeaverUltimate"))if(Files.isDirectory(Path.of(value,name)))result.add(Path.of(value,name));
        }
        Path home=Path.of(System.getProperty("user.home"));
        for(String part:List.of("Applications","Desktop","桌面"))if(Files.isDirectory(home.resolve(part)))result.add(home.resolve(part));
        return List.copyOf(result);
    }
    public static Compatibility inspect(Target target) {
        try {
            validateTarget(target);Installations.checkArchive(target.appJar());Installations.checkArchive(target.modelJar());
            ClassPatch.Result patch=ClassPatch.inspect(Installations.entry(target.modelJar(),ClassPatch.ENTRY));
            publicKey(Installations.entry(target.appJar(),target.keyEntry()));
            KeyPair pair=newKeys();
            try(var api=new LicenseApi(target)) {
                var profile=new Profile("Compatibility Test","test@example.invalid","Local",LocalDate.now().plusYears(1),1);
                byte[] license=api.generate(profile,pair.getPrivate(),pair.getPublic());api.verify(license,pair.getPublic());
                api.registrationFile(RegistrationPaths.directory(target),pair.getPublic());
            }
            return new Compatibility(true,I18n.t("结构检查通过，目标授权 API 回读有效。")+(patch.alreadyLocal()?I18n.t("当前构造器已为本地验证模式。"):I18n.t("可应用本地验证适配。"))+I18n.t(" 这不代表所有版本或所有操作系统已实测。"));
        }catch(Throwable e){return new Compatibility(false,I18n.t("尚未适配或检查失败：")+LicenseApi.failure(e).getMessage());}
    }
    private static void validateTarget(Target t)throws IOException {
        if(!t.productId().matches("dbeaver-[a-z0-9-]{1,32}"))throw new IOException(I18n.t("产品标识格式异常"));
        Path plugins=t.home().resolve("plugins").toRealPath();
        if(!plugins.equals(t.plugins().toRealPath()))throw new IOException(I18n.t("插件目录不属于选中安装"));
        for(Path file:List.of(t.appJar(),t.modelJar(),t.apiJar()))
            if(!file.toRealPath().getParent().equals(plugins))throw new IOException(I18n.t("插件路径越过安装目录：")+file);
        if(!t.keyEntry().equals("keys/"+t.productId()+"-public.key"))throw new IOException(I18n.t("公钥资源名与产品不一致"));
    }
    private static Path state(Target t){return t.home().resolve(".local-license-tool").resolve(t.productId());}
    static boolean hasLocalLicense(Target target) {
        try {
            Path folder=state(target);
            Properties p=properties(folder.resolve("manifest.properties"));
            return Files.isRegularFile(folder.resolve("license.txt")) && p.getProperty("status", "").equals("installed")
                    && p.getProperty("import.file")!=null && Files.isRegularFile(Path.of(p.getProperty("import.file")));
        } catch(IOException | IllegalArgumentException ex) { return false; }
    }
    static ClientLauncher.Result launch(Target target)throws Exception {
        validateTarget(target);
        Path directory=state(target);
        if(!hasLocalLicense(target))throw new IOException(I18n.t("请先为所选版本生成并安装授权。"));
        try(var channel=FileChannel.open(directory.getParent().resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException(I18n.t("另一个操作尚未完成"));
            ensureStopped(target);
            Properties journal=properties(directory.resolve("manifest.properties"));
            verifyJournal(target,directory,journal);
            Path license=directory.resolve("license.txt");
            if(!journal.getProperty("status", "").equals("installed")
                    ||!hash(Files.readAllBytes(license)).equals(journal.getProperty("license.sha256"))
                    ||!hash(Files.readAllBytes(target.appJar())).equals(journal.getProperty("app.installed"))
                    ||!hash(Files.readAllBytes(target.modelJar())).equals(journal.getProperty("model.installed")))
                throw new IOException(I18n.t("安装状态或授权文件已变化，请重新生成并安装后启动。"));
            try(var store=RegistrationStore.open(target,directory)) {
                store.verifyInstalled(journal);
                try(var api=new LicenseApi(target)){api.verifyImported(store.file.getParent(),Files.readAllBytes(license),publicKey(Installations.entry(target.appJar(),target.keyEntry())));}
                return ClientLauncher.launch(target.home(),store.file,directory,ProcessBuilder::start);
            }
        }
    }
    private static KeyPair newKeys()throws GeneralSecurityException {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();
    }
    private static PublicKey publicKey(byte[] pem)throws GeneralSecurityException {
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(LicenseApi.unpem(pem)));
        if(((java.security.interfaces.RSAKey)key).getModulus().bitLength()!=2048)throw new InvalidKeyException(I18n.t("需要 RSA-2048 公钥"));
        return key;
    }
    private static KeyPair keys(Path directory)throws Exception {
        Path priv=directory.resolve("private.pem"),pub=directory.resolve("public.pem");
        if(Files.exists(priv)) {
            PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(LicenseApi.unpem(Files.readAllBytes(priv))));
            if(!(key instanceof RSAPrivateCrtKey rsa)||rsa.getModulus().bitLength()!=2048)throw new InvalidKeyException(I18n.t("私钥格式不匹配"));
            PublicKey derived=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent()));
            if(Files.exists(pub)&&!Arrays.equals(publicKey(Files.readAllBytes(pub)).getEncoded(),derived.getEncoded()))throw new IOException(I18n.t("已保存密钥对不一致"));
            if(!Files.exists(pub))atomic(pub,LicenseApi.pem("PUBLIC KEY",derived.getEncoded()));
            return new KeyPair(derived,key);
        }
        if(Files.exists(pub))throw new IOException(I18n.t("缺少本地私钥；请保留原密钥，或还原后重新设置"));
        KeyPair pair=newKeys();atomic(priv,LicenseApi.pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
        try{Files.setPosixFilePermissions(priv,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        atomic(pub,LicenseApi.pem("PUBLIC KEY",pair.getPublic().getEncoded()));return pair;
    }
    static boolean matchesProcess(Target target,String command,String line) {
        String normalized=command.replace('\\','/').toLowerCase(Locale.ROOT);
        String arguments=line.replace('\\','/').toLowerCase(Locale.ROOT);
        String home=target.home().toString().replace('\\','/').toLowerCase(Locale.ROOT);
        for(Path executable:List.of(target.home().resolve("dbeaver.exe"),target.home().resolve("dbeaver"),target.home().resolve("../MacOS/dbeaver").normalize()))
            if(normalized.equals(executable.toString().replace('\\','/').toLowerCase(Locale.ROOT)))return true;
        return (normalized.startsWith(home+"/")||arguments.contains(home+"/"))&&arguments.contains("org.eclipse.equinox.launcher");
    }
    private static void ensureStopped(Target target)throws IOException {
        for(ProcessHandle process:ProcessHandle.allProcesses().toList()) {
            if(process.pid()==ProcessHandle.current().pid())continue;
            var info=process.info();String command=info.command().orElse("").toLowerCase(Locale.ROOT);
            String line=info.commandLine().orElse("").toLowerCase(Locale.ROOT);
            if(matchesProcess(target,command,line))
                throw new IOException(I18n.t("请先关闭所选 DBeaver（进程 ")+process.pid()+I18n.t("）"));
        }
    }
    public static Result activate(Target target,Profile profile,Consumer<String> log)throws Exception {
        LicenseApi.validate(profile);validateTarget(target);ensureStopped(target);
        Compatibility check=inspect(target);if(!check.supported())throw new IOException(check.detail());log.accept(check.detail());
        Path directory=state(target);Files.createDirectories(directory);
        try(var channel=FileChannel.open(directory.getParent().resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException(I18n.t("该安装目录正在由另一个工具处理"));
            return activateLocked(target,profile,directory,log);
        }
    }
    private static Result activateLocked(Target target,Profile profile,Path directory,Consumer<String> log)throws Exception {
        try(var store=RegistrationStore.open(target,directory)){return activateRegistered(target,profile,directory,log,store);}
    }
    private static Result activateRegistered(Target target,Profile profile,Path directory,Consumer<String> log,RegistrationStore store)throws Exception {
        Path manifest=directory.resolve("manifest.properties"),backup=directory.resolve("backup");
        Properties journal=Files.exists(manifest)?properties(manifest):new Properties();
        String appName=target.appJar().getFileName().toString(),modelName=target.modelJar().getFileName().toString();
        checkOtherProducts(target,directory);
        if(!journal.isEmpty())verifyJournal(target,directory,journal);
        KeyPair pair=keys(directory);byte[] publicPem=LicenseApi.pem("PUBLIC KEY",pair.getPublic().getEncoded());
        byte[] license;
        try(var api=new LicenseApi(target)){license=api.generate(profile,pair.getPrivate(),pair.getPublic());api.verify(license,pair.getPublic());}
        log.accept(I18n.t("已用所选版本的原始解析器验证姓名、邮箱、单位、日期、席位和产品。"));
        ClassPatch.Result patch=ClassPatch.inspect(Installations.entry(target.modelJar(),ClassPatch.ENTRY));
        byte[] app=rewrite(target.appJar(),target.keyEntry(),publicPem);
        byte[] model=rewrite(target.modelJar(),ClassPatch.ENTRY,patch.bytes());
        Files.createDirectories(backup);
        if(journal.isEmpty()) {
            journal.setProperty("format","1");journal.setProperty("product",target.productId());journal.setProperty("version",target.version());
            journal.setProperty("app.file",appName);journal.setProperty("model.file",modelName);
            for(var item:Map.of("app",target.appJar(),"model",target.modelJar()).entrySet()) {
                byte[] original=Files.readAllBytes(item.getValue());Path saved=backup.resolve(item.getValue().getFileName());
                if(Files.exists(saved)&&!Arrays.equals(original,Files.readAllBytes(saved)))throw new IOException(I18n.t("已有备份不同，保留备份并停止：")+saved);
                if(!Files.exists(saved))atomic(saved,original);
                journal.setProperty(item.getKey()+".original",hash(original));
            }
        }
        journal.setProperty("app.installed",hash(app));journal.setProperty("model.installed",hash(model));
        journal.setProperty("patch.offset",Integer.toString(patch.offset()));journal.putIfAbsent("patch.before",patch.before());journal.setProperty("patch.after",patch.after());
        journal.setProperty("license.sha256",hash(license));
        Path output=directory.resolve("license.txt");
        if(Files.exists(output))atomic(directory.resolve("history/license-"+UUID.randomUUID()+".txt"),Files.readAllBytes(output));
        var changes=new LinkedHashMap<Path,byte[]>();changes.put(target.appJar(),app);changes.put(target.modelJar(),model);
        store.prepare(target,journal,backup,license,pair.getPublic(),changes);
        journal.setProperty("status","installed");
        // A recovery manifest is persisted before plugin writes. Both known hashes are accepted on recovery.
        if(!Files.exists(manifest)){Properties pending=new Properties();pending.putAll(journal);pending.setProperty("status","prepared");atomic(manifest,propertiesBytes(pending));}
        changes.put(output,license);changes.put(manifest,propertiesBytes(journal));
        transaction(changes,()->{
            if(!Arrays.equals(publicKey(Installations.entry(target.appJar(),target.keyEntry())).getEncoded(),pair.getPublic().getEncoded()))throw new IOException(I18n.t("已安装公钥回读不一致"));
            if(!ClassPatch.inspect(Installations.entry(target.modelJar(),ClassPatch.ENTRY)).alreadyLocal())throw new IOException(I18n.t("已安装构造器回读不一致"));
            try(var api=new LicenseApi(target)){
                api.verify(Files.readAllBytes(output),pair.getPublic());
                api.verifyImported(store.file.getParent(),Files.readAllBytes(output),pair.getPublic());
            }
        });
        log.accept(I18n.t("已写入并回读校验两个插件；原始文件备份：")+backup);
        log.accept(I18n.t("授权文件：")+output);
        log.accept(I18n.t("已导入并由独立实例验证注册信息：")+store.file);
        createLaunchers(target,directory,log);
        return new Result(output,backup,I18n.t("注册信息已导入并验证，可从原快捷方式正常启动 DBeaver。"),store.file);
    }
    private static void createLaunchers(Target target,Path directory,Consumer<String> log) {
        try {
            String product=target.productId();
            String sh="#!/bin/sh\nset -eu\nHERE=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\nAPP=$(CDPATH= cd -- \"$HERE/../..\" && pwd)\nif [ -x \"$APP/dbeaver\" ]; then exec \"$APP/dbeaver\"; fi\nif [ -x \"$APP/../MacOS/dbeaver\" ]; then exec \"$APP/../MacOS/dbeaver\"; fi\nprintf '%s\\n' 'DBeaver executable not found.' >&2\nexit 1\n";
            Path shell=directory.resolve("start-dbeaver.sh");atomic(shell,sh.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try{Files.setPosixFilePermissions(shell,PosixFilePermissions.fromString("rwxr-xr-x"));}catch(UnsupportedOperationException ignored){}
            String cmd="@echo off\r\nsetlocal\r\nstart \"DBeaver\" \"%~dp0..\\..\\dbeaver.exe\"\r\n";
            atomic(directory.resolve("start-dbeaver.cmd"),cmd.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }catch(Exception e){log.accept(I18n.t("授权安装已验证，启动脚本写入失败：")+e.getMessage());}
    }
    public static String restore(Target target,Consumer<String> log)throws Exception {
        validateTarget(target);ensureStopped(target);Path directory=state(target);
        if(!Files.isRegularFile(directory.resolve("manifest.properties")))throw new IOException(I18n.t("该安装目录没有本工具的还原记录"));
        try(var channel=FileChannel.open(directory.getParent().resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException(I18n.t("另一个操作尚未完成"));
            checkOtherProducts(target,directory);
            Properties journal=properties(directory.resolve("manifest.properties"));verifyJournal(target,directory,journal);
            try(var store=journal.getProperty("import.file")==null?null:RegistrationStore.open(target,directory)) {
            var changes=new LinkedHashMap<Path,byte[]>();
            changes.put(target.appJar(),Files.readAllBytes(directory.resolve("backup").resolve(journal.getProperty("app.file"))));
            changes.put(target.modelJar(),Files.readAllBytes(directory.resolve("backup").resolve(journal.getProperty("model.file"))));
            if(store!=null)store.restore(journal,directory.resolve("backup"),changes);
            journal.setProperty("status","restored");changes.put(directory.resolve("manifest.properties"),propertiesBytes(journal));
            transaction(changes,()->{
                if(store!=null)store.verifyRestored(journal);
                if(!hash(Files.readAllBytes(target.appJar())).equals(journal.getProperty("app.original"))||!hash(Files.readAllBytes(target.modelJar())).equals(journal.getProperty("model.original")))throw new IOException(I18n.t("还原后哈希不一致"));
            });
            String result=store==null?I18n.t("两个插件已还原为首次使用本工具前的字节，SHA-256 回读一致。密钥、许可证和历史备份已保留。")
                    :I18n.t("插件与导入前的授权记录已还原，回读校验通过。工具中的密钥和历史备份已保留。");
            log.accept(result);return result;
            }
        }
    }
    private static void checkOtherProducts(Target target,Path directory)throws IOException {
        try(var paths=Files.list(directory.getParent())) {
            for(Path sibling:paths.filter(Files::isDirectory).toList()) {
                if(sibling.equals(directory))continue;
                Path record=sibling.resolve("manifest.properties");
                if(!Files.isRegularFile(record))continue;
                Properties p=properties(record);
                boolean shared=p.getProperty("app.file","").equals(target.appJar().getFileName().toString())||p.getProperty("model.file","").equals(target.modelJar().getFileName().toString());
                if(shared&&!p.getProperty("status","").equals("restored"))throw new IOException(I18n.t("同一安装的共享插件已由产品 ")+sibling.getFileName()+I18n.t(" 管理，请先还原该产品，或选择独立安装目录"));
            }
        }
    }
    private static void verifyJournal(Target target,Path directory,Properties p)throws Exception {
        if(!p.getProperty("format","").equals("1")||!p.getProperty("product","").equals(target.productId()))throw new IOException(I18n.t("备份清单格式或产品不匹配"));
        for(var item:Map.of("app",target.appJar(),"model",target.modelJar()).entrySet()) {
            String type=item.getKey(),file=p.getProperty(type+".file","");
            if(!file.equals(item.getValue().getFileName().toString())||!Path.of(file).getFileName().toString().equals(file))throw new IOException(I18n.t("备份清单版本与当前安装不一致"));
            Path saved=directory.resolve("backup").resolve(file);
            if(!Files.isRegularFile(saved)||!hash(Files.readAllBytes(saved)).equals(p.getProperty(type+".original")))throw new IOException(I18n.t("原始备份缺失或哈希不同：")+saved);
            String current=hash(Files.readAllBytes(item.getValue()));
            if(!current.equals(p.getProperty(type+".original"))&&!current.equals(p.getProperty(type+".installed")))throw new IOException(I18n.t("目标文件已被其他程序修改或升级，保留现状：")+item.getValue());
        }
    }
    static Properties properties(Path file)throws IOException {
        var p=new Properties();try(var input=Files.newInputStream(file)){p.load(input);}return p;
    }
    static byte[] propertiesBytes(Properties p)throws IOException {
        var stream=new ByteArrayOutputStream();p.store(stream,"DBeaver local license transaction");return stream.toByteArray();
    }
    static String hash(byte[] data)throws GeneralSecurityException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static byte[] rewrite(Path source,String name,byte[] replacement)throws Exception {
        var buffer=new ByteArrayOutputStream();var expected=new LinkedHashMap<String,byte[]>();
        try(var input=new ZipFile(source.toFile());var output=new ZipOutputStream(buffer)) {
            if(input.getComment()!=null)output.setComment(input.getComment());var entries=input.entries();
            while(entries.hasMoreElements()) {
                ZipEntry original=entries.nextElement();byte[] data;
                try(var stream=input.getInputStream(original)){data=original.getName().equals(name)?replacement:stream.readAllBytes();}
                expected.put(original.getName(),data);var entry=new ZipEntry(original);entry.setSize(data.length);
                var crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());entry.setCompressedSize(entry.getMethod()==ZipEntry.STORED?data.length:-1);
                output.putNextEntry(entry);output.write(data);output.closeEntry();
            }
        }
        if(!expected.containsKey(name))throw new IOException(I18n.t("待替换资源不存在：")+name);
        byte[] result=buffer.toByteArray();var seen=new HashSet<String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(result))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                if(!seen.add(entry.getName())||!Arrays.equals(expected.get(entry.getName()),zip.readAllBytes()))throw new IOException(I18n.t("重打包回读验证失败"));
            }
        }
        if(!seen.equals(expected.keySet()))throw new IOException(I18n.t("重打包条目数量变化"));return result;
    }
    static void atomic(Path path,byte[] data)throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        PosixFileAttributes attributes=null;
        try{if(Files.exists(path))attributes=Files.readAttributes(path,PosixFileAttributes.class);}catch(UnsupportedOperationException ignored){}
        Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),".license-tool-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer=java.nio.ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            try {
                if(attributes!=null) {
                    var view=Files.getFileAttributeView(temp,PosixFileAttributeView.class);
                    PosixFileAttributes current=view.readAttributes();
                    if(!current.owner().equals(attributes.owner()))view.setOwner(attributes.owner());
                    if(!current.group().equals(attributes.group()))view.setGroup(attributes.group());
                    view.setPermissions(attributes.permissions());
                } else Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString(path.getFileName().toString().equals("private.pem")?"rw-------":"rw-r--r--"));
            }catch(UnsupportedOperationException ignored){}
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
            if(!Arrays.equals(data,Files.readAllBytes(path)))throw new IOException(I18n.t("文件回读不一致：")+path);
        }finally{Files.deleteIfExists(temp);}
    }
    @FunctionalInterface private interface Check {void run()throws Exception;}
    private static void transaction(LinkedHashMap<Path,byte[]> changes,Check verify)throws Exception {
        var originals=new LinkedHashMap<Path,byte[]>();
        for(Path path:changes.keySet())originals.put(path,Files.exists(path)?Files.readAllBytes(path):null);
        var written=new ArrayList<Path>();
        try {
            for(var entry:changes.entrySet()){
                written.add(entry.getKey());
                if(entry.getValue()==null)Files.deleteIfExists(entry.getKey());else atomic(entry.getKey(),entry.getValue());
            }
            verify.run();
        }catch(Exception error) {
            Collections.reverse(written);
            for(Path path:written)try{if(originals.get(path)==null)Files.deleteIfExists(path);else atomic(path,originals.get(path));}catch(Exception rollback){error.addSuppressed(rollback);}
            throw error;
        }
    }
}
