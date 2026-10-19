package io.github.xt5tether;

import android.content.Context;
import android.net.Uri;
import io.github.xt5tether.ptp.CaptureStore;
import java.io.*;
import java.util.Locale;

/** A saved original, either private to the application or owned in MediaStore. */
final class PhotoItem {
    final File file;
    final Uri uri;
    final String key, name;
    final long size, modified;
    final long captureId,received;
    final String queueState;
    final byte[] preview;
    private PhotoItem(File file, Uri uri, String name, long size, long modified) {
        this.file=file; this.uri=uri; this.name=name; this.size=size; this.modified=modified;
        key=file!=null?file.getAbsolutePath():uri.toString();
        captureId=received=0;queueState=null;preview=null;
    }
    private PhotoItem(io.github.xt5tether.ptp.CaptureQueue.Entry entry) {
        file=null;uri=null;name=entry.name;size=entry.total;modified=System.currentTimeMillis();
        captureId=entry.id;received=entry.received;queueState=entry.state;preview=entry.preview;key="pending:"+captureId;
    }
    static PhotoItem pending(io.github.xt5tether.ptp.CaptureQueue.Entry entry) {return new PhotoItem(entry);}
    boolean pending() {return queueState!=null;}
    static PhotoItem local(File file) { return new PhotoItem(file,null,file.getName(),file.length(),file.lastModified()); }
    static PhotoItem media(Uri uri,String name,long size,long modified) { return new PhotoItem(null,uri,name,size,modified); }
    boolean inAlbum() { return uri!=null; }
    boolean raw() { return name.toLowerCase(Locale.ROOT).endsWith(".raf"); }
    String displayName() { return CaptureStore.displayName(new File(name)); }
    String location() { return inAlbum()?(raw()?"下载/XT5Tether":"本地相册") : "App 图库"; }
    InputStream open(Context context) throws IOException {
        if(pending())throw new IOException("原图尚未保存，请等待下载完成");
        InputStream stream=file!=null?new FileInputStream(file):context.getContentResolver().openInputStream(uri);
        if(stream==null) throw new IOException("原图不可用，可能已被移动或删除");
        return stream;
    }
    @Override public boolean equals(Object other) { return other instanceof PhotoItem && key.equals(((PhotoItem)other).key); }
    @Override public int hashCode() { return key.hashCode(); }
}
