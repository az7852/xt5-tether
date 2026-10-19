package io.github.xt5tether.ptp;

import java.io.*;
import java.util.*;

/** Camera-shutter tethering. No host priority, remote release or live-view stream required. */
public final class TetherEngine {
    public interface Listener {
        void status(String message);
        void ready(String model);
        void thumbnail(byte[] jpeg, String name);
        void progress(String name, long received, long total);
        void saved(File file, long transferMillis);
        void diagnostic(String message);
        default void thumbnail(byte[] jpeg,String name,long captureId) {thumbnail(jpeg,name);}
        default void saved(File file,long transferMillis,long captureId) {saved(file,transferMillis);}
        default void backlog(int pending) { }
        default void queued(long captureId,String name,long size) { }
        default void resetPending() { }
        default void transferStarted(long captureId) { }
        default void transferTiming(long captureId,long queueMillis,long receiveMillis,long publishMillis) { }
        default void progress(String name,long received,long total,long captureId) {progress(name,received,total);}
    }
    public interface Destination {
        void commit(File original) throws IOException;
        default void released(File original) { }
    }
    private final PtpClient camera;
    private final CaptureStore store;
    private final Listener listener;
    private final boolean wireless;
    private final Destination destination;
    private final Map<Long, File> awaitingRelease = new HashMap<>();
    private volatile boolean running = true;
    private long captureSerial=System.nanoTime();
    private volatile boolean downloadsPaused;
    private volatile java.util.function.BooleanSupplier immediateDownloads=() -> false;
    public void immediateDownloads(java.util.function.BooleanSupplier setting) {immediateDownloads=Objects.requireNonNull(setting);}
    private boolean waitForOriginal(long lastNewShot,int pending) {
        return downloadsPaused || !immediateDownloads.getAsBoolean() && System.nanoTime()-lastNewShot<3_000_000_000L && pending<8;
    }
    public void pauseDownloads(boolean paused) {downloadsPaused=paused;}
    public TetherEngine(PtpClient camera, CaptureStore store, Listener listener) {
        this(camera, store, listener, false);
    }
    public TetherEngine(PtpClient camera, CaptureStore store, Listener listener, boolean wireless) {
        this(camera,store,listener,wireless,file -> { });
    }
    public TetherEngine(PtpClient camera, CaptureStore store, Listener listener, boolean wireless, Destination destination) {
        this.camera = camera; this.store = store; this.listener = listener; this.wireless = wireless; this.destination=destination;
    }
    public void stop() { running = false; }
    public void run() throws IOException, InterruptedException {
        listener.status("正在读取 X‑T5 联机能力…");
        PtpClient.DeviceInfo info = camera.open();
        listener.diagnostic("Camera=" + info.manufacturer + " " + info.model + " firmware=" + info.version);
        if (!info.model.toUpperCase(Locale.ROOT).contains("X-T5")) throw new IOException("此版本仅适配 X‑T5，当前设备：" + info.model);
        if (!info.operations.contains(0x1009) || !info.properties.contains(PtpClient.PRIORITY))
            throw new IOException(wireless ? "请将相机设为 WIRELESS TETHER SHOOTING FIXED 后重新连接" : "相机处于读卡器模式，请改为 USB TETHER SHOOTING AUTO 后重新连接");
        // Camera priority keeps the physical shutter and exposure dials usable.
        int priority = camera.property16(PtpClient.PRIORITY);
        if (priority != 1) camera.setProperty16(PtpClient.PRIORITY, 1);
        listener.diagnostic("PartialObject advertised="+info.operations.contains(0x101B));
        listener.ready(info.model);
        if(wireless) {
            try {runQueued();return;}
            catch(PartialUnavailable e) {
                listener.resetPending();
                listener.diagnostic("分段读取不可用："+e.getMessage());
                if(info.operations.contains(0x100A)) {
                    try {runPreviewFirst();return;}
                    catch(PartialUnavailable thumb) {listener.resetPending();listener.diagnostic("独立预览不可用，回退完整文件接收："+thumb.getMessage());}
                }
            }
        }
        while (running) {
            try {
                // Unlike SD-card reader mode, this storage contains only volatile tether captures.
                long[] handles = camera.handles(PtpClient.STILL_STORE);
                Set<Long> present = new HashSet<>();
                for (long handle : handles) present.add(handle);
                awaitingRelease.keySet().retainAll(present);
                // JPEG first where a camera reports RAW+JPEG as separate objects.
                List<Map.Entry<Long, PtpClient.ObjectInfo>> images = new ArrayList<>();
                for (long h : handles) {
                    if (!running) break;
                    if (awaitingRelease.containsKey(h)) { release(h); continue; }
                    if (h == 0x80000001L) continue;
                    try {
                        PtpClient.ObjectInfo o = camera.info(h);
                        if (o.storage != PtpClient.STILL_STORE) throw new IOException("相机返回的存储区不匹配；停止传输以保留原文件");
                        if (o.image()) images.add(new AbstractMap.SimpleEntry<>(h, o));
                    } catch (PtpException e) {
                        if (!e.transientFailure() && e.code != 0x2009) throw e;
                    }
                }
                images.sort(Comparator.comparing(e -> e.getValue().filename.toLowerCase(Locale.ROOT).endsWith(".raf")));
                for (Map.Entry<Long, PtpClient.ObjectInfo> entry : images) {
                    if (!running) break;
                    long handle = entry.getKey(); PtpClient.ObjectInfo o = entry.getValue();
                    long captureId=++captureSerial;
                    listener.queued(captureId,o.filename,o.size);
                    listener.status("正在接收 " + o.filename);
                    long detected=System.nanoTime();
                    String filename=o.filename.toLowerCase(Locale.ROOT);
                    boolean jpegFile=filename.endsWith(".jpg") || filename.endsWith(".jpeg");
                    // JPEG already carries a preview in its header. Avoid a separate camera
                    // thumbnail transaction before the original transfer even starts.
                    if (!jpegFile && info.operations.contains(0x100A)) {
                        try {
                            byte[] preview = camera.thumbnail(handle);
                            listener.diagnostic("GetThumb " + o.filename + " bytes="+preview.length+" afterDetectMs="+(System.nanoTime()-detected)/1_000_000);
                            if (preview.length > 0) listener.thumbnail(preview, o.filename,captureId);
                        }
                        catch (PtpException e) { listener.diagnostic("GetThumb: " + e.getMessage()); }
                    }
                    long began = System.nanoTime();
                    listener.transferStarted(captureId);
                    File saved = store.receive(camera, handle, o, n -> listener.progress(o.filename, n, o.size,captureId),jpeg -> {
                        listener.diagnostic("QuickPreview " + o.filename + " bytes=" + jpeg.length + " afterDetectMs="+(System.nanoTime()-detected)/1_000_000);
                        listener.thumbnail(jpeg,o.filename,captureId);
                    });
                    long downloaded=System.nanoTime();
                    listener.diagnostic("ReceiveIO "+o.filename+" "+camera.lastDownloadDiagnostic());
                    try { destination.commit(saved); }
                    catch(IOException failure) { throw new CaptureSaveException(saved,failure); }
                    listener.diagnostic("Timing " + o.filename + " downloadMs="+(downloaded-began)/1_000_000+
                        " publishMs="+(System.nanoTime()-downloaded)/1_000_000);
                    listener.transferTiming(captureId,0,(downloaded-began)/1_000_000,(System.nanoTime()-downloaded)/1_000_000);
                    awaitingRelease.put(handle, saved);
                    listener.saved(saved, (System.nanoTime() - began) / 1_000_000,captureId);
                    release(handle);
                }
            } catch (PtpException e) {
                if (!e.transientFailure()) throw e;
                listener.diagnostic("Camera busy: " + e.getMessage());
            }
            if (running) Thread.sleep(200);
        }
    }
    private static final class PartialUnavailable extends IOException {
        PartialUnavailable(IOException cause) {super(cause.getMessage(),cause);}
    }
    private static final class PreviewPending {
        final long handle,id,began=System.nanoTime();
        final PtpClient.ObjectInfo info;
        boolean previewed;
        PreviewPending(long handle,long id,PtpClient.ObjectInfo info) {this.handle=handle;this.id=id;this.info=info;}
    }
    /** Cameras without partial reads can prioritize thumbnails while shooting is active.
     * A full GetObject transaction still cannot be interrupted; never pretend otherwise. */
    private void runPreviewFirst() throws IOException,InterruptedException {
        LinkedHashMap<Long,PreviewPending> queue=new LinkedHashMap<>();
        long lastNewShot=0;
        listener.status("连拍优先预览 · 短暂停拍后补原图");
        listener.diagnostic("Preview-first mode; immediateDownloads="+immediateDownloads.getAsBoolean()+"; otherwise originals start after 3000ms quiet or 8 queued objects");
        try {
            while(running) {
                try {
                    long[] handles=camera.handles(PtpClient.STILL_STORE);
                    Set<Long> present=new HashSet<>();for(long h:handles)present.add(h);
                    for(long h:queue.keySet())if(!present.contains(h))throw new IOException("待保存照片从相机缓冲区消失，停止以保留其余原图");
                    awaitingRelease.keySet().retainAll(present);
                    int added=0;
                    for(long h:handles) {
                        if(!running)break;
                        if(awaitingRelease.containsKey(h)) {release(h);continue;}
                        if(h==0x80000001L || queue.containsKey(h) || queue.size()>=32)continue;
                        PtpClient.ObjectInfo o=camera.info(h);
                        if(o.storage!=PtpClient.STILL_STORE)throw new IOException("相机返回的存储区不匹配；停止传输以保留原文件");
                        if(o.image()) {
                            PreviewPending p=new PreviewPending(h,++captureSerial,o);queue.put(h,p);
                            listener.queued(p.id,o.filename,o.size);added++;lastNewShot=System.nanoTime();
                        }
                    }
                    if(added>0)listener.diagnostic("PreviewQueue new="+added+" pending="+queue.size());
                    listener.backlog(queue.size());
                    if(queue.isEmpty()) {Thread.sleep(100);continue;}
                    PreviewPending newest=null;for(PreviewPending p:queue.values())newest=p;
                    if(newest.previewed)for(PreviewPending p:queue.values())if(!p.previewed){newest=p;break;}
                    if(!newest.previewed) {
                        long began=System.nanoTime();byte[] jpeg;
                        try {jpeg=camera.thumbnail(newest.handle);}
                        catch(PtpException e) {
                            if(e.transientFailure())throw e;
                            throw new PartialUnavailable(e);
                        }
                        newest.previewed=true;
                        listener.diagnostic("IndependentPreview "+newest.info.filename+" bytes="+jpeg.length+" fetchMs="+(System.nanoTime()-began)/1_000_000+" pending="+queue.size());
                        if(jpeg.length<4 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216)
                            throw new PartialUnavailable(new IOException("相机未返回独立 JPEG 预览"));
                        listener.thumbnail(jpeg,newest.info.filename,newest.id);
                        // New shots may have arrived during GetThumb: check them before a
                        // long full-object transaction, especially on a slower Wi-Fi link.
                        continue;
                    }
                    if(waitForOriginal(lastNewShot,queue.size())) {
                        listener.status((downloadsPaused?"拍摄优先 · 点击开始保存原图":"优先预览 · 停拍后自动保存")+" · 待保存 "+queue.size()+"/32 张");
                        Thread.sleep(80);continue;
                    }
                    PreviewPending p=queue.values().iterator().next();
                    long transferBegan=System.nanoTime();
                    listener.transferStarted(p.id);
                    File saved=store.receive(camera,p.handle,p.info,n->listener.progress(p.info.filename,n,p.info.size,p.id),jpeg->listener.thumbnail(jpeg,p.info.filename,p.id));
                    long downloaded=System.nanoTime();
                    listener.diagnostic("ReceiveIO "+p.info.filename+" "+camera.lastDownloadDiagnostic());
                    try {destination.commit(saved);}catch(IOException e) {throw new CaptureSaveException(saved,e);}
                    listener.diagnostic("QueueTiming "+p.info.filename+" waitMs="+(transferBegan-p.began)/1_000_000+
                        " receiveMs="+(downloaded-transferBegan)/1_000_000+" publishMs="+(System.nanoTime()-downloaded)/1_000_000+
                        " MBps="+String.format(Locale.ROOT,"%.2f",p.info.size/1048576.0/Math.max(.001,(downloaded-transferBegan)/1e9)));
                    listener.transferTiming(p.id,(transferBegan-p.began)/1_000_000,(downloaded-transferBegan)/1_000_000,(System.nanoTime()-downloaded)/1_000_000);
                    awaitingRelease.put(p.handle,saved);
                    listener.saved(saved,(System.nanoTime()-p.began)/1_000_000,p.id);
                    queue.remove(p.handle);release(p.handle);listener.backlog(queue.size());
                } catch(PtpException e) {
                    if(!e.transientFailure())throw e;
                    listener.diagnostic("Camera busy: "+e.getMessage());Thread.sleep(100);
                }
            }
        } finally {listener.backlog(0);}
    }
    private final class Pending {
        final long handle,id=++captureSerial, began=System.nanoTime();
        final PtpClient.ObjectInfo info;
        final CaptureStore.Incoming incoming;
        boolean previewed;
        Pending(long handle,PtpClient.ObjectInfo info) throws IOException {
            this.handle=handle;this.info=info;
            listener.queued(id,info.filename,info.size);
            incoming=store.begin(info,jpeg -> {
                previewed=true;
                listener.diagnostic("QueuePreview "+info.filename+" id="+id+" afterDetectMs="+(System.nanoTime()-began)/1_000_000);
                listener.thumbnail(jpeg,info.filename,id);
            });
        }
    }
    private void runQueued() throws IOException,InterruptedException {
        LinkedHashMap<Long,Pending> queue=new LinkedHashMap<>();
        long pollAt=0,lastNewShot=0;boolean verifiedPartial=false;
        listener.status("就绪 · 优先预览，原图后台保存");
        try {
            while(running) {
                try {
                    if(System.nanoTime()>=pollAt) {
                        long[] handles=camera.handles(PtpClient.STILL_STORE);
                        Set<Long> present=new HashSet<>();for(long h:handles)present.add(h);
                        for(long h:queue.keySet()) if(!present.contains(h)) throw new IOException("待保存照片从相机缓冲区消失；停止以保留其余原图");
                        awaitingRelease.keySet().retainAll(present);
                        for(long handle:handles) {
                            if(!running)break;
                            if(awaitingRelease.containsKey(handle)) {release(handle);continue;}
                            if(handle==0x80000001L || queue.containsKey(handle) || queue.size()>=32)continue;
                            PtpClient.ObjectInfo o=camera.info(handle);
                            if(o.storage!=PtpClient.STILL_STORE)throw new IOException("相机返回的存储区不匹配；停止传输以保留原文件");
                            if(o.image()){queue.put(handle,new Pending(handle,o));lastNewShot=System.nanoTime();}
                        }
                        listener.backlog(queue.size());
                        pollAt=System.nanoTime()+150_000_000L;
                    }
                    if(queue.isEmpty()) {Thread.sleep(60);continue;}
                    // Fetch newest headers first. Older previews carry lower IDs and cannot
                    // overwrite the newest frame, while originals retain FIFO save order.
                    List<Pending> newest=new ArrayList<>(queue.values());Collections.reverse(newest);
                    Pending next=null;
                    for(Pending p:newest) if(!p.previewed && p.incoming.received()<Math.min(p.info.size,256*1024)) {next=p;break;}
                    if(next==null) {
                        next=queue.values().iterator().next();
                        // Header previews may proceed while paused; timing changes apply before body transfer.
                        if(downloadsPaused || next.incoming.received()<=256*1024 && waitForOriginal(lastNewShot,queue.size())) {Thread.sleep(80);continue;}
                    }
                    long offset=next.incoming.received();
                    int count=(int)Math.min(next.info.size-offset,offset<128*1024?128*1024:256*1024);
                    byte[] bytes;
                    try {bytes=camera.partial(next.handle,offset,count);}
                    catch(PtpException e) {
                        if(!verifiedPartial && (e.code==0x2005 || e.code==0x2006 || e.code==0x201D || e.code==0x2002)) throw new PartialUnavailable(e);
                        throw e;
                    }
                    if(!verifiedPartial) {verifiedPartial=true;listener.diagnostic("PartialObject verified; preview-priority queue active");}
                    next.incoming.append(bytes);
                    listener.progress(next.info.filename,next.incoming.received(),next.info.size,next.id);
                    if(next.incoming.received()==next.info.size) {
                        PtpClient.ObjectInfo current=camera.info(next.handle);
                        if(current.size!=next.info.size || current.storage!=next.info.storage || !current.filename.equals(next.info.filename))
                            throw new IOException("相机对象在传输中变化，拒绝发布或释放");
                        File saved=next.incoming.finish();
                        try {destination.commit(saved);}catch(IOException e) {throw new CaptureSaveException(saved,e);}
                        awaitingRelease.put(next.handle,saved);
                        listener.saved(saved,(System.nanoTime()-next.began)/1_000_000,next.id);
                        queue.remove(next.handle);release(next.handle);listener.backlog(queue.size());
                        pollAt=0;
                    }
                } catch(PtpException e) {
                    if(!e.transientFailure())throw e;
                    listener.diagnostic("Camera busy: "+e.getMessage());Thread.sleep(100);pollAt=0;
                }
            }
        } finally {
            for(Pending p:queue.values()) try {p.incoming.close();}catch(IOException e) {listener.diagnostic("Close partial: "+e.getMessage());}
            listener.backlog(0);
        }
    }
    private void release(long handle) throws IOException {
        File verified = awaitingRelease.get(handle);
        if (verified == null || !verified.isFile() || verified.length() == 0) throw new IOException("本地文件未确认，拒绝释放相机缓冲区");
        try {
            camera.release(handle);
            awaitingRelease.remove(handle);
            destination.released(verified);
        } catch (PtpException e) {
            if (e.code == 0x2009) { awaitingRelease.remove(handle); destination.released(verified); }
            else if (!e.transientFailure()) throw e;
            else listener.diagnostic("等待相机释放缓冲区 " + handle);
        }
    }
}
