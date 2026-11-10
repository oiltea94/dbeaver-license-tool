package io.github.oiltea94.dbeaverlicensetool;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Plans imported-record changes for the same transaction as the client plugins. */
final class RegistrationStore implements AutoCloseable {
    final Path file;
    private final Path owner, state;
    private final FileChannel channel;
    private final FileLock lock;
    private RegistrationStore(Path file, Path state, FileChannel channel, FileLock lock) {
        this.file=file;this.state=state.toAbsolutePath().normalize();this.channel=channel;this.lock=lock;
        owner=file.resolveSibling(file.getFileName()+".local-license-tool.properties");
    }
    static RegistrationStore open(Target target,Path state)throws Exception {
        Path directory=RegistrationPaths.directory(target);
        Path file;
        try(var api=new LicenseApi(target)){file=api.registrationFile(directory,null);}
        Files.createDirectories(directory);
        FileChannel channel=FileChannel.open(directory.resolve(".local-license-tool.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            FileLock lock=channel.tryLock();
            if(lock==null)throw new IOException(I18n.t("用户授权目录正在由另一个操作使用。"));
            RegistrationStore store=new RegistrationStore(file,state,channel,lock);
            store.checkOwner();
            return store;
        }catch(Throwable error){channel.close();throw error;}
    }
    private void checkOwner()throws Exception {
        if(Files.exists(owner)) {
            Properties p=Engine.properties(owner);
            if(!p.getProperty("state", "").equals(state.toString()))
                throw new IOException(I18n.t("该产品的用户授权记录由另一安装管理，请先还原那一安装：")+p.getProperty("state", ""));
        }
    }
    private void checkJournal(Properties journal,boolean restoring)throws Exception {
        if(journal.getProperty("import.file")==null)return;
        if(!file.toString().equals(journal.getProperty("import.file")))throw new IOException(I18n.t("当前用户授权目录与备份记录不同，已停止写入。"));
        String current=digest(file),before=journal.getProperty("import.original"),after=journal.getProperty("import.installed");
        boolean inactive=journal.getProperty("status", "").equals("restored");
        if(!inactive && !(current.equals(after)||current.equals(before)))throw new IOException(I18n.t("授权记录已被其他程序修改，保留现状：")+file);
        if(restoring && inactive && !current.equals(before))throw new IOException(I18n.t("授权记录已被其他程序修改，保留现状：")+file);
    }
    void prepare(Target target,Properties journal,Path backup,byte[] license,PublicKey key,LinkedHashMap<Path,byte[]> changes)throws Exception {
        checkJournal(journal,false);
        boolean fresh=journal.getProperty("import.file")==null||journal.getProperty("status", "").equals("restored");
        if(fresh) {
            journal.setProperty("import.file",file.toString());
            journal.setProperty("import.original",digest(file));
            String backupName="registration-"+UUID.randomUUID()+".lic";
            journal.setProperty("import.backup",backupName);
            if(Files.exists(file))Engine.atomic(backup.resolve(backupName),Files.readAllBytes(file));
        }
        byte[] record;
        try(var api=new LicenseApi(target)){record=api.importRecord(license,key);}
        if(Files.exists(file))parse(Files.readAllBytes(file),target.productId());
        parse(record,target.productId());
        // The complete prior product record remains in backup. Its signatures may
        // use a different key, so do not mix it into the new active record.
        journal.setProperty("import.installed",Engine.hash(record));
        changes.put(file,record);
        Properties ownership=new Properties();ownership.setProperty("state",state.toString());
        changes.put(owner,Engine.propertiesBytes(ownership));
    }
    void restore(Properties journal,Path backup,LinkedHashMap<Path,byte[]> changes)throws Exception {
        if(journal.getProperty("import.file")==null)return;
        checkJournal(journal,true);
        String original=journal.getProperty("import.original");
        if(original.equals("MISSING"))changes.put(file,null);
        else {
            String name=journal.getProperty("import.backup", "");
            if(name.isBlank()||!Path.of(name).getFileName().toString().equals(name))throw new IOException(I18n.t("授权备份记录无效。"));
            Path saved=backup.resolve(name);
            if(!digest(saved).equals(original))throw new IOException(I18n.t("授权原始备份缺失或哈希不一致。"));
            changes.put(file,Files.readAllBytes(saved));
        }
        changes.put(owner,null);
    }
    void verifyRestored(Properties journal)throws Exception {
        if(journal.getProperty("import.file")!=null && !digest(file).equals(journal.getProperty("import.original")))
            throw new IOException(I18n.t("授权记录还原后回读不一致。"));
    }
    void verifyInstalled(Properties journal)throws Exception {
        checkJournal(journal,false);
        if(!digest(file).equals(journal.getProperty("import.installed")))throw new IOException(I18n.t("授权记录尚未导入，请重新生成并安装。"));
    }
    private static String digest(Path path)throws Exception { return Files.exists(path)?Engine.hash(Files.readAllBytes(path)):"MISSING"; }
    private static Document parse(byte[] xml,String product)throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
            @Override public void error(org.xml.sax.SAXParseException ex)throws org.xml.sax.SAXException{throw ex;}
            @Override public void fatalError(org.xml.sax.SAXParseException ex)throws org.xml.sax.SAXException{throw ex;}
        });
        Document doc=builder.parse(new ByteArrayInputStream(xml));
        if(!doc.getDocumentElement().getTagName().equals("product")||!doc.getDocumentElement().getAttribute("id").equals(product))
            throw new IOException(I18n.t("原授权记录格式或产品不匹配，已保留原文件。"));
        return doc;
    }
    @Override public void close()throws IOException { try{lock.close();}finally{channel.close();} }
}
