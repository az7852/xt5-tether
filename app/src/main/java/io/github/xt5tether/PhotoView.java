package io.github.xt5tether;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fast overview followed by source-pixel detail for the current viewport. */
final class PhotoView extends View {
    private Bitmap bitmap, detail;
    private PhotoSource source;
    private ImageGeometry geometry;
    private ImageGeometry.Region wanted, displayed;
    private volatile int generation;
    private boolean busy, disposed;
    private long detailBudget=Math.min(40L*1024*1024,Runtime.getRuntime().maxMemory()/6);
    private final ExecutorService regions=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable load=this::loadDetail;
    private float zoom=1, panX, panY, lastX, lastY;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix transform=new Matrix(), inverse=new Matrix();
    private final RectF bounds=new RectF();
    private final ScaleGestureDetector scale;
    private final GestureDetector gesture;

    PhotoView(Context context) {
        super(context);setContentDescription("拍摄照片；双击查看原图细节，双指缩放，拖动查看");
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(Math.max(1,Math.min(8,zoom*detector.getScaleFactor())),detector.getFocusX(),detector.getFocusY());
                return true;
            }
        });
        gesture=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float target=zoom>1?1:Math.min(8,Math.max(2,1/Math.max(.001f,fit())));
                zoomAt(target,e.getX(),e.getY());return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick();return true; }
        });
    }
    private void zoomAt(float value,float x,float y) {
        float ratio=value/zoom;zoom=value;
        panX=(panX-x+getWidth()/2f)*ratio+x-getWidth()/2f;
        panY=(panY-y+getHeight()/2f)*ratio+y-getHeight()/2f;
        clamp();invalidate();
    }
    void photo(Bitmap value) { photo(value,null); }
    void photo(Bitmap value,PhotoSource original) {
        generation++;wanted=null;displayed=null;main.removeCallbacks(load);
        PhotoSource previous=source;source=original;
        if(previous!=null && previous!=original) regions.execute(previous::close);
        Bitmap old=bitmap;bitmap=value;
        if(old!=null && old!=value) old.recycle();
        if(detail!=null) { detail.recycle();detail=null; }
        geometry=original!=null?original.geometry:value!=null?new ImageGeometry(value.getWidth(),value.getHeight(),1):null;
        zoom=1;panX=panY=0;invalidate();
    }
    void dispose() {
        photo(null);disposed=true;regions.shutdown();
    }
    private float fit() {
        return geometry==null?1:Math.min(getWidth()/(float)geometry.uprightWidth,getHeight()/(float)geometry.uprightHeight);
    }
    private void matrix() {
        float s=fit()*zoom;
        float x=(getWidth()-geometry.uprightWidth*s)/2+panX;
        float y=(getHeight()-geometry.uprightHeight*s)/2+panY;
        transform.setValues(new float[]{geometry.a*s,geometry.b*s,x+geometry.tx*s,
            geometry.c*s,geometry.d*s,y+geometry.ty*s,0,0,1});
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(bitmap==null || getWidth()==0 || getHeight()==0) return;
        matrix();int saved=canvas.save();canvas.concat(transform);
        bounds.set(0,0,geometry.width,geometry.height);canvas.drawBitmap(bitmap,null,bounds,paint);
        if(detail!=null && displayed!=null) {
            bounds.set(displayed.left,displayed.top,displayed.right,displayed.bottom);
            canvas.drawBitmap(detail,null,bounds,paint);
        }
        canvas.restoreToCount(saved);requestDetail();
    }
    private void requestDetail() {
        if(source==null || disposed || !transform.invert(inverse)) return;
        bounds.set(0,0,getWidth(),getHeight());inverse.mapRect(bounds);
        bounds.inset(-16,-16);
        ImageGeometry.Region next=ImageGeometry.region(geometry.width,geometry.height,
            bounds.left,bounds.top,bounds.right,bounds.bottom,fit()*zoom,detailBudget);
        if(next.sample>=source.previewSample) {
            wanted=null;
            if(detail!=null) {detail.recycle();detail=null;displayed=null;invalidate();}
            return;
        }
        if(next.equals(wanted)) return;
        wanted=next;main.removeCallbacks(load);main.postDelayed(load,70);
    }
    private void loadDetail() {
        if(disposed || busy || wanted==null || wanted.equals(displayed)) return;
        final PhotoSource original=source;
        final ImageGeometry.Region request=wanted;
        final int ticket=generation;
        busy=true;
        regions.execute(() -> {
            long began=System.nanoTime();
            Bitmap decoded=null;boolean memoryFailure=false;
            try { if(ticket==generation) decoded=original.decode(request); }
            catch(OutOfMemoryError e) {memoryFailure=true;Log.w("PhotoView","Reducing detail memory",e);}
            catch(IOException | RuntimeException e) {Log.w("PhotoView","Detail unavailable",e);}
            final Bitmap result=decoded;final boolean reduce=memoryFailure;
            final long decodeMillis=(System.nanoTime()-began)/1_000_000;
            main.post(() -> {
                busy=false;
                if(!disposed && ticket==generation && request.equals(wanted)) {
                    if(result!=null) {
                        Bitmap old=detail;detail=result;displayed=request;
                        if(old!=null) old.recycle();
                        Log.d("PhotoView","Detail source="+geometry.width+"x"+geometry.height+
                            " region="+request.left+","+request.top+","+request.right+","+request.bottom+
                            " sample="+request.sample+" bitmap="+result.getWidth()+"x"+result.getHeight()+
                            " viewport="+getWidth()+"x"+getHeight()+" decodeMs="+decodeMillis);
                        invalidate();
                    } else if(reduce && detailBudget>2*1024*1024) {
                        detailBudget=Math.max(2*1024*1024,detailBudget/2);wanted=null;invalidate();
                    }
                } else if(result!=null) result.recycle();
                // Coalesce gestures and photo changes while the one decoder worker is busy.
                if(!disposed && wanted!=null && (ticket!=generation || !request.equals(wanted))) loadDetail();
            });
        });
    }
    private void clamp() {
        if(geometry==null) return;
        float s=fit()*zoom;
        float x=Math.max(0,(geometry.uprightWidth*s-getWidth())/2);
        float y=Math.max(0,(geometry.uprightHeight*s-getHeight())/2);
        panX=Math.max(-x,Math.min(x,panX));panY=Math.max(-y,Math.min(y,panY));
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {clamp();}
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);gesture.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {lastX=event.getX();lastY=event.getY();}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !scale.isInProgress() && event.getPointerCount()==1) {
            panX+=event.getX()-lastX;panY+=event.getY()-lastY;clamp();invalidate();
        }
        lastX=event.getX();lastY=event.getY();return true;
    }
    @Override public boolean performClick() {super.performClick();return true;}
}
