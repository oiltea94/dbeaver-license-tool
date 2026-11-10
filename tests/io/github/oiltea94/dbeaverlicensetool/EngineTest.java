package io.github.oiltea94.dbeaverlicensetool;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public class EngineTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static void rejects(Throwing action,String name)throws Exception {
        boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}
        check(rejected,name);
    }
    interface Throwing {void run()throws Exception;}
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]).toAbsolutePath();Path work=Path.of(args[1]).toAbsolutePath();Files.createDirectories(work);
        Path home=work.resolve("DBeaver Test With Spaces");Path plugins=home.resolve("plugins");Files.createDirectories(plugins);
        // Only copy original application/model plus dependency JARs needed by the real parser.
        Path original=source.resolve("license-tool/staged/backup");
        try(var list=Files.list(source.resolve("plugins"))) {
            for(Path p:list.filter(p->p.getFileName().toString().matches("(com\\.dbeaver\\.(app\\.enterprise|model\\.license|lm\\.api)|org\\.jkiss\\.utils|com\\.google\\.gson)_.*\\.jar")).toList()) {
                Path input=Files.exists(original.resolve(p.getFileName()))?original.resolve(p.getFileName()):p;
                Files.copy(input,plugins.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Target target=Engine.scan(home).getFirst();
        check(target.productId().equals("dbeaver-ee"),"metadata product");
        check(target.version().equals("26.2.0.202611051500"),"metadata version");
        check(Engine.inspect(target).supported(),"real target structure");
        String appBefore=hash(target.appJar()),modelBefore=hash(target.modelJar());
        var profile=new Profile("测试用户","test@example.invalid","Test Company",LocalDate.now().plusYears(2),3);
        Engine.Result installed=Engine.activate(target,profile,System.out::println);
        check(Files.isRegularFile(installed.license()),"license exists");
        check(!hash(target.appJar()).equals(appBefore),"public resource changed");
        check(!hash(target.modelJar()).equals(modelBefore),"validator constructor changed");
        check(Engine.inspect(target).supported(),"already local structure recognized");
        String firstPublic=hash(target.appJar());
        Engine.Result updated=Engine.activate(target,new Profile("Other","other@example.invalid","Other",LocalDate.now().plusYears(3),2),System.out::println);
        check(hash(target.appJar()).equals(firstPublic),"second activation retains public key");
        check(updated.backup().equals(installed.backup()),"original backup retained");
        Engine.restore(target,System.out::println);
        check(hash(target.appJar()).equals(appBefore),"exact application restore");
        check(hash(target.modelJar()).equals(modelBefore),"exact model restore");
        // Validation must happen before any client write.
        rejects(()->Engine.activate(target,new Profile("测".repeat(11),"test@example.invalid","Test",LocalDate.now().plusYears(1),1),s->{}),"UTF8 overflow rejected");
        rejects(()->Engine.activate(target,new Profile("Test","test@example.invalid","Test",LocalDate.now().minusDays(1),1),s->{}),"expired date rejected");
        check(hash(target.appJar()).equals(appBefore),"invalid profile makes no app mutation");
        // Unknown constructor bytes must fail preflight.
        byte[] cls=Installations.entry(target.modelJar(),ClassPatch.ENTRY);
        var patch=ClassPatch.inspect(cls);byte[] malformed=cls.clone();malformed[patch.offset()-1]=0;
        rejects(()->ClassPatch.inspect(malformed),"unknown constructor rejected");
        check(ClassPatch.inspect(patch.bytes()).alreadyLocal(),"patched class recognized");
        byte[] highVersion=cls.clone();highVersion[6]=0x7f;highVersion[7]=(byte)0xff;
        rejects(()->ClassPatch.inspect(highVersion),"invalid major version rejected");
        byte[] truncated=Arrays.copyOf(cls,patch.offset()+7);
        java.nio.ByteBuffer.wrap(truncated).putInt(patch.offset()-14,17);
        rejects(()->ClassPatch.inspect(truncated),"truncated class rejected");
        rejects(()->ClassPatch.inspect(Arrays.copyOf(cls,cls.length-1)),"class tail truncation rejected");
        byte[] extra=Arrays.copyOf(cls,cls.length+1);
        rejects(()->ClassPatch.inspect(extra),"extra class bytes rejected");
        // macOS layout and Linux directory paths use the same discovery implementation.
        Path mac=work.resolve("DBeaver.app/Contents/Eclipse");Files.createDirectories(mac);
        copyPlugins(plugins,mac.resolve("plugins"));
        check(Engine.scan(work.resolve("DBeaver.app")).getFirst().home().equals(mac.toRealPath()),"mac app layout");
        Target macTarget=Engine.scan(work.resolve("DBeaver.app")).getFirst();
        check(Engine.matchesProcess(macTarget,mac.resolve("../MacOS/dbeaver").normalize().toString(),"dbeaver"),"mac executable process recognized");
        check(!Engine.matchesProcess(macTarget,"/Applications/Other.app/Contents/MacOS/dbeaver","dbeaver"),"unrelated process excluded");
        Path linux=work.resolve("linux/opt/dbeaver");Files.createDirectories(linux);
        copyPlugins(plugins,linux.resolve("plugins"));
        check(!Engine.scan(linux).isEmpty(),"linux layout");
        Engine.activate(target,profile,s->{});
        Path otherRecord=home.resolve(".local-license-tool/dbeaver-other/manifest.properties");Files.createDirectories(otherRecord.getParent());
        var shared=new Properties();shared.setProperty("status","installed");shared.setProperty("model.file",target.modelJar().getFileName().toString());
        try(var stream=Files.newOutputStream(otherRecord)){shared.store(stream,"test");}
        rejects(()->Engine.activate(target,profile,s->{}),"shared product conflict rejected");
        rejects(()->Engine.restore(target,s->{}),"shared product restore conflict rejected");
        Files.delete(otherRecord);
        byte[] valid=Files.readAllBytes(target.appJar());Files.write(target.appJar(),new byte[]{1,2,3});
        rejects(()->Engine.restore(target,s->{}),"external change refuses restore");
        Files.write(target.appJar(),valid);Engine.restore(target,s->{});
        System.out.println("ENGINE_TEST_PASS checks="+checks);
    }
    static void copyPlugins(Path from,Path to)throws IOException {
        Files.createDirectories(to);try(var files=Files.list(from)){for(Path p:files.toList())Files.copy(p,to.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
    }
}
