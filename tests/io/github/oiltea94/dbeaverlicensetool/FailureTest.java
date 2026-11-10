package io.github.oiltea94.dbeaverlicensetool;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public class FailureTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path home=Path.of(args[0]);Target target=Engine.scan(home).getFirst();
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var other=generator.generateKeyPair();
        try(var api=new LicenseApi(target)) {
            byte[] license=api.generate(new Profile("Test","test@example.invalid","Test",LocalDate.now().plusYears(1),1),pair.getPrivate(),pair.getPublic());
            api.verify(license,pair.getPublic());check(true,"valid roundtrip");
            boolean failed=false;try{api.verify(license,other.getPublic());}catch(Exception e){failed=true;}check(failed,"wrong key rejected");
            byte[] cipher=LicenseApi.unpem(license);cipher[20]^=1;failed=false;
            try{api.verify(LicenseApi.pem("LICENSE",cipher),pair.getPublic());}catch(Exception e){failed=true;}check(failed,"tamper rejected");
        }
        Path work=Files.createTempDirectory("license-rollback-test-");
        try {
            Path first=work.resolve("first.txt"),second=work.resolve("second.txt"),created=work.resolve("new.txt");
            Files.writeString(first,"original one");Files.writeString(second,"original two");
            var changes=new LinkedHashMap<Path,byte[]>();changes.put(first,"changed one".getBytes());changes.put(second,"changed two".getBytes());changes.put(created,"created".getBytes());
            Class<?> callback=Class.forName("io.github.oiltea94.dbeaverlicensetool.Engine$Check");
            Method transaction=Engine.class.getDeclaredMethod("transaction",LinkedHashMap.class,callback);transaction.setAccessible(true);
            Object fail=Proxy.newProxyInstance(FailureTest.class.getClassLoader(),new Class[]{callback},(proxy,method,values)->{
                check(Files.readString(first).equals("changed one"),"failure occurs after real write");
                throw new IOException("injected readback failure");
            });
            boolean failed=false;try{transaction.invoke(null,changes,fail);}catch(InvocationTargetException e){failed=e.getCause() instanceof IOException;}check(failed,"injected failure propagated");
            check(Files.readString(first).equals("original one"),"first restored");check(Files.readString(second).equals("original two"),"second restored");check(!Files.exists(created),"new file removed on rollback");
            if(System.getProperty("os.name").startsWith("Windows")) {
                Files.setAttribute(second,"dos:readonly",true);
                Object success=Proxy.newProxyInstance(FailureTest.class.getClassLoader(),new Class[]{callback},(proxy,method,values)->null);
                try {
                    failed=false;try{transaction.invoke(null,changes,success);}catch(InvocationTargetException e){failed=e.getCause() instanceof IOException;}
                    check(failed,"read-only target fails mid-transaction");
                    check(Files.readString(first).equals("original one"),"earlier write rolled back after permission failure");
                    check(Files.readString(second).equals("original two"),"read-only target preserved");
                }finally{Files.setAttribute(second,"dos:readonly",false);}
            }
        }finally {
            try(var paths=Files.walk(work)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
        // Recover the known mixed before/after state left by an interrupted installation.
        var result=Engine.activate(target,new Profile("Test","test@example.invalid","Test",LocalDate.now().plusYears(1),1),s->{});
        Path modelBackup=result.backup().resolve(target.modelJar().getFileName());
        Files.copy(modelBackup,target.modelJar(),StandardCopyOption.REPLACE_EXISTING);
        Path manifest=result.backup().getParent().resolve("manifest.properties");var journal=new Properties();
        try(var input=Files.newInputStream(manifest)){journal.load(input);}journal.setProperty("status","prepared");
        try(var output=Files.newOutputStream(manifest)){journal.store(output,"simulated interrupted state");}
        Engine.restore(target,s->{});
        check(Arrays.equals(Files.readAllBytes(target.appJar()),Files.readAllBytes(result.backup().resolve(target.appJar().getFileName()))),"mixed-state app recovery");
        check(Arrays.equals(Files.readAllBytes(target.modelJar()),Files.readAllBytes(modelBackup)),"mixed-state model recovery");
        System.out.println("FAILURE_TEST_PASS checks="+checks);
    }
}
