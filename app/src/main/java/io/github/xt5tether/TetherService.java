package io.github.xt5tether;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.usb.*;
import android.os.*;
import android.net.wifi.WifiManager;
import io.github.xt5tether.ptp.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.HashMap;
import java.util.Map;

public final class TetherService extends Service {
    public static final String CONNECT = "io.github.xt5tether.CONNECT", CONNECT_WIFI = "io.github.xt5tether.CONNECT_WIFI", STOP = "io.github.xt5tether.STOP";
    public interface Observer { void changed(); void preview(byte[] jpeg, String name,long captureId); void captured(PhotoItem file, long milliseconds,long captureId); }
    public final class LocalBinder extends Binder { public TetherService service() { return TetherService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile TetherEngine engine;
    private volatile Closeable transport;
    private volatile boolean stopping;
    private Observer observer;
    private UsbDevice selected;
    private boolean active;
    private boolean wireless;
    public String connectionType = "USB", wifiAddress = "";
    public String message = "连接 X‑T5，开始联机拍摄", model = "未连接", error = "";
    public int count, percent;
    public boolean busy, connected;
    public PhotoItem latest;
    public long latestMillis;
    public long latestCaptureId;
    public int pendingCount;
    private long progressAt;
    private final CaptureQueue captureQueue=new CaptureQueue();
    private final Map<Long,Long> transferBegins=new HashMap<>();
    private final Map<Long,String> timingCaptions=new HashMap<>();
    public String savedSummary="";
    public boolean downloadsPaused;
    public boolean receivingOriginal;
    public String receiveSummary="";
    public String receiveSpeed="正在接收";
    public volatile boolean downloadImmediately;
    public void downloadImmediately(boolean enabled) {
        downloadImmediately=enabled;getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("download_immediately",enabled).apply();
        log("Original download mode="+(enabled?"immediate":"after 3000ms quiet")+" (applies to next original)");changed();
    }
    public volatile boolean wifiAcceleration;
    public volatile boolean wifiLowLatency;
    private WifiManager.WifiLock wifiLock;
    public void wifiAcceleration(boolean enabled) {
        wifiAcceleration=enabled;getSharedPreferences("connection",MODE_PRIVATE).edit().putBoolean("wifi_acceleration",enabled).apply();
        log("WiFi acceleration="+enabled+" (applies to current connection)");
    }
    public void wifiLowLatency(boolean enabled) {
        wifiLowLatency=enabled;getSharedPreferences("connection",MODE_PRIVATE).edit().putBoolean("wifi_low_latency",enabled).apply();
        updateWifiLock();
    }
    private void updateWifiLock() {
        boolean wanted=wifiLowLatency && active && wireless;
        try {
            if(wanted && wifiLock==null) {
                WifiManager wifi=getApplicationContext().getSystemService(WifiManager.class);
                WifiManager.WifiLock lease=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,"XT5Tether:Receive");
                lease.setReferenceCounted(false);lease.acquire();wifiLock=lease;
                log("WiFi low latency requested; lockHeld="+lease.isHeld());
            } else if(!wanted && wifiLock!=null) {
                if(wifiLock.isHeld())wifiLock.release();wifiLock=null;log("WiFi low latency released");
            }
        } catch(RuntimeException e) {log("WiFi low latency unavailable: "+e);}
    }
    public java.util.List<PhotoItem> pendingPhotos() {
        java.util.ArrayList<PhotoItem> result=new java.util.ArrayList<>();
        for(CaptureQueue.Entry e:captureQueue.snapshot())result.add(PhotoItem.pending(e));return result;
    }
    public void pauseDownloads(boolean paused) {
        downloadsPaused=paused;TetherEngine activeEngine=engine;if(activeEngine!=null)activeEngine.pauseDownloads(paused);
        captureQueue.waiting(paused);changed();
    }

    @Override public void onCreate() {
        super.onCreate();
        downloadImmediately=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("download_immediately",false);
        wifiAcceleration=getSharedPreferences("connection",MODE_PRIVATE).getBoolean("wifi_acceleration",true);
        wifiLowLatency=getSharedPreferences("connection",MODE_PRIVATE).getBoolean("wifi_low_latency",false);
        NotificationChannel channel = new NotificationChannel("tether", "相机联机传输", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detached, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(detached, filter);
    }
    private final BroadcastReceiver detached = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (selected != null && device != null && selected.getDeviceId() == device.getDeviceId()) {
                message = "USB 已断开，已接收的照片保存在手机中";
                stopConnection(true);
            }
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    public void observe(Observer value) {
        observer=value;
        if(value!=null) {
            value.changed();
            for(CaptureQueue.Entry e:captureQueue.snapshot())if(e.preview!=null && e.id>latestCaptureId) {
                value.preview(e.preview,e.name,e.id);break;
            }
        }
    }
    public void removeObserver(Observer value) { if (observer == value) observer = null; }
    private void changed() { if (observer != null) observer.changed(); }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TetherService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "tether").setSmallIcon(io.github.xt5tether.R.drawable.ic_camera)
                .setContentTitle("XT5 Tether · 联机拍摄").setContentText("按下相机快门后自动接收照片")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "断开", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (STOP.equals(intent.getAction())) { message = "正在暂停传输并断开…"; stopConnection(false); return START_NOT_STICKY; }
        boolean wifi = CONNECT_WIFI.equals(intent.getAction());
        if ((!CONNECT.equals(intent.getAction()) && !wifi) || active) return START_NOT_STICKY;
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        UsbManager manager = getSystemService(UsbManager.class);
        if (!wifi && (device == null || !manager.hasPermission(device))) { error = "USB 未授权，请重新连接"; changed(); stopSelf(); return START_NOT_STICKY; }
        try { startForeground(100, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); }
        catch (RuntimeException e) {
            error = "无法启动联机服务，请保持应用在前台重试：" + e.getMessage();
            log("Foreground start failed: " + e); changed(); stopSelf(); return START_NOT_STICKY;
        }
        selected = wifi ? null : device; wireless = wifi; connectionType = wifi ? "Wi‑Fi" : "USB";
        wifiAddress = ""; active = true; stopping = false; busy = true; connected = false; error = ""; percent = 0;
        updateWifiLock();
        message = "正在连接 X‑T5…"; changed();
        captureQueue.clear();transferBegins.clear();timingCaptions.clear();receiveSummary="";receiveSpeed="正在接收";savedSummary="";
        String host = intent.getStringExtra("camera_ip");
        worker.execute(() -> connect(manager, device, host == null ? "" : host.trim()));
        return START_NOT_STICKY;
    }
    private void connect(UsbManager manager, UsbDevice device, String requested) {
        PowerManager.WakeLock awake = null;
        try {
            if (wireless) {
                awake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"XT5Tether:Wifi");
                awake.acquire(2 * 60 * 60 * 1000L);
            }
            int retries = 0; boolean[] reachedReady = {false}; String host = requested;
            while (!stopping) {
                PtpClient camera = null;
                try {
                    if (wireless) {
                        WifiNetwork network = WifiNetwork.current(this,() -> wifiAcceleration,this::log);
                        log("WiFi local=" + network.localAddress().getHostAddress() + "/" + network.prefixLength());
                        FujiWifiConnection link = new FujiWifiConnection(network); transport = link;
                        FujiWifiIo io = link.connect(host, text -> {
                            log("WiFi: " + text);
                            main.post(() -> { message = text; changed(); });
                        });
                        host = link.endpoint.host;
                        String address = host; main.post(() -> { wifiAddress = address; changed(); });
                        log("WiFi connected " + host + ":" + link.endpoint.port + " camera=" + io.cameraName);
                        camera = new PtpClient(io, io.cameraName);
                    } else {
                        UsbPtpIo usb = new UsbPtpIo(manager,device); transport = usb; camera = new PtpClient(usb);
                    }
                    PhotoLibrary library=new PhotoLibrary(this);
                    Map<File,PhotoItem> committed=new HashMap<>();
                    CaptureStore store = new CaptureStore(captureDirectory());
                    engine = new TetherEngine(camera, store, new TetherEngine.Listener() {
                        public void status(String text) { main.post(() -> { message = text; changed(); }); }
                        public void ready(String name) { reachedReady[0] = true; main.post(() -> { model = name; busy = false; connected = true; message = "就绪 · 请按下相机快门"; changed(); }); }
                        public void thumbnail(byte[] bytes, String name) {thumbnail(bytes,name,0);}
                        public void queued(long id,String name,long size) {main.post(() -> {captureQueue.discovered(id,name,size);captureQueue.waiting(downloadsPaused);changed();});}
                        public void resetPending() {main.post(() -> {captureQueue.clear();transferBegins.clear();timingCaptions.clear();changed();});}
                        public void transferStarted(long id) {
                            log("TransferStart id="+id+" wifiAcceleration="+wifiAcceleration+" wifiLowLatency="+wifiLowLatency);
                            main.post(() -> {receivingOriginal=true;transferBegins.put(id,SystemClock.elapsedRealtime());captureQueue.progress(id,0);changed();});
                        }
                        public void transferTiming(long id,long queued,long received,long published) {main.post(() -> {
                            timingCaptions.put(id,String.format(java.util.Locale.CHINA,"排队 %.1f 秒 · 接收 %.1f 秒 · 保存 %.2f 秒",queued/1000.0,received/1000.0,published/1000.0));
                        });}
                        public void thumbnail(byte[] bytes,String name,long captureId) {main.post(() -> {
                            captureQueue.preview(captureId,bytes);if(observer!=null)observer.preview(bytes,name,captureId);changed();
                        });}
                        public void backlog(int pending) {main.post(() -> {pendingCount=pending;changed();});}
                        public void progress(String name, long received, long total) {progress(name,received,total,0);}
                        public void progress(String name, long received, long total,long id) {
                            long now = SystemClock.elapsedRealtime();
                            if (now - progressAt < 100 && received != total) return;
                            progressAt = now; int p = (int)Math.min(100, received * 100 / total);
                            main.post(() -> {
                                captureQueue.progress(id,received);
                                Long began=transferBegins.get(id);long elapsed=began==null?0:SystemClock.elapsedRealtime()-began;
                                receiveSpeed=elapsed>=300?String.format(java.util.Locale.CHINA,"%.2f MB/s",received/1048576.0/(elapsed/1000.0)):"正在接收";
                                receiveSummary=String.format(java.util.Locale.CHINA,"%.1f / %.1f MB · %s",received/1048576.0,total/1048576.0,receiveSpeed);
                                percent = p; message = "接收 " + name + " · " + p + "% · "+receiveSummary+" · 待保存 "+captureQueue.snapshot().size()+"/32 张";changed();
                            });
                        }
                        public void saved(File file, long ms) {saved(file,ms,0);}
                        public void saved(File file, long ms,long captureId) {
                            PhotoItem item=committed.get(file);
                            log("Saved " + item.name + " bytes=" + item.size + " transferMs=" + ms + " destination=" + item.location());
                            main.post(() -> {receivingOriginal=false;savedSummary=timingCaptions.remove(captureId);if(savedSummary==null)savedSummary="";captureQueue.saved(captureId);transferBegins.remove(captureId);latest = item; latestMillis = ms;latestCaptureId=captureId; count++; percent = 0; message = "已保存到"+item.location()+" · 等待下一张照片"; if (observer != null) observer.captured(item, ms,captureId); changed(); });
                        }
                        public void diagnostic(String text) { log(text); }
                    },wireless,new TetherEngine.Destination() {
                        public void commit(File original) throws IOException { committed.put(original,library.save(original)); }
                        public void released(File original) {
                            PhotoItem item=committed.remove(original);
                            if(item!=null && item.inAlbum() && original.exists() && !original.delete()) log("相册保存成功，临时副本待清理："+original.getName());
                        }
                    });
                    engine.immediateDownloads(() -> downloadImmediately);
                    engine.pauseDownloads(downloadsPaused);
                    if (stopping) engine.stop();
                    engine.run();
                    break;
                } catch (IOException failure) {
                    if (stopping || !wireless || !reachedReady[0] || retries >= 3 || failure instanceof PtpException || failure instanceof CaptureSaveException) throw failure;
                    retries++;
                    String text = "Wi‑Fi 中断，正在重连（" + retries + "/3）…";
                    log(text + " " + failure);
                    main.post(() -> { connected = false; busy = true; percent = 0; message = text; changed(); });
                } finally {
                    // CloseSession only on a normally stopped engine; a broken transport must be closed directly.
                    if (camera != null && stopping) try { camera.closeSession(); } catch (IOException e) { log("CloseSession: " + e); }
                    closeTransport(); engine = null;
                }
                if (!stopping) Thread.sleep(1500);
            }
        } catch (Exception e) {
            log("Stopped: " + e);
            if (!stopping) main.post(() -> {
                error = e.getMessage() == null ? e.toString() : e.getMessage(); message = "连接已停止 · 请检查后重新连接";
                if(e instanceof CaptureSaveException) {
                    PhotoItem kept=PhotoItem.local(((CaptureSaveException)e).file); latest=kept; count++;
                    if(observer!=null) observer.captured(kept,0,System.nanoTime());
                }
            });
        } finally {
            closeTransport(); engine = null;
            if (awake != null && awake.isHeld()) awake.release();
            main.post(() -> {
                receivingOriginal=false;captureQueue.disconnected();active = false; busy = false; connected = false; selected = null; percent = 0;
                updateWifiLock();
                if (stopping && error.isEmpty()) message = "已断开 · 已接收的照片保存在手机中";
                model = "未连接"; changed(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        }
    }
    private void closeTransport() {
        Closeable connection = transport; transport = null;
        if (connection != null) try { connection.close(); } catch (IOException e) { log("Close transport: " + e); }
    }
    public File captureDirectory() {
        return new PhotoLibrary(this).directory();
    }
    public File logFile() { return new File(getFilesDir(), "tether.log"); }
    private synchronized void log(String line) {
        try {
            File f = logFile();
            if (f.length() > 1024 * 1024) try (FileOutputStream truncate = new FileOutputStream(f)) { truncate.write("Log rotated\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            try (FileWriter w = new FileWriter(f, true)) { w.write(new java.util.Date() + " " + line + "\n"); }
        } catch (IOException ignored) { android.util.Log.w("XT5Tether", "Cannot save diagnostic log"); }
    }
    public void stopConnection(boolean force) {
        stopping = true; TetherEngine e = engine; if (e != null) e.stop();
        if (force || (wireless && e == null)) closeTransport();
        changed();
    }
    @Override public void onDestroy() {
        observer = null; stopConnection(true);active=false;updateWifiLock();unregisterReceiver(detached); worker.shutdown(); super.onDestroy();
    }
}
