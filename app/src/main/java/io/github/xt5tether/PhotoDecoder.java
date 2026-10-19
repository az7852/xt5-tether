package io.github.xt5tether;

import android.graphics.*;
import android.content.Context;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import io.github.xt5tether.ptp.CaptureStore;

final class PhotoDecoder {
    static Bitmap decode(Context context,PhotoItem item,int edge) throws IOException {
        if(item.file!=null) return decode(item.file,edge);
        if(item.raw()) return decode(rafPreview(context,item),edge);
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        try(InputStream in=item.open(context)) { BitmapFactory.decodeStream(in,null,bounds); }
        Bitmap bitmap;
        try(InputStream in=item.open(context)) { bitmap=BitmapFactory.decodeStream(in,null,options(bounds,edge)); }
        if(bitmap==null) throw new IOException("JPEG 无法解码");
        try(InputStream in=item.open(context)) { return orient(bitmap,new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)); }
        catch(IOException e) { bitmap.recycle(); throw e; }
    }
    static byte[] rafPreview(Context context,PhotoItem item) throws IOException {
        if(item.file!=null) return CaptureStore.rafPreview(item.file);
        try(DataInputStream in=new DataInputStream(item.open(context))) {
            byte[] header=new byte[92]; in.readFully(header);
            if(!new String(header,0,16,StandardCharsets.US_ASCII).startsWith("FUJIFILMCCD-RAW")) throw new IOException("无效 RAF 文件");
            ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            long offset=Integer.toUnsignedLong(b.getInt(84)), length=Integer.toUnsignedLong(b.getInt(88));
            if(offset<92 || length<4 || length>32*1024*1024 || offset+length>item.size) throw new IOException("RAF 没有可用的内嵌 JPEG");
            long skip=offset-92;
            while(skip>0) { long n=in.skip(skip); if(n==0) {if(in.read()<0) throw new EOFException("RAF 预览不完整");n=1;} skip-=n; }
            byte[] jpeg=new byte[(int)length]; in.readFully(jpeg); return jpeg;
        }
    }
    static Bitmap decode(File file, int edge) throws IOException {
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".raf")) return decode(CaptureStore.rafPreview(file), edge);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = options(bounds, edge);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bitmap == null) throw new IOException("JPEG 无法解码");
        return orient(bitmap, new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
    }
    static Bitmap decode(byte[] bytes, int edge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options(bounds, edge));
        if (bitmap == null) throw new IOException("相机未提供可显示的 JPEG 预览");
        int orientation = 1;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) { orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); }
        catch (IOException ignored) { /* Thumbnail may omit EXIF. */ }
        return orient(bitmap, orientation);
    }
    private static BitmapFactory.Options options(BitmapFactory.Options bounds, int edge) throws IOException {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 40000 || bounds.outHeight > 40000) throw new IOException("图片尺寸异常");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2;
        return options;
    }
    private static Bitmap orient(Bitmap bitmap, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
            default: return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (rotated != bitmap) bitmap.recycle(); return rotated;
    }
}
