package io.github.oiltea94.dbeaverlicensetool;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Loads the selected installation, without redistributing its libraries. */
final class LicenseApi implements AutoCloseable {
    private final URLClassLoader loader;
    private final Class<?> licenseClass, typeClass, productClass, releaseClass, encryptionClass;
    private final Object product;
    private final Target target;
    LicenseApi(Target target) throws Exception {
        this.target=target;
        var jars=new LinkedHashSet<Path>();jars.add(target.apiJar());
        try(var files=Files.list(target.plugins())) {
            var all=files.filter(p->p.toString().endsWith(".jar")).sorted().toList();
            for(Path jar:all)if(jar.getFileName().toString().startsWith("org.jkiss.utils_"))jars.add(jar);
            jars.addAll(all);
        }
        URL[] urls=new URL[jars.size()];int index=0;for(Path p:jars)urls[index++]=p.toUri().toURL();
        loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader());
        try {
            licenseClass=load("LMLicense");typeClass=load("LMLicenseType");productClass=load("LMProduct");
            releaseClass=load("LMProductRelease");encryptionClass=load("LMEncryption");
            Class<?> productType=load("LMProductType");
            LocalDate release=target.releaseDate().matches("\\d{8}")?LocalDate.parse(target.releaseDate(),DateTimeFormatter.BASIC_ISO_DATE):LocalDate.now();
            product=productClass.getConstructor(String.class,String.class,String.class,String.class,String.class,productType,Date.class,String[].class)
                .newInstance(target.productId(),"DB",target.productId(),target.productId(),version(),null,Date.from(release.atStartOfDay(ZoneOffset.UTC).toInstant()),new String[0]);
            licenseClass.getConstructor(byte[].class,Key.class);
            licenseClass.getMethod("getStatusFor",productClass,releaseClass,boolean.class);
        }catch(Throwable e){loader.close();throw failure(e);}
    }
    private Class<?> load(String name)throws ClassNotFoundException{return Class.forName("com.dbeaver.lm.api."+name,true,loader);}
    private String version(){return String.join(".",Arrays.stream(target.version().split("\\.")).limit(3).toList());}
    static Exception failure(Throwable error) {
        while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();
        if(error instanceof UnsupportedClassVersionError)return new IOException(I18n.t("目标安装需要更高版本 Java，请使用该安装包附带的 Java 运行工具"),error);
        return error instanceof Exception e?e:new IOException(I18n.t("授权 API 加载失败：")+error,error);
    }
    static void validate(Profile p) {
        text(p.name(),32,I18n.t("姓名"));text(p.email(),48,I18n.t("邮箱"));text(p.company(),64,I18n.t("单位"));
        if(!p.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException(I18n.t("邮箱格式不正确"));
        if(p.expires()==null||!p.expires().isAfter(LocalDate.now())||p.expires().getYear()>9998)throw new IllegalArgumentException(I18n.t("到期日期应晚于今天，且年份小于 9999"));
        if(p.users()<1||p.users()>32767)throw new IllegalArgumentException(I18n.t("席位数范围为 1–32767"));
    }
    private static void text(String value,int limit,String label) {
        if(value==null||value.isBlank()||!value.equals(value.trim())||value.chars().anyMatch(Character::isISOControl)||value.getBytes(StandardCharsets.UTF_8).length>limit)
            throw new IllegalArgumentException(label+I18n.t("需要有效文本，长度最多 ")+limit+I18n.t(" 个 UTF-8 字节"));
    }
    byte[] generate(Profile profile,PrivateKey key,PublicKey pub)throws Exception {
        validate(profile);
        try {
            Object type=typeClass.getField("LIMITED").get(null);
            Date now=new Date();Date end=Date.from(profile.expires().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1));
            String id="DB"+UUID.randomUUID().toString().replace("-","").substring(0,14);
            Object license=licenseClass.getConstructor(String.class,typeClass,Date.class,Date.class,Date.class,long.class,
                String.class,String.class,String.class,String.class,String.class,String.class)
                .newInstance(id,type,now,now,end,0L,target.productId(),version(),"LOCAL",profile.company(),profile.name(),profile.email());
            licenseClass.getMethod("setUsersNumber",short.class).invoke(license,(short)profile.users());
            byte[] raw=(byte[])licenseClass.getMethod("getData").invoke(license);
            byte[] cipher=(byte[])encryptionClass.getMethod("encrypt",byte[].class,Key.class).invoke(null,raw,key);
            Object read=read(cipher,pub);
            same(read,"getOwnerName",profile.name());same(read,"getOwnerEmail",profile.email());same(read,"getOwnerCompany",profile.company());
            same(read,"getLicenseId",id);same(read,"getProductId",target.productId());same(read,"getUsersNumber",(short)profile.users());
            same(read,"getLicenseEndTimeForPersistence",end);
            return pem("LICENSE",cipher);
        }catch(Throwable e){throw failure(e);}
    }
    void verify(byte[] license,PublicKey key)throws Exception {
        try{read(unpem(license),key);}catch(Throwable e){throw failure(e);}
    }
    private Object manager(Path directory,PublicKey publicKey)throws Exception {
        Class<?> env=load("LMEnvironment"),provider=load("LMKeyProvider"),validator=load("LMLicenseValidator");
        Object keys=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{provider},(object,method,args)->{
            if(method.getName().equals("getDecryptionKey")||method.getName().equals("getEncryptionKey"))return publicKey;
            if(method.getName().equals("toString"))return "Local public key provider";
            if(method.getName().equals("hashCode"))return System.identityHashCode(object);
            if(method.getName().equals("equals"))return object==args[0];
            throw new UnsupportedOperationException(method.getName());
        });
        return load("LMLicenseManager").getConstructor(env,provider,validator,Path.class)
                .newInstance(env.getConstructor().newInstance(),keys,null,directory);
    }
    Path registrationFile(Path directory,PublicKey key)throws Exception {
        Object manager=manager(directory,key);
        Method getter=manager.getClass().getDeclaredMethod("getProductLicensesFile",productClass,boolean.class);
        getter.setAccessible(true);
        Path file=((Path)getter.invoke(manager,product,false)).toAbsolutePath().normalize();
        if(!file.getParent().equals(directory.toAbsolutePath().normalize()))throw new IOException(I18n.t("授权存储文件越过配置目录。"));
        return file;
    }
    byte[] importRecord(byte[] license,PublicKey key)throws Exception {
        Path temp=Files.createTempDirectory("dbeaver-import-");
        try {
            Path file=registrationFile(temp,key);
            // Seed an empty product to prevent fallback to the user's legacy record.
            Files.writeString(file,"<product id=\""+target.productId()+"\"/>");
            Object manager=manager(temp,key),parsed=read(unpem(license),key);
            manager.getClass().getMethod("importLicense",productClass,licenseClass,boolean.class).invoke(manager,product,parsed,false);
            byte[] result=Files.readAllBytes(file);
            verifyImported(temp,license,key);
            return result;
        }catch(Throwable error){throw failure(error);}
        finally {
            try(var files=Files.walk(temp)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
    void verifyImported(Path directory,byte[] expected,PublicKey key)throws Exception {
        try {
            if(!Files.isRegularFile(registrationFile(directory,key)))throw new IOException(I18n.t("授权记录不存在，导入尚未完成。"));
            Object original=read(unpem(expected),key),manager=manager(directory,key);
            String id=(String)licenseClass.getMethod("getLicenseId").invoke(original);
            Object[] licenses=(Object[])manager.getClass().getMethod("getProductLicenses",productClass).invoke(manager,product);
            for(Object license:licenses) {
                if(!id.equals(licenseClass.getMethod("getLicenseId").invoke(license)))continue;
                for(String getter:List.of("getOwnerName","getOwnerEmail","getOwnerCompany","getProductId","getUsersNumber","getLicenseEndTimeForPersistence"))
                    same(license,getter,licenseClass.getMethod(getter).invoke(original));
                Object status=licenseClass.getMethod("getStatusFor",productClass,releaseClass,boolean.class).invoke(license,product,null,true);
                if(!status.toString().equals("VALID"))throw new IOException(I18n.t("目标解析器报告：")+status);
                return;
            }
            throw new IOException(I18n.t("独立读取实例未找到已导入的授权。"));
        }catch(Throwable error){throw failure(error);}
    }
    private Object read(byte[] cipher,PublicKey pub)throws Exception {
        Object result=licenseClass.getConstructor(byte[].class,Key.class).newInstance(cipher,pub);
        Object status=licenseClass.getMethod("getStatusFor",productClass,releaseClass,boolean.class).invoke(result,product,null,true);
        if(!status.toString().equals("VALID"))throw new IOException(I18n.t("目标解析器报告：")+status);
        return result;
    }
    private void same(Object object,String getter,Object expected)throws Exception {
        Object actual=licenseClass.getMethod(getter).invoke(object);
        if(!Objects.equals(expected,actual))throw new IOException(I18n.t("目标格式回读不一致：")+getter);
    }
    static byte[] pem(String type,byte[] data) {
        return ("-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(data)+"\n-----END "+type+"-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    static byte[] unpem(byte[] text) {
        String raw=new String(text,StandardCharsets.US_ASCII).lines().map(String::trim)
            .filter(s->!s.startsWith("-")&&!s.startsWith("#")).reduce("",String::concat).replaceAll("\\s","");
        return Base64.getDecoder().decode(raw);
    }
    @Override public void close()throws IOException{loader.close();}
}
