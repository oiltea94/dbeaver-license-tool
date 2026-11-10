package io.github.oiltea94.dbeaverlicensetool;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.xml.parsers.DocumentBuilderFactory;

final class Installations {
    static List<Target> scan(Path selected) throws IOException {
        Path path=selected.toAbsolutePath().normalize();
        if(Files.isRegularFile(path))path=path.getParent();
        if(!Files.isDirectory(path))throw new IOException(I18n.t("目录不存在：")+path);
        var homes=new LinkedHashSet<Path>();addHomes(homes,path);
        try(var children=Files.list(path)) {
            for(Path child:children.filter(Files::isDirectory).sorted().toList())addHomes(homes,child);
        }
        List<Target> result=new ArrayList<>();List<String> failures=new ArrayList<>();
        for(Path home:homes) {
            try {result.addAll(read(home));}catch(IOException e){failures.add(home+"："+e.getMessage());}
        }
        if(result.isEmpty()&&!failures.isEmpty())throw new IOException(String.join("\n",failures));
        return result;
    }
    private static void addHomes(Set<Path> homes,Path path) throws IOException {
        for(Path candidate:List.of(path,path.resolve("Contents/Eclipse"),path.resolve("Contents/Resources"),path.resolve("Contents/Resources/app")))
            if(Files.isDirectory(candidate.resolve("plugins")))homes.add(candidate.toRealPath());
        if(path.getFileName()!=null&&path.getFileName().toString().equals("plugins"))homes.add(path.getParent().toRealPath());
    }
    private static List<Path> jars(Path plugins,String prefix) throws IOException {
        try(var paths=Files.list(plugins)) {
            return paths.filter(p->p.getFileName().toString().startsWith(prefix+"_")&&p.toString().endsWith(".jar")&&Files.isRegularFile(p)).sorted().toList();
        }
    }
    private static Path one(List<Path> candidates,String description) throws IOException {
        if(candidates.size()!=1)throw new IOException(description+I18n.t("匹配到 ")+candidates.size()+I18n.t(" 个文件；请选单个完整安装目录"));
        return candidates.getFirst();
    }
    private static List<Target> read(Path home) throws IOException {
        Path plugins=home.resolve("plugins");
        List<Path> appJars;
        try(var paths=Files.list(plugins)) {
            appJars=paths.filter(p->p.getFileName().toString().startsWith("com.dbeaver.app.")&&p.toString().endsWith(".jar")).sorted().toList();
        }
        if(appJars.isEmpty())return List.of();
        Path model=one(jars(plugins,"com.dbeaver.model.license"),I18n.t("授权服务插件"));
        Path api=one(jars(plugins,"com.dbeaver.lm.api"),I18n.t("授权 API 插件"));
        var result=new ArrayList<Target>();
        for(Path app:appJars)try(var jar=new JarFile(app.toFile())) {
            Manifest manifest=jar.getManifest();if(manifest==null)continue;
            String version=manifest.getMainAttributes().getValue("Bundle-Version");
            String release=manifest.getMainAttributes().getValue("Bundle-Release-Date");
            Set<String> applicationIds=applicationIds(jar);
            var entries=jar.entries();
            while(entries.hasMoreElements()) {
                String key=entries.nextElement().getName();
                if(key.startsWith("keys/dbeaver-")&&key.endsWith("-public.key")&&key.indexOf('/',5)<0) {
                    if(version==null||version.isBlank())throw new IOException(I18n.t("产品插件缺少 Bundle-Version"));
                    String product=key.substring(5,key.length()-"-public.key".length());
                    if(!product.matches("dbeaver-[a-z0-9-]{1,32}"))throw new IOException(I18n.t("异常产品资源名"));
                    if(!applicationIds.isEmpty()&&!applicationIds.contains(product))continue;
                    result.add(new Target(home,plugins,app,model,api,product,version,release==null?"":release,key));
                }
            }
        }
        return result;
    }
    private static Set<String> applicationIds(JarFile jar)throws IOException {
        var entry=jar.getJarEntry("plugin.xml");if(entry==null)return Set.of();
        try(var input=jar.getInputStream(entry)) {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var document=factory.newDocumentBuilder().parse(input);var extensions=document.getElementsByTagName("extension");
            Set<String> ids=new HashSet<>();
            for(int i=0;i<extensions.getLength();i++) {
                var extension=(org.w3c.dom.Element)extensions.item(i);
                if(!extension.getAttribute("point").equals("org.jkiss.dbeaver.application"))continue;
                var apps=extension.getElementsByTagName("application");
                for(int j=0;j<apps.getLength();j++){String id=((org.w3c.dom.Element)apps.item(j)).getAttribute("id");if(!id.isBlank())ids.add(id);}
            }
            return ids;
        }catch(Exception e){throw new IOException(I18n.t("产品 plugin.xml 解析失败"),e);}
    }
    static byte[] entry(Path jar,String name) throws IOException {
        try(var file=new JarFile(jar.toFile())) {
            var entry=file.getJarEntry(name);if(entry==null)throw new IOException(jar.getFileName()+I18n.t(" 缺少 ")+name);
            if(entry.getSize()>8*1024*1024)throw new IOException(I18n.t("资源尺寸异常：")+name);
            try(var stream=file.getInputStream(entry)){return stream.readAllBytes();}
        }
    }
    static void checkArchive(Path jar) throws IOException {
        try(var file=new JarFile(jar.toFile())) {
            var seen=new HashSet<String>();var entries=file.entries();
            while(entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                if(!seen.add(name))throw new IOException(I18n.t("JAR 包含重名条目：")+name);
                String upper=name.toUpperCase(Locale.ROOT);
                if(upper.startsWith("META-INF/")&&(upper.endsWith(".SF")||upper.endsWith(".RSA")||upper.endsWith(".DSA")||upper.endsWith(".EC")))
                    throw new IOException(I18n.t("尚未适配：目标插件包含 JAR 数字签名：")+jar.getFileName());
            }
        }
    }
}
