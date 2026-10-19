package io.github.xt5tether;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import io.github.xt5tether.ptp.CaptureStore;
import java.io.*;
import java.util.*;

/** MediaStore publication is complete before a camera's volatile capture is released. */
final class PhotoLibrary {
    static final String APP="app", ALBUM="album";
    private final Context context;
    private final ContentResolver resolver;
    PhotoLibrary(Context context) {
        Context application=context.getApplicationContext(); this.context=application==null?context:application;
        resolver=this.context.getContentResolver();
    }
    String destination() { return ALBUM.equals(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("save_location",APP))?ALBUM:APP; }
    void destination(String value) {
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("save_location",ALBUM.equals(value)?ALBUM:APP).apply();
    }
    File directory() {
        File external=context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return new File(external==null?context.getFilesDir():external,"Captures");
    }
    PhotoItem save(File file) throws IOException {
        return ALBUM.equals(destination())?publish(PhotoItem.local(file)):PhotoItem.local(file);
    }
    private static Uri collection(boolean raw) {
        return raw?MediaStore.Downloads.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
    private static String path(boolean raw) { return (raw?Environment.DIRECTORY_DOWNLOADS:Environment.DIRECTORY_PICTURES)+"/XT5Tether/"; }
    private List<PhotoItem> published(boolean raw,String name) throws IOException {
        Uri collection=collection(raw);
        String where=MediaStore.MediaColumns.OWNER_PACKAGE_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.IS_PENDING+"=0";
        ArrayList<String> args=new ArrayList<>(Arrays.asList(context.getPackageName(),path(raw)));
        if(name!=null) { where+=" AND "+MediaStore.MediaColumns.DISPLAY_NAME+"=?"; args.add(name); }
        String[] projection={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED};
        try(Cursor cursor=resolver.query(collection,projection,where,args.toArray(new String[0]),MediaStore.MediaColumns.DATE_MODIFIED+" DESC")) {
            if(cursor==null) throw new IOException("无法读取本应用已保存的相册照片");
            ArrayList<PhotoItem> result=new ArrayList<>();
            while(cursor.moveToNext()) {
                String filename=cursor.getString(1);
                // Prefix distinguishes received originals from unrelated gallery content.
                if(filename==null || !filename.matches("[0-9]+_[0-9a-f]{8}_.+\\.(?i:jpg|jpeg|raf)")) continue;
                result.add(PhotoItem.media(ContentUris.withAppendedId(collection,cursor.getLong(0)),filename,cursor.getLong(2),cursor.getLong(3)*1000));
            }
            return result;
        } catch(RuntimeException failure) { throw new IOException("无法读取本应用相册："+failure.getMessage(),failure); }
    }
    List<PhotoItem> recent() throws IOException {
        Map<String,PhotoItem> items=new HashMap<>();
        for(File file:new CaptureStore(directory()).recent()) items.put(file.getName(),PhotoItem.local(file));
        for(boolean raw:new boolean[]{false,true}) for(PhotoItem item:published(raw,null)) {
            PhotoItem local=items.get(item.name);
            if(local==null || local.size==item.size) items.put(item.name,item);
        }
        ArrayList<PhotoItem> result=new ArrayList<>(items.values());
        result.sort(Comparator.comparingLong((PhotoItem item)->item.modified).thenComparing(item->item.name).reversed()); return result;
    }
    /** Remove only this capture's completed, application-owned local copies. Never touches the camera. */
    void delete(PhotoItem item) throws IOException {
        if(item.pending())throw new IOException("待保存照片不能删除，请先完成保存");
        try {
            File local=localCopy(item.name);
            if(item.file!=null && !item.file.getAbsoluteFile().equals(local))throw new IOException("照片不在 App 图库目录内");
            if(item.inAlbum())checkOwned(item.uri,item.name,item.raw());
            // Validate every copy before the first mutation. Export may have retained a private original.
            LinkedHashSet<Uri> copies=new LinkedHashSet<>();
            for(PhotoItem copy:published(item.raw(),item.name))copies.add(copy.uri);
            if(item.inAlbum())copies.add(item.uri);
            for(Uri uri:copies)checkOwned(uri,item.name,item.raw());
            String where=MediaStore.MediaColumns.OWNER_PACKAGE_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+
                    MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.IS_PENDING+"=0";
            String[] args={context.getPackageName(),path(item.raw()),item.name};
            for(Uri uri:copies) {
                if(resolver.delete(uri,where,args)==0 && checkOwned(uri,item.name,item.raw()))throw new IOException("系统相册未删除照片");
            }
            // Recheck the path after provider calls, and retain the private original if publication deletion failed.
            local=localCopy(item.name);
            if(local.exists() && !local.delete())throw new IOException("无法删除 App 图库中的原图");
        } catch(RuntimeException failure) {throw new IOException("删除失败："+failure.getMessage(),failure);}
    }
    private File localCopy(String name) throws IOException {
        if(name==null || !new File(name).getName().equals(name) || !name.matches(".+\\.(?i:jpg|jpeg|raf)"))throw new IOException("不是完整照片文件");
        File parent=directory().getCanonicalFile(),local=new File(parent,name);
        if(!local.equals(local.getCanonicalFile()) || !parent.equals(local.getCanonicalFile().getParentFile()) || local.isDirectory())throw new IOException("照片不在 App 图库目录内");
        return local.getCanonicalFile();
    }
    /** Returns false only when the record is already gone; foreign or changed records fail closed. */
    private boolean checkOwned(Uri uri,String name,boolean raw) throws IOException {
        List<String> parts=uri.getPathSegments();
        boolean shape=raw?parts.size()==3 && "downloads".equals(parts.get(1)):
                parts.size()==4 && "images".equals(parts.get(1)) && "media".equals(parts.get(2));
        if(!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) || !shape ||
                !("external".equals(parts.get(0)) || "external_primary".equals(parts.get(0))) ||
                !parts.get(parts.size()-1).matches("[1-9][0-9]*") || !name.matches("[0-9]+_[0-9a-f]{8}_.+\\.(?i:jpg|jpeg|raf)"))
            throw new IOException("只允许删除本应用保存的照片");
        String[] columns={MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING};
        try(Cursor c=resolver.query(uri,columns,null,null,null)) {
            if(c==null)throw new IOException("无法核验相册照片归属");
            if(!c.moveToFirst())return false;
            if(!context.getPackageName().equals(c.getString(0)) || !path(raw).equals(c.getString(1)) ||
                    !name.equals(c.getString(2)) || c.getInt(3)!=0)throw new IOException("照片归属或保存位置已改变，未删除");
            return true;
        }
    }
    PhotoItem publish(PhotoItem source) throws IOException {
        if(source.inAlbum()) { verify(source.uri,source.size); return source; }
        Uri pending=null;
        try {
            // Stable received filename makes retry/export idempotent even after process restart.
            for(PhotoItem previous:published(source.raw(),source.name)) {
                if(previous.size==source.size) { verify(previous.uri,source.size); return previous; }
            }
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,source.name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,source.raw()?"image/x-fuji-raf":"image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,path(source.raw()));
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            pending=resolver.insert(collection(source.raw()),values);
            if(pending==null) throw new IOException("无法在本地相册创建文件");
            try(ParcelFileDescriptor descriptor=resolver.openFileDescriptor(pending,"w")) {
                if(descriptor==null) throw new IOException("无法打开相册文件");
                try(InputStream in=source.open(context); ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                    byte[] buffer=new byte[65536]; int n; long bytes=0;
                    while((n=in.read(buffer))!=-1) {out.write(buffer,0,n);bytes+=n;}
                    if(bytes!=source.size || out.getChannel().size()!=source.size) throw new IOException("相册原图长度校验失败");
                    out.getFD().sync();
                }
            }
            verify(pending,source.size);
            ContentValues ready=new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(pending,ready,null,null)!=1) throw new IOException("系统相册未确认保存");
            PhotoItem saved=PhotoItem.media(pending,source.name,source.size,source.modified); pending=null; return saved;
        } catch(IOException|RuntimeException failure) {
            throw new IOException("相册保存失败；完整照片仍保留在 App 图库。"+failure.getMessage(),failure);
        } finally {
            if(pending!=null) try {resolver.delete(pending,null,null);} catch(RuntimeException ignored) { /* Keep the private original. */ }
        }
    }
    private void verify(Uri uri,long expected) throws IOException {
        try(ParcelFileDescriptor descriptor=resolver.openFileDescriptor(uri,"r")) {
            if(descriptor==null || descriptor.getStatSize()!=expected) throw new IOException("相册文件大小校验失败");
        }
    }
}
