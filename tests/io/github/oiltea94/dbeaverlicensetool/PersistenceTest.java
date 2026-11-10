package io.github.oiltea94.dbeaverlicensetool;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.*;

/** Integration tests use original APIs and a separate JVM, confined to explicit test directories. */
public class PersistenceTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        I18n.select(I18n.Language.EN,false);
        if(args[0].equals("--read")) {
            Target target=Engine.scan(Path.of(args[1])).getFirst();
            PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(LicenseApi.unpem(Installations.entry(target.appJar(),target.keyEntry()))));
            try(var api=new LicenseApi(target)){api.verifyImported(RegistrationPaths.directory(target),Files.readAllBytes(Path.of(args[2])),key);}
            System.out.println("DEFAULT_STORE_FRESH_JVM_PASS (no custom license path, no -license argument)");
            return;
        }
        Path work=Path.of(args[1]).toAbsolutePath();
        Path directory=RegistrationPaths.directory();
        if(!directory.startsWith(work))throw new AssertionError("Tests must use isolated user storage: "+directory);
        Target target=Engine.scan(Path.of(args[0])).getFirst();
        Path originalApp=target.appJar();byte[] appBefore=Files.readAllBytes(originalApp),modelBefore=Files.readAllBytes(target.modelJar());
        Files.createDirectories(directory);
        var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var other=gen.generateKeyPair();
        byte[] priorLicense,priorRecord;
        Path record;
        try(var api=new LicenseApi(target)) {
            priorLicense=api.generate(profile("Prior User"),other.getPrivate(),other.getPublic());
            priorRecord=api.importRecord(priorLicense,other.getPublic());
            record=api.registrationFile(directory,other.getPublic());
        }
        Files.write(record,priorRecord);
        var installed=Engine.activate(target,profile("Registered User"),s->{});
        check(installed.registration().equals(record),"reported persistent file matches default storage");
        check(Engine.hasLocalLicense(target),"persistent installation recognized");
        freshJvm(target,installed.license());
        Path held=record.resolveSibling("temporarily-absent.lic");
        Files.move(record,held);
        try{freshJvm(target,installed.license(),false);}finally{Files.move(held,record);}
        String priorText=Base64.getEncoder().encodeToString(LicenseApi.unpem(priorLicense));
        var journal=Engine.properties(installed.backup().getParent().resolve("manifest.properties"));
        check(Arrays.equals(priorRecord,Files.readAllBytes(installed.backup().resolve(journal.getProperty("import.backup")))),"pre-existing license record backed up exactly");
        check(!Files.readString(record).contains(priorText),"old signatures kept out of active record");
        byte[] first=Files.readAllBytes(installed.license());
        var updated=Engine.activate(target,profile("Updated User"),s->{});
        freshJvm(target,updated.license());
        check(Arrays.equals(priorRecord,Files.readAllBytes(installed.backup().resolve(journal.getProperty("import.backup")))),"original backup retained after repeated import");
        check(!Files.readString(record).contains(Base64.getEncoder().encodeToString(LicenseApi.unpem(first))),"old tool license replaced without accumulating entries");
        check(Files.readString(record).split("<license ",-1).length-1==1,"exactly one current license entry");
        byte[] valid=Files.readAllBytes(record);
        Files.writeString(record,"\n<!-- external edit -->",StandardOpenOption.APPEND);
        byte[] external=Files.readAllBytes(record);
        rejects(()->Engine.activate(target,profile("Blocked"),s->{}),"external record change prevents overwrite");
        rejects(()->Engine.restore(target,s->{}),"external record change prevents destructive restore");
        check(Arrays.equals(external,Files.readAllBytes(record)),"external edit retained");
        Files.write(record,valid);
        Path otherHome=work.resolve("second-install");
        EngineTest.copyPlugins(target.plugins(),otherHome.resolve("plugins"));
        Target second=Engine.scan(otherHome).getFirst();
        byte[] secondApp=Files.readAllBytes(second.appJar());
        rejects(()->Engine.activate(second,profile("Conflict"),s->{}),"shared product owner prevents cross-install overwrite");
        check(Arrays.equals(secondApp,Files.readAllBytes(second.appJar())),"conflicting installation untouched");
        Engine.restore(target,s->{});
        check(Arrays.equals(priorRecord,Files.readAllBytes(record)),"restore reproduces original license record byte-for-byte");
        check(Arrays.equals(appBefore,Files.readAllBytes(originalApp))&&Arrays.equals(modelBefore,Files.readAllBytes(target.modelJar())),"plugin originals restored");
        check(!Engine.hasLocalLicense(target),"restored installation is not reported as imported");
        Files.writeString(record,new String(priorRecord,StandardCharsets.UTF_8)+"\n<!-- updated before reactivation -->");
        byte[] newBaseline=Files.readAllBytes(record);
        Engine.activate(target,profile("New Cycle"),s->{});Engine.restore(target,s->{});
        check(Arrays.equals(newBaseline,Files.readAllBytes(record)),"new activation cycle backs up current record");
        Files.delete(record);
        Engine.activate(target,profile("First Registration"),s->{});
        check(Files.isRegularFile(record),"missing original record created");
        Engine.restore(target,s->{});
        check(!Files.exists(record),"restore removes record when it was originally absent");
        Files.writeString(record,"broken XML");
        rejects(()->Engine.activate(target,profile("Bad Record"),s->{}),"malformed record rejected before plugin writes");
        check(Arrays.equals(appBefore,Files.readAllBytes(originalApp)),"failed import leaves plugins intact");
        check(Files.readString(record).equals("broken XML"),"malformed user record preserved");
        Files.write(record,priorRecord);
        if(System.getProperty("os.name").startsWith("Windows")) {
            Files.setAttribute(record,"dos:readonly",true);
            try {
                rejects(()->Engine.activate(target,profile("Write Failure"),s->{}),"record write failure rolls back installation");
                check(Arrays.equals(appBefore,Files.readAllBytes(originalApp))&&Arrays.equals(modelBefore,Files.readAllBytes(target.modelJar())),"plugins rolled back after record permission failure");
                check(Arrays.equals(priorRecord,Files.readAllBytes(record)),"read-only original record retained");
            }finally{Files.setAttribute(record,"dos:readonly",false);}
        }
        Path home=work.resolve("fake-home");
        check(RegistrationPaths.resolve("Windows 11",home,null,work.resolve("appdata").toString(),null).equals(work.resolve("appdata/.jkiss-lm")),"Windows APPDATA storage");
        check(RegistrationPaths.resolve("Mac OS X",home,null,null,null).equals(home.resolve("Library/.jkiss-lm")),"macOS storage rule");
        check(RegistrationPaths.resolve("Linux",home,null,null,null).equals(home.resolve(".local/share/.jkiss-lm")),"Linux default storage rule");
        Path linux=work.resolve("xdg");Files.createDirectories(linux.resolve("..jkiss-lm"));
        check(RegistrationPaths.resolve("Linux",home,null,null,linux.toString()).equals(linux.resolve("..jkiss-lm")),"legacy Linux double-dot directory selected when present");
        check(RegistrationPaths.resolve("Linux",home,work.resolve("custom").toString(),null,null).equals(work.resolve("custom/.jkiss-lm")),"DBEAVER_DATA overrides platform storage");
        rejects(()->RegistrationPaths.resolve("Windows",home,"relative",null,null),"ambiguous relative data directory rejected");
        Files.delete(record);
        System.out.println("PERSISTENCE_TEST_PASS checks="+checks+"; GUI client launch not performed");
    }
    private static Profile profile(String name){return new Profile(name,"registration@example.invalid","Import Tests",LocalDate.now().plusYears(1),3);}
    private static void freshJvm(Target target,Path license)throws Exception {
        freshJvm(target,license,true);
    }
    private static void freshJvm(Target target,Path license,boolean expected)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(java,"-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-Duser.home="+System.getProperty("user.home"),
                "-cp",System.getProperty("java.class.path"),PersistenceTest.class.getName(),"--read",target.home().toString(),license.toString()).redirectErrorStream(true).start();
        String text=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        int exit=child.waitFor();
        if(expected)check(exit==0 && text.contains("DEFAULT_STORE_FRESH_JVM_PASS"),"fresh JVM reads persistent registration: "+text);
        else check(exit!=0 && !text.contains("DEFAULT_STORE_FRESH_JVM_PASS"),"fresh JVM rejects missing stored record");
        System.out.println(expected?"DEFAULT_STORE_FRESH_JVM_PASS":"MISSING_DEFAULT_STORE_REJECTED");
    }
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void rejects(Action a,String label)throws Exception{boolean failed=false;try{a.run();}catch(Exception expected){failed=true;}check(failed,label);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
