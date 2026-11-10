package io.github.oiltea94.dbeaverlicensetool;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Symbol-aware, length-preserving constructor adaptation. */
final class ClassPatch {
    static final String OWNER="com/dbeaver/model/license/embedded/LicenseServiceEmbedded";
    static final String ENTRY=OWNER+".class";
    static final String VALIDATOR="Lcom/dbeaver/lm/api/LMLicenseValidator;";
    record Result(byte[] bytes, int offset, String before, String after, boolean alreadyLocal) {}
    private final byte[] source;
    private final ByteBuffer in;
    private final Object[] cp;
    private final int[] tags;

    private ClassPatch(byte[] source) throws IOException {
        this.source=source; in=ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
        if(in.getInt()!=0xCAFEBABE) throw new IOException(I18n.t("不是 Java class 文件"));
        int minor=u2(),major=u2();
        if(major<45||major>Runtime.version().feature()+44||minor==65535)throw new IOException(I18n.t("class 版本超出当前 Java 的适配范围"));
        int count=u2(); cp=new Object[count]; tags=new int[count];
        for(int i=1;i<count;i++) {
            int tag=Byte.toUnsignedInt(in.get());tags[i]=tag;
            switch(tag) {
                case 1 -> {
                    int length=u2();byte[] text=new byte[length+2];text[0]=(byte)(length>>>8);text[1]=(byte)length;in.get(text,2,length);
                    cp[i]=new DataInputStream(new ByteArrayInputStream(text)).readUTF();
                }
                case 7,8,16,19,20 -> cp[i]=u2();
                case 9,10,11,12,17,18 -> cp[i]=new int[]{u2(),u2()};
                case 3,4 -> skip(4);
                case 5,6 -> {skip(8);i++;}
                case 15 -> skip(3);
                default -> throw new IOException(I18n.t("未知常量池类型 ")+tag);
            }
        }
    }
    private int u2(){return Short.toUnsignedInt(in.getShort());}
    private void skip(int count) throws IOException {
        if(count<0 || count>in.remaining()) throw new IOException(I18n.t("class 长度异常"));
        in.position(in.position()+count);
    }
    private String utf(int index) throws IOException {
        if(index<=0||index>=cp.length||tags[index]!=1)throw new IOException(I18n.t("无效 UTF8 常量索引"));
        return (String)cp[index];
    }
    private String className(int index) throws IOException {
        if(index<=0||index>=cp.length||tags[index]!=7)throw new IOException(I18n.t("无效 Class 常量索引"));
        return utf((Integer)cp[index]);
    }
    private boolean reference(int index,int tag,String owner,String name,String descriptor) throws IOException {
        if(index<=0||index>=cp.length||tags[index]!=tag) return false;
        int[] ref=(int[])cp[index];int nt=ref[1];
        if(nt<=0||nt>=cp.length||tags[nt]!=12)return false;
        int[] pair=(int[])cp[nt];
        return className(ref[0]).equals(owner)&&utf(pair[0]).equals(name)&&utf(pair[1]).equals(descriptor);
    }
    private void skipAttributes(int count) throws IOException {
        for(int i=0;i<count;i++){u2();skip(in.getInt());}
    }
    private Result adapt() throws IOException {
        u2();String name=className(u2());u2();
        if(!name.equals(OWNER))throw new IOException(I18n.t("许可证服务类名不同"));
        skip(2*u2());int fields=u2();
        for(int i=0;i<fields;i++){skip(6);skipAttributes(u2());}
        int methods=u2();Result candidate=null;
        for(int i=0;i<methods;i++) {
            int access=u2();String method=utf(u2()), descriptor=utf(u2());int attributes=u2();
            for(int j=0;j<attributes;j++) {
                String attribute=utf(u2());int length=in.getInt();int start=in.position();
                if(length<0||length>in.remaining())throw new IOException(I18n.t("属性长度异常"));
                if(method.equals("<init>")&&descriptor.equals("(Z)V")&&attribute.equals("Code")) {
                    if(candidate!=null||(access&0x0508)!=0)throw new IOException(I18n.t("重复或无效构造器"));
                    if(length<21)throw new IOException(I18n.t("构造器 Code 属性过短"));
                    int stack=u2(),locals=u2();
                    if(stack<3||locals<2)throw new IOException(I18n.t("构造器栈或局部变量尺寸不足"));
                    int codeLength=in.getInt();int code=in.position();
                    if(codeLength!=9)throw new IOException(I18n.t("尚未适配：构造器指令结构已变化"));
                    byte[] bytes=Arrays.copyOfRange(source,code,code+9);
                    if(bytes[0]!=0x2a||bytes[1]!=0x1b||(bytes[5]&255)!=0xb7||(bytes[8]&255)!=0xb1)
                        throw new IOException(I18n.t("尚未适配：构造器指令不同"));
                    int constructor=((bytes[6]&255)<<8)|(bytes[7]&255);
                    if(!reference(constructor,10,OWNER,"<init>","(Z"+VALIDATOR+")V"))
                        throw new IOException(I18n.t("尚未适配：委托构造器签名不同"));
                    boolean local=bytes[2]==1&&bytes[3]==0&&bytes[4]==0;
                    int field=((bytes[3]&255)<<8)|(bytes[4]&255);
                    if(!local&&((bytes[2]&255)!=0xb2||!reference(field,9,"com/dbeaver/model/license/validate/PublicLicenseValidator","PUBLIC_LICENSE_VALIDATOR",VALIDATOR)))
                        throw new IOException(I18n.t("尚未适配：联网验证字段引用不同"));
                    byte[] result=source.clone();result[code+2]=1;result[code+3]=0;result[code+4]=0;
                    candidate=new Result(result,code+2,HexFormat.of().formatHex(source,code+2,code+5),"010000",local);
                }
                if(attribute.equals("Code"))validateCode(start,length);
                in.position(start+length);
            }
        }
        skipAttributes(u2());
        if(in.hasRemaining())throw new IOException(I18n.t("class 尾部存在额外数据"));
        if(candidate==null)throw new IOException(I18n.t("尚未适配：未找到许可证服务构造器"));
        return candidate;
    }
    private void validateCode(int start,int length)throws IOException {
        int end=start+length;in.position(start);
        if(length<12)throw new IOException(I18n.t("Code 属性过短"));
        u2();u2();int codeLength=in.getInt();
        if(codeLength<=0||codeLength>65535||codeLength>end-in.position()-4)throw new IOException(I18n.t("Code 指令长度异常"));
        skip(codeLength);int exceptions=u2();
        if(exceptions>(end-in.position()-2)/8)throw new IOException(I18n.t("异常表截断"));
        for(int n=0;n<exceptions;n++) {
            int first=u2(),last=u2(),handler=u2(),type=u2();
            if(first>=last||last>codeLength||handler>=codeLength)throw new IOException(I18n.t("异常表指令边界异常"));
            if(type!=0)className(type);
        }
        int attributes=u2();
        for(int n=0;n<attributes;n++) {
            if(end-in.position()<6)throw new IOException(I18n.t("Code 属性头截断"));
            utf(u2());int nested=in.getInt();
            if(nested<0||nested>end-in.position())throw new IOException(I18n.t("Code 子属性越界"));skip(nested);
        }
        if(in.position()!=end)throw new IOException(I18n.t("Code 属性长度不一致"));
    }
    static Result inspect(byte[] source) throws IOException {
        try {return new ClassPatch(source).adapt();}
        catch(BufferUnderflowException|IndexOutOfBoundsException|ClassCastException e){throw new IOException(I18n.t("class 文件结构损坏"),e);}
    }
}
