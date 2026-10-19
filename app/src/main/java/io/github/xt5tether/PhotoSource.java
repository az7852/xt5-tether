package io.github.xt5tether;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

/** Keeps compressed source data and decodes only the visible region, without rotating full bitmaps. */
final class PhotoSource implements Closeable {
    final ImageGeometry geometry;
    final int previewSample;
    final PhotoMetadata metadata;
    private final BitmapRegionDecoder decoder;

    private PhotoSource(BitmapRegionDecoder decoder,int orientation,PhotoMetadata metadata) throws IOException {
        this.decoder=decoder;this.metadata=metadata;
        int w=decoder.getWidth(), h=decoder.getHeight();
        if(w<=0 || h<=0 || w>40000 || h>40000) throw new IOException("图片尺寸异常");
        geometry=new ImageGeometry(w,h,orientation);
        int sample=1;
        while(Math.max(w,h)/sample>2048 || 4L*((w+sample-1)/sample)*((h+sample-1)/sample)>8*1024*1024) sample*=2;
        previewSample=sample;
    }
    @SuppressWarnings("deprecation") // Android 11 needs the boolean overload.
    static PhotoSource open(Context context,PhotoItem item) throws IOException {
        byte[] embedded=item.raw()?PhotoDecoder.rafPreview(context,item):null;
        int orientation;PhotoMetadata metadata=PhotoMetadata.EMPTY;
        try(InputStream in=embedded==null?item.open(context):new ByteArrayInputStream(embedded)) {
            ExifInterface exif=new ExifInterface(in);orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            try {
                metadata=new PhotoMetadata(exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER,Double.NaN),
                    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,Double.NaN),exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0),
                    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH,Double.NaN),exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,Double.NaN),
                    exif.getAttribute(ExifInterface.TAG_MODEL),exif.getAttribute(ExifInterface.TAG_LENS_MODEL),exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE,-1),exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,0),
                    exif.getAttributeInt(ExifInterface.TAG_METERING_MODE,-1),exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM,-1),
                    exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_MODE,-1),exif.getAttributeInt(ExifInterface.TAG_FLASH,-1),
                    exif.getAttributeInt(ExifInterface.TAG_LIGHT_SOURCE,-1),exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE,-1),exif.getAttribute(ExifInterface.TAG_MAKE));
            }catch(RuntimeException ignored){ /* Optional metadata must never prevent viewing the original. */ }
        }
        BitmapRegionDecoder decoder;
        try(InputStream in=embedded==null?item.open(context):new ByteArrayInputStream(embedded)) {
            decoder=Build.VERSION.SDK_INT>=31?BitmapRegionDecoder.newInstance(in):BitmapRegionDecoder.newInstance(in,false);
        }
        if(decoder==null) throw new IOException("无法读取原图");
        try { return new PhotoSource(decoder,orientation,metadata); }
        catch(IOException | RuntimeException | OutOfMemoryError e) { decoder.recycle();throw e; }
    }
    Bitmap preview() throws IOException {
        return decode(new ImageGeometry.Region(0,0,geometry.width,geometry.height,previewSample));
    }
    synchronized Bitmap decode(ImageGeometry.Region region) throws IOException {
        if(decoder.isRecycled()) throw new IOException("原图已关闭");
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=region.sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inScaled=false;
        int decodedWidth=(region.right-region.left+region.sample-1)/region.sample;
        int decodedHeight=(region.bottom-region.top+region.sample-1)/region.sample;
        if(decodedWidth==region.outputWidth && decodedHeight==region.outputHeight)
            return decodeRaw(new Rect(region.left,region.top,region.right,region.bottom),options);
        // Build the exact display-sized bitmap using bounded strips, so a fullscreen
        // tablet never needs an 80–160 MiB intermediate full-resolution photograph.
        Bitmap target=Bitmap.createBitmap(region.outputWidth,region.outputHeight,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(target);
            Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            int rows=Math.max(1,(8*1024*1024/(decodedWidth*4)-4)/8)*8*region.sample;
            float ratio=region.outputHeight/(float)(region.bottom-region.top);
            for(int top=region.top;top<region.bottom;top+=rows) {
                int from=Math.max(region.top,top-2*region.sample);
                int to=Math.min(region.bottom,top+rows+2*region.sample);
                Bitmap strip=decodeRaw(new Rect(region.left,from,region.right,to),options);
                try {canvas.drawBitmap(strip,null,new RectF(0,(from-region.top)*ratio,
                    region.outputWidth,(to-region.top)*ratio),paint);}
                finally {strip.recycle();}
            }
            return target;
        } catch(IOException | RuntimeException | OutOfMemoryError e) {target.recycle();throw e;}
    }
    private Bitmap decodeRaw(Rect region,BitmapFactory.Options options) throws IOException {
        Bitmap bitmap=decoder.decodeRegion(region,options);
        if(bitmap==null) throw new IOException("无法解码照片细节");
        return bitmap;
    }
    @Override public synchronized void close() { if(!decoder.isRecycled()) decoder.recycle(); }
}
