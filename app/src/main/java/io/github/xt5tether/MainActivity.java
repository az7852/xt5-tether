package io.github.xt5tether;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.util.LruCache;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements TetherService.Observer {
    private static final int BG=Color.rgb(13,16,19), PANEL=Color.rgb(24,29,33), SURFACE=Color.rgb(33,40,44), BORDER=Color.rgb(48,58,63), INK=Color.rgb(236,241,237), MUTED=Color.rgb(151,164,171), GREEN=Color.rgb(194,227,163);
    private static final String USB_PERMISSION = "io.github.xt5tether.USB_PERMISSION";
    private final ExecutorService decoding = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    // Keep queries, exports and deletes ordered even when an Activity is recreated.
    private static final ExecutorService files = Executors.newSingleThreadExecutor();
    private TetherService service;
    private boolean bound, follow = true, permissionPending;
    private volatile boolean destroyed;
    private UsbDevice pendingDevice;
    private TextView status, connection, detail, title, empty, savedCount;
    private Button connect, export, followButton, stripAll, stripDelete;
    private PhotoView photo;
    private GridLayout strip;
    private LinearLayout root, header, statusBar, library, viewerInfo;
    private ImageButton focusExit;
    private boolean wideLayout, focusMode, rebuilding;
    private Dialog gallery;
    private AlertDialog deleteConfirmation;
    private android.window.OnBackInvokedCallback exitFocus;
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getAllocationByteCount() / 1024); }
    };
    private ProgressBar progress;
    private PhotoItem selected;
    private PhotoLibrary photos;
    private boolean showParameters;
    private PhotoMetadata photoMetadata=PhotoMetadata.EMPTY;
    private LinearLayout parameterRow;
    private final TextView[] parameterValues=new TextView[8];
    private AlertDialog parameterDialog,settingsDialog;
    private volatile int previewGeneration;
    private long latestFollowCapture=Long.MIN_VALUE;
    private boolean waitingForOriginal;
    private final ArrayList<PhotoItem> history = new ArrayList<>();
    private final LinkedHashMap<String,PhotoItem> pendingItems=new LinkedHashMap<>();
    private TextView queueSummary;
    private Button downloadButton;
    private int sidebarWidthDp;
    private BaseAdapter galleryAdapter;
    private TextView galleryHeading, galleryHint;
    private Button galleryMode, galleryAll, galleryDelete;
    private ImageButton galleryClose;
    private final GallerySelection gallerySelection=new GallerySelection();
    private boolean deleting, exporting, stripFollowBefore;
    private int photoBatchDone,photoBatchTotal;
    private final GallerySelection stripSelection=new GallerySelection();
    private final LinkedHashMap<String,PhotoItem> stripChosen=new LinkedHashMap<>();
    private final ArrayList<PhotoItem> gallerySaved=new ArrayList<>(),galleryRows=new ArrayList<>();
    private final Map<String, View> thumbnailViews = new HashMap<>();
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TetherService.LocalBinder)binder).service(); service.observe(MainActivity.this);
            refreshHistory();
            if (pendingDevice != null) { UsbDevice d = pendingDevice; pendingDevice = null; startCamera(d); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        photos=new PhotoLibrary(this);
        showParameters=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("show_capture_parameters",false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (state != null) follow = state.getBoolean("follow", true);
        if (state != null) focusMode = state.getBoolean("focus", false);
        if (Build.VERSION.SDK_INT >= 33) exitFocus = () -> setFocusMode(false);
        buildUi();
        if (focusMode && Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, exitFocus);
        IntentFilter permission = new IntentFilter(USB_PERMISSION);
        IntentFilter attached = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, permission, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(attachReceiver, attached, Context.RECEIVER_NOT_EXPORTED);
        } else { registerLegacyPermissionReceiver(permission); registerReceiver(attachReceiver, attached); }
        bound = bindService(new Intent(this, TetherService.class), binding, Context.BIND_AUTO_CREATE);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) handleUsbIntent(getIntent());
    }
    // Android 11/12 do not expose RECEIVER_NOT_EXPORTED. The package-scoped PendingIntent
    // returns the grant, and the receiver independently verifies UsbManager.hasPermission.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyPermissionReceiver(IntentFilter filter) { registerReceiver(permissionReceiver, filter); }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putBoolean("follow", follow); out.putBoolean("focus", focusMode);
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (root != null) root.requestLayout();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleUsbIntent(intent); }
    private final BroadcastReceiver attachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { handleUsbIntent(intent); }
    };
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            permissionPending = false;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && getSystemService(UsbManager.class).hasPermission(d)) startCamera(d);
            else toast("未获得 USB 授权，请点「连接」重试");
        }
    };
    private void handleUsbIntent(Intent intent) {
        UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d != null && d.getVendorId() == 0x04cb && d.getProductId() == 0x02fc) requestCamera(d);
    }
    private void findCamera() {
        if (service != null && (service.connected || service.busy)) { service.message = "正在完成当前文件并断开…"; service.stopConnection(false); return; }
        ArrayList<UsbDevice> cameras = new ArrayList<>();
        for (UsbDevice d : getSystemService(UsbManager.class).getDeviceList().values()) if (d.getVendorId() == 0x04cb && d.getProductId() == 0x02fc) cameras.add(d);
        if (cameras.isEmpty()) { new AlertDialog.Builder(this).setTitle("未检测到 X‑T5")
                .setMessage("请使用支持数据传输的 USB‑C 线或 OTG 转接器，让手机作为 USB 主机。\n\n相机：网络/USB 设置 → 连接模式 → USB 联机拍摄自动。\n\n关闭其他相机应用后重试。")
                .setPositiveButton("知道了", null).show(); return; }
        if (cameras.size() == 1) { requestCamera(cameras.get(0)); return; }
        String[] names = new String[cameras.size()]; for (int i=0; i<names.length; i++) names[i] = "X‑T5 · USB " + cameras.get(i).getDeviceId();
        new AlertDialog.Builder(this).setTitle("选择相机").setItems(names, (d, which) -> requestCamera(cameras.get(which))).show();
    }
    private void requestCamera(UsbDevice d) {
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager.hasPermission(d)) { startCamera(d); return; }
        if (permissionPending) return;
        permissionPending = true;
        PendingIntent permission = PendingIntent.getBroadcast(this, 10, new Intent(USB_PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        manager.requestPermission(d, permission);
    }
    private void startCamera(UsbDevice d) {
        if (service == null) { pendingDevice = d; return; }
        if (service.connected || service.busy) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        Intent start = new Intent(this, TetherService.class).setAction(TetherService.CONNECT).putExtra(UsbManager.EXTRA_DEVICE, d);
        startForegroundService(start);
    }
    private void wifiOptions() {
        if (service == null) { toast("应用正在准备，请稍后重试"); return; }
        if (service.connected || service.busy) { toast("请先断开当前连接"); return; }
        new AlertDialog.Builder(this).setTitle("Wi‑Fi 联机拍摄")
            .setItems(new String[]{"自动发现 X‑T5","输入相机 IP 地址","查看 Wi‑Fi 连接步骤"},(dialog,which) -> {
                if (which == 0) startWifi("");
                else if (which == 1) enterWifiAddress();
                else wifiHelp();
            }).setNegativeButton("取消",null).show();
    }
    private void enterWifiAddress() {
        EditText address = new EditText(this); address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        address.setHint("例如 192.168.31.120");
        address.setText(getSharedPreferences("connection",MODE_PRIVATE).getString("camera_ip",""));
        LinearLayout layout = column(); layout.setPadding(dp(24),dp(8),dp(24),0);
        layout.addView(text("相机和手机需要连接同一个 Wi‑Fi",13,MUTED)); layout.addView(address,matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("相机 IP 地址").setView(layout)
                .setPositiveButton("连接",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String host = address.getText().toString().trim();
            if (!validIpv4(host)) { address.setError("请输入相机的有效 IPv4 地址"); return; }
            getSharedPreferences("connection",MODE_PRIVATE).edit().putString("camera_ip",host).apply();
            dialog.dismiss(); startWifi(host);
        }));
        dialog.show();
    }
    private static boolean validIpv4(String address) {
        String[] parts=address.split("\\.",-1); if(parts.length!=4)return false;
        for(String part:parts) { if(!part.matches("[0-9]{1,3}"))return false; int n=Integer.parseInt(part); if(n>255)return false; }
        int first=Integer.parseInt(parts[0]); return first>0 && first<224 && first!=127;
    }
    private void startWifi(String host) {
        if (service == null || service.connected || service.busy) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
        startForegroundService(new Intent(this,TetherService.class).setAction(TetherService.CONNECT_WIFI).putExtra("camera_ip",host));
    }
    private void wifiHelp() {
        new AlertDialog.Builder(this).setTitle("Wi‑Fi 连接步骤")
            .setMessage("1. 拔下相机 USB 数据线。\n\n2. 相机 → 网络/USB 设置 → 网络设置，将相机连接到手机所在的路由器 Wi‑Fi。\n\n3. 连接模式选择「无线联机拍摄固定 / WIRELESS TETHER SHOOTING FIXED」。\n\n4. 点顶部「连接 → Wi‑Fi 自动发现」，或选择「Wi‑Fi 输入相机 IP」。相机有确认提示时按 MENU/OK。\n\n关闭 XApp、X Acquire 等正在使用相机的程序。访客网络或 AP 隔离可能阻止发现。\n\n建议先用 JPEG FINE 测试。Wi‑Fi 传输 RAW 需要更长时间；存卡行为仍由机身设置决定。")
            .setPositiveButton("知道了",null).show();
    }
    private void buildUi() {
        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        buildUi(useSidebar(width, height));
    }
    private static boolean useSidebar(int width, int height) {
        return width >= 840 || (width >= 640 && width > height);
    }
    private static int sidebarWidth(int windowWidthDp) {return windowWidthDp>=1000?256:212;}
    private void buildUi(boolean wide) {
        CharSequence oldTitle = title == null ? "拍摄预览" : title.getText();
        CharSequence oldDetail = detail == null ? "原图自动保存在本机" : detail.getText();
        CharSequence oldEmpty = empty == null ? "等待第一张照片\n连接相机，按下快门即可查看" : empty.getText();
        boolean hasPhoto = empty != null && empty.getVisibility() == View.GONE;
        wideLayout = wide;
        int initialWidth=root!=null && root.getWidth()>0?Math.round(root.getWidth()/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;
        sidebarWidthDp=sidebarWidth(initialWidth);
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left+dp(8),bars.top,bars.right+dp(8),bars.bottom+dp(8)); return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int windowWidth=Math.round((r-l)/getResources().getDisplayMetrics().density);
            boolean next = useSidebar(windowWidth,
                    Math.round((b-t) / getResources().getDisplayMetrics().density));
            if ((next != wideLayout || next && sidebarWidthDp!=sidebarWidth(windowWidth)) && !rebuilding) {
                rebuilding = true;
                v.post(() -> { rebuilding = false; if (!destroyed && v == root) buildUi(next); });
            }
        });
        header = row(); header.setPadding(dp(8),0,0,0);
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(64)));
        LinearLayout identity=column();identity.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("XT5",21,INK);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));brand.setLetterSpacing(0.05f);
        TextView subtitle=text("联机拍摄",9,MUTED);subtitle.setLetterSpacing(0.08f);identity.addView(brand,matchWrap());identity.addView(subtitle,matchWrap());header.addView(identity);
        connection = text("○ 未连接",12,MUTED); connection.setSingleLine(true);
        connection.setEllipsize(TextUtils.TruncateAt.END); connection.setPadding(dp(12),0,dp(4),0);
        connection.setContentDescription("连接状态，点按查看详情");
        statusBar=column();statusBar.setGravity(Gravity.CENTER_VERTICAL);statusBar.setPadding(0,dp(4),dp(4),dp(4));
        header.addView(statusBar,new LinearLayout.LayoutParams(0,-1,1));
        statusBar.addView(connection,matchWrap());connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setOnClickListener(v -> connectionDetails());
        status = text("连接相机，开始联机拍摄",11,MUTED);status.setSingleLine(true);status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(12),dp(2),0,0);statusBar.addView(status,matchWrap());
        statusBar.setOnClickListener(v -> connectionDetails());status.setOnClickListener(v -> connectionDetails());
        connect = button("连接",true);header.addView(connect,new LinearLayout.LayoutParams(dp(72),dp(48)));connect.setOnClickListener(v -> connectionOptions());
        ImageButton focus = iconButton(R.drawable.ic_expand,"全屏看图");LinearLayout.LayoutParams focusParams=new LinearLayout.LayoutParams(dp(48),dp(48));focusParams.leftMargin=dp(8);header.addView(focus,focusParams); focus.setOnClickListener(v -> setFocusMode(true));
        ImageButton more = iconButton(R.drawable.ic_more,"更多操作与连接指南");LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(dp(48),dp(48));moreParams.leftMargin=dp(8);moreParams.rightMargin=dp(2);header.addView(more,moreParams); more.setOnClickListener(v -> moreOptions(more));

        LinearLayout workspace = row(); workspace.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams workspaceParams=new LinearLayout.LayoutParams(-1,0,1);workspaceParams.bottomMargin=wide?0:dp(8);
        root.addView(workspace,workspaceParams);

        FrameLayout viewer = new FrameLayout(this); viewer.setBackground(shape(Color.rgb(5,7,9),16)); viewer.setClipToOutline(true);
        LinearLayout.LayoutParams viewerParams = new LinearLayout.LayoutParams(0,-1,1);
        workspace.addView(viewer,viewerParams);
        if (photo == null) photo = new PhotoView(this);
        else if (photo.getParent() != null) ((ViewGroup)photo.getParent()).removeView(photo);
        photo.setOnClickListener(v -> setFocusMode(!focusMode));
        viewer.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        empty = text(oldEmpty.toString(),15,MUTED); empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(10),1);
        empty.setVisibility(hasPhoto?View.GONE:View.VISIBLE); viewer.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        viewerInfo = column(); viewerInfo.setPadding(dp(18),dp(28),dp(18),dp(14));
        viewerInfo.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00050709,0xF0050709}));
        title = text(oldTitle.toString(),13,INK); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        detail = text(oldDetail.toString(),11,MUTED); detail.setSingleLine(true); detail.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail.setPadding(0,dp(3),0,0);
        viewerInfo.addView(title,matchWrap());viewerInfo.addView(detail,matchWrap());
        parameterRow=column();parameterRow.setPadding(0,dp(12),0,0);
        String[] parameterLabels={"光圈","快门","ISO","焦距","曝光补偿","测光模式","曝光程序","等效焦距"};
        for(int rowIndex=0;rowIndex<2;rowIndex++) {
            LinearLayout metrics=row();if(rowIndex==1)metrics.setPadding(0,dp(12),0,0);
            for(int columnIndex=0;columnIndex<4;columnIndex++) {
                int i=rowIndex*4+columnIndex;LinearLayout metric=column();metric.setPadding(0,0,dp(6),0);
                parameterValues[i]=text("—",14,INK);parameterValues[i].setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                parameterValues[i].setSingleLine(true);parameterValues[i].setEllipsize(TextUtils.TruncateAt.END);
                TextView label=text(parameterLabels[i],9,MUTED);label.setPadding(0,dp(3),0,0);
                metric.addView(parameterValues[i],matchWrap());metric.addView(label,matchWrap());metrics.addView(metric,new LinearLayout.LayoutParams(0,-2,1));
            }
            parameterRow.addView(metrics,matchWrap());
        }
        parameterRow.setOnClickListener(v -> parameterDetails());viewerInfo.addView(parameterRow,matchWrap());refreshParameterPanel();
        viewer.addView(viewerInfo,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        focusExit = iconButton(R.drawable.ic_collapse,"退出全屏看图"); focusExit.setBackground(shape(0xC01B1E21,24));
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);
        exitParams.setMargins(dp(12),dp(12),dp(12),dp(12)); viewer.addView(focusExit,exitParams); focusExit.setOnClickListener(v -> setFocusMode(false));
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        viewer.addView(progress,new FrameLayout.LayoutParams(-1,dp(3),Gravity.TOP)); progress.setVisibility(View.INVISIBLE);

        library = column(); library.setBackground(shape(PANEL,16));library.setClipToOutline(true);
        if (wide) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sidebarWidthDp),-1);
            lp.leftMargin = dp(8); workspace.addView(library,lp);
        } else root.addView(library,matchWrap());
        LinearLayout tools = row(); tools.setPadding(dp(8),0,dp(6),0);
        library.addView(tools,new LinearLayout.LayoutParams(-1,dp(48)));
        savedCount = text(wide?"拍摄序列":"最近照片",12,INK);savedCount.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); savedCount.setSingleLine(true); savedCount.setEllipsize(TextUtils.TruncateAt.END);
        tools.addView(savedCount,new LinearLayout.LayoutParams(0,-2,1));
        followButton = button("跟随",false); tools.addView(followButton,new LinearLayout.LayoutParams(dp(84),dp(48))); followButton.setOnClickListener(v -> {
            if(stripSelection.active()){endStripSelection(true);return;}
            follow = !follow; updateFollow(); if(follow) resumeLatest();
        });
        LinearLayout queueControls=row();queueControls.setPadding(dp(6),0,dp(6),0);
        queueSummary=text("待保存 0 / 32\n停拍3秒自动保存",11,MUTED);queueSummary.setLines(2);queueSummary.setEllipsize(TextUtils.TruncateAt.END);
        queueSummary.setGravity(Gravity.CENTER_VERTICAL);queueSummary.setPadding(dp(4),0,dp(4),0);
        queueControls.addView(queueSummary,new LinearLayout.LayoutParams(0,-1,1));
        downloadButton=button("暂停原图",false);downloadButton.setEnabled(false);downloadButton.setOnClickListener(v -> {
            if(service!=null)service.pauseDownloads(!service.downloadsPaused);
        });queueControls.addView(downloadButton,new LinearLayout.LayoutParams(dp(88),dp(40)));
        queueControls.setBackground(shape(SURFACE,12));LinearLayout.LayoutParams queueParams=new LinearLayout.LayoutParams(-1,dp(52));
        queueParams.setMargins(dp(8),0,dp(8),dp(6));library.addView(queueControls,queueParams);
        strip = new GridLayout(this); strip.setOrientation(GridLayout.HORIZONTAL);
        // Let the filmstrip grow in one row. Android retains resolved child indices;
        // shrinking columnCount as pending photos disappear can otherwise throw.
        if(wide)strip.setColumnCount(2);else strip.setRowCount(1);
        strip.setPadding(dp(6),dp(4),dp(6),dp(4));
        if (wide) {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(strip,new ScrollView.LayoutParams(-1,-2)); library.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        } else {
            HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(strip,new HorizontalScrollView.LayoutParams(-2,-2));
            library.addView(scroll,new LinearLayout.LayoutParams(-1,dp(64)+Math.round(30*getResources().getDisplayMetrics().scaledDensity)));
        }
        LinearLayout actions = row(); actions.setPadding(dp(6),0,dp(6),0);
        stripAll=button("图库",false);stripAll.setOnClickListener(v -> {
            if(photoActionsBusy())return;
            if(stripSelection.active()) {stripSelection.selectAll(!stripSelection.allSelected());updateSelection();}else allPhotos();
        });
        export=button("导出",false);export.setOnClickListener(v -> {if(stripSelection.active())exportPhotos(stripTargets());else exportSelected();});
        stripDelete=button("删除",false);stripDelete.setOnClickListener(v -> confirmPhotoDelete(stripTargets()));
        stripDelete.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(92,104,108),Color.rgb(255,155,140)}));
        if(wide) {
            for(Button action:new Button[]{stripAll,export,stripDelete})actions.addView(action,new LinearLayout.LayoutParams(0,dp(48),1));
            library.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            for(Button action:new Button[]{stripAll,export,stripDelete})tools.addView(action,new LinearLayout.LayoutParams(dp(52),dp(48)));
        }
        thumbnailViews.clear();for(PhotoItem item:pendingItems.values())bindThumbnail(item,strip.getChildCount());
        for (PhotoItem file : history) bindThumbnail(file,strip.getChildCount());
        updateFollow(); updateSelection(); changed(); applyFocusMode(); root.requestApplyInsets();
    }
    private void connectionOptions() {
        if (service != null && (service.connected || service.busy)) { findCamera(); return; }
        new AlertDialog.Builder(this).setTitle("连接 X‑T5")
            .setItems(new String[]{"Wi‑Fi 自动发现","Wi‑Fi 输入相机 IP","USB 数据线连接","连接指南"},(d,which) -> {
                if (which==0) startWifi(""); else if (which==1) enterWifiAddress(); else if (which==2) findCamera(); else help();
            }).setNegativeButton("取消",null).show();
    }
    private void connectionDetails() {
        String message = service == null ? "正在准备连接服务" : service.message;
        if (service != null && !service.wifiAddress.isEmpty()) message += "\n相机：" + service.wifiAddress;
        if (service != null && !service.error.isEmpty()) message += "\n\n" + service.error;
        if(service!=null && !service.savedSummary.isEmpty())message+="\n\n最近完成："+service.savedSummary;
        new AlertDialog.Builder(this).setTitle("连接与传输状态").setMessage(message)
            .setPositiveButton("知道了",null).setNeutralButton("连接指南",(d,w) -> help()).show();
    }
    private void moreOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this,anchor);
        menu.getMenu().add(0,6,Menu.NONE,"设置");
        menu.getMenu().add(0,1,Menu.NONE,"图库"); menu.getMenu().add(0,2,Menu.NONE,stripSelection.active()?"导出所选照片":"导出当前照片").setEnabled(!photoActionsBusy() && (stripSelection.active()?stripSelection.size()>0:selected!=null&&!selected.pending()));
        menu.getMenu().add(0,3,Menu.NONE,"跟随新照片").setCheckable(true).setChecked(follow);
        menu.getMenu().add(0,4,Menu.NONE,"连接与传输状态"); menu.getMenu().add(0,5,Menu.NONE,"连接指南与许可");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 6: settings(); break;
                case 1: allPhotos(); break;
                case 2: if(stripSelection.active())exportPhotos(stripTargets());else exportSelected(); break;
                case 3: if(stripSelection.active()){endStripSelection(false);follow=true;}else follow=!follow; updateFollow(); if(follow)resumeLatest(); break;
                case 4: connectionDetails(); break;
                default: help();
            }
            return true;
        }); menu.show();
    }
    private void refreshParameterPanel() {
        if(parameterRow==null)return;
        parameterRow.setVisibility(showParameters?View.VISIBLE:View.GONE);
        String[] values={photoMetadata.aperture(),photoMetadata.shutter(),photoMetadata.iso(),photoMetadata.focal(),photoMetadata.compensation(),photoMetadata.metering(),photoMetadata.program(),photoMetadata.equivalent()};
        for(int i=0;i<values.length;i++)parameterValues[i].setText(values[i]);
        parameterRow.setAlpha(photoMetadata==PhotoMetadata.EMPTY?0.55f:1f);
        parameterRow.setContentDescription("拍摄参数：光圈 "+values[0]+"，快门 "+values[1]+"，ISO "+values[2]+"，焦距 "+values[3]+"，曝光补偿 "+values[4]+"，测光模式 "+values[5]+"，曝光程序 "+values[6]+"，等效焦距 "+values[7]+"。点按查看完整参数");
    }
    private void parameterDetails() {
        if(selected==null || selected.pending() || waitingForOriginal || photoMetadata==PhotoMetadata.EMPTY){toast("原图显示后可查看拍摄参数");return;}
        if(parameterDialog!=null)return;
        LinearLayout rows=column();rows.setPadding(dp(24),dp(8),dp(24),dp(12));
        TextView filename=text(selected.displayName(),12,MUTED);filename.setPadding(0,0,0,dp(14));rows.addView(filename,matchWrap());
        for(String[] entry:photoMetadata.details()) {
            LinearLayout line=row();line.setGravity(Gravity.TOP);line.setPadding(0,dp(10),0,dp(10));
            line.addView(text(entry[0],13,MUTED),new LinearLayout.LayoutParams(dp(80),-2));
            TextView value=text(entry[1],14,INK);value.setTextIsSelectable(true);line.addView(value,new LinearLayout.LayoutParams(0,-2,1));rows.addView(line,matchWrap());
        }
        TextView note=text("仅显示照片记录的 EXIF；缺失的参数以 — 标示。",11,MUTED);note.setPadding(0,dp(8),0,0);rows.addView(note,matchWrap());
        ScrollView scroll=new ScrollView(this);scroll.addView(rows);
        parameterDialog=new AlertDialog.Builder(this).setTitle("拍摄参数").setView(scroll).setPositiveButton("关闭",null).create();
        parameterDialog.setOnDismissListener(d -> parameterDialog=null);parameterDialog.show();
        if(parameterDialog.getWindow()!=null)parameterDialog.getWindow().setLayout(dp(Math.min(540,getResources().getConfiguration().screenWidthDp-32)),-2);
    }
    private LinearLayout settingsCard(LinearLayout parent,String heading) {
        TextView label=text(heading,12,GREEN);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));label.setPadding(dp(4),dp(18),dp(4),dp(8));parent.addView(label,matchWrap());
        LinearLayout card=column();card.setPadding(dp(16),dp(4),dp(16),dp(8));card.setBackground(shape(SURFACE,14));parent.addView(card,matchWrap());return card;
    }
    private Switch settingsSwitch(String label,boolean checked) {
        Switch control=new Switch(this);control.setText(label);control.setTextSize(15);control.setTextColor(INK);control.setMinHeight(dp(52));control.setSwitchPadding(dp(16));control.setChecked(checked);
        control.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GREEN,MUTED}));
        control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0x80516A3D,BORDER}));return control;
    }
    private void settings() {
        if(settingsDialog!=null)return;
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),dp(12));
        LinearLayout display=settingsCard(content,"查看与显示");
        Switch parameters=settingsSwitch("显示拍摄参数",showParameters);display.addView(parameters,matchWrap());
        TextView parameterHelp=text("预览与全屏显示两行拍摄参数，包括光圈、快门、ISO、焦距、曝光补偿、测光与曝光程序。点参数栏可看镜头、白平衡、闪光灯、色彩空间等完整信息。",12,MUTED);parameterHelp.setLineSpacing(dp(3),1);parameterHelp.setPadding(0,0,0,dp(6));display.addView(parameterHelp,matchWrap());
        LinearLayout storage=settingsCard(content,"照片保存");
        RadioGroup choices=new RadioGroup(this);choices.setOrientation(RadioGroup.VERTICAL);
        RadioButton local=new RadioButton(this);local.setId(View.generateViewId());local.setText(R.string.save_app);local.setTextSize(15);local.setTextColor(INK);local.setMinHeight(dp(48));
        RadioButton album=new RadioButton(this);album.setId(View.generateViewId());album.setText(R.string.save_album);album.setTextSize(15);album.setTextColor(INK);album.setMinHeight(dp(48));
        choices.addView(local,matchWrap());choices.addView(album,matchWrap());choices.check(PhotoLibrary.ALBUM.equals(photos.destination())?album.getId():local.getId());storage.addView(choices,matchWrap());
        TextView description=text("",12,MUTED);description.setLineSpacing(dp(3),1);description.setPadding(0,dp(4),0,dp(8));storage.addView(description,matchWrap());
        Runnable explain=() -> description.setText(choices.getCheckedRadioButtonId()==album.getId()
                ?"JPEG 保存到系统相册 XT5Tether；RAF 保存到下载/XT5Tether，App 内也可查看。"
                :"原图保存在 App 图库，可随时导出到系统相册。卸载 App 会删除这些照片。");
        choices.setOnCheckedChangeListener((group,id) -> explain.run());explain.run();
        TextView note=text("仅影响之后保存的照片，不移动已有照片。",11,MUTED);note.setPadding(0,0,0,dp(6));storage.addView(note,matchWrap());
        LinearLayout timing=settingsCard(content,"原图下载时机");
        RadioGroup timingChoices=new RadioGroup(this);timingChoices.setOrientation(RadioGroup.VERTICAL);
        RadioButton wait=new RadioButton(this);wait.setId(View.generateViewId());wait.setText("停拍 3 秒后下载");wait.setTextSize(15);wait.setTextColor(INK);wait.setMinHeight(dp(48));
        RadioButton immediate=new RadioButton(this);immediate.setId(View.generateViewId());immediate.setText("立即下载");immediate.setTextSize(15);immediate.setTextColor(INK);immediate.setMinHeight(dp(48));
        timingChoices.addView(wait,matchWrap());timingChoices.addView(immediate,matchWrap());
        boolean currentImmediate=service!=null?service.downloadImmediately:getSharedPreferences("settings",MODE_PRIVATE).getBoolean("download_immediately",false);
        timingChoices.check(currentImmediate?immediate.getId():wait.getId());timing.addView(timingChoices,matchWrap());
        TextView timingHelp=text("",12,MUTED);timingHelp.setLineSpacing(dp(3),1);timingHelp.setPadding(0,dp(4),0,dp(6));timing.addView(timingHelp,matchWrap());
        Runnable timingDescription=() -> timingHelp.setText(timingChoices.getCheckedRadioButtonId()==immediate.getId()
                ?"Wi-Fi 联机时，取得预览后立即下载原图。下载中再拍，新预览可能等待当前传输结束。保存设置后无需重连；手动暂停仍然有效。"
                :"Wi-Fi 联机时优先更新连拍预览，停拍 3 秒后保存原图；积压 8 项时会提前保存。USB 保持直接接收。保存设置后无需重连。");
        timingChoices.setOnCheckedChangeListener((group,id) -> timingDescription.run());timingDescription.run();
        LinearLayout network=settingsCard(content,"无线连接");
        Switch accelerated=settingsSwitch(getString(R.string.wifi_acceleration),service!=null?service.wifiAcceleration:getSharedPreferences("connection",MODE_PRIVATE).getBoolean("wifi_acceleration",true));network.addView(accelerated,matchWrap());
        TextView networkNote=text(getString(R.string.wifi_acceleration_help),12,MUTED);networkNote.setLineSpacing(dp(3),1);networkNote.setPadding(0,0,0,dp(10));network.addView(networkNote,matchWrap());
        View separator=new View(this);separator.setBackgroundColor(BORDER);network.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
        Switch lowLatency=settingsSwitch(getString(R.string.wifi_low_latency),service!=null?service.wifiLowLatency:getSharedPreferences("connection",MODE_PRIVATE).getBoolean("wifi_low_latency",false));network.addView(lowLatency,matchWrap());
        TextView latencyNote=text(getString(R.string.wifi_low_latency_help),12,MUTED);latencyNote.setLineSpacing(dp(3),1);latencyNote.setPadding(0,0,0,dp(6));network.addView(latencyNote,matchWrap());
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        settingsDialog=new AlertDialog.Builder(this).setTitle("设置").setView(scroll).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w) -> {
                photos.destination(choices.getCheckedRadioButtonId()==album.getId()?PhotoLibrary.ALBUM:PhotoLibrary.APP);
                showParameters=parameters.isChecked();getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("show_capture_parameters",showParameters).apply();
                refreshParameterPanel();applyFocusMode();
                boolean immediately=timingChoices.getCheckedRadioButtonId()==immediate.getId();
                if(service!=null)service.downloadImmediately(immediately);
                else getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("download_immediately",immediately).apply();
                if(service!=null){service.wifiAcceleration(accelerated.isChecked());service.wifiLowLatency(lowLatency.isChecked());}
                else getSharedPreferences("connection",MODE_PRIVATE).edit().putBoolean("wifi_acceleration",accelerated.isChecked()).putBoolean("wifi_low_latency",lowLatency.isChecked()).apply();
                toast("设置已保存");
            }).create();
        settingsDialog.setOnDismissListener(d -> settingsDialog=null);settingsDialog.show();
        if(settingsDialog.getWindow()!=null)settingsDialog.getWindow().setLayout(dp(Math.min(540,getResources().getConfiguration().screenWidthDp-32)),-2);
    }
    private void setFocusMode(boolean enabled) {
        if (focusMode == enabled) return;
        focusMode = enabled;
        if (Build.VERSION.SDK_INT >= 33) {
            if (enabled) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,exitFocus);
            else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(exitFocus);
        }
        applyFocusMode();
    }
    private void applyFocusMode() {
        header.setVisibility(focusMode?View.GONE:View.VISIBLE);
        library.setVisibility(focusMode?View.GONE:View.VISIBLE);viewerInfo.setVisibility(focusMode && !showParameters?View.GONE:View.VISIBLE);
        title.setVisibility(focusMode?View.GONE:View.VISIBLE);detail.setVisibility(focusMode?View.GONE:View.VISIBLE);
        focusExit.setVisibility(focusMode?View.VISIBLE:View.GONE);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (focusMode) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
        }
    }
    @Override public void onBackPressed() { if(stripSelection.active() && !photoActionsBusy())endStripSelection(true);else if (focusMode) setFocusMode(false); else super.onBackPressed(); }
    private void help() {
        new AlertDialog.Builder(this).setTitle("XT5 Tether 0.9.2")
            .setItems(new String[]{"USB 连接指南","Wi‑Fi 连接指南","导出诊断日志","开源许可"},(dialog,which) -> {
                if (which == 0) usbHelp();
                else if (which == 1) wifiHelp();
                else if (which == 2) {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"xt5-tether.log"); startActivityForResult(i,30);
                } else licenses();
            }).setNegativeButton("关闭",null).show();
    }
    private void usbHelp() {
        new AlertDialog.Builder(this).setTitle("X‑T5 联机拍摄")
            .setMessage("1. 相机 → 网络/USB 设置 → 连接模式 → USB 联机拍摄自动（USB TETHER SHOOTING AUTO）。\n\n2. USB 供电/通信设置 → 自动，或供电关/通信开。关闭相机自动关机。\n\n3. 用 USB 数据线连接支持 OTG 的安卓手机/平板，点击连接并允许 USB 访问。\n\n4. 用相机快门拍照。推荐 JPEG FINE 或 RAW+JPEG；RAW 显示内嵌 JPEG，原 RAF 完整保留。\n\n照片保存位置可在「更多 → 设置」选择。App 图库照片卸载前请先导出；本地相册照片保留在公共相册中，RAF 原图保存在下载目录。\n\n联机拍摄模式可能不向存储卡写照片，请在相机上检查并开启所需备份设置。先用测试照片确认存储位置。\n\n当前版本：0.9.2，支持 USB 与同一局域网 Wi‑Fi 联机收图。")
            .setPositiveButton("知道了",null).setNeutralButton("导出诊断日志",(d,w) -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"xt5-tether.log"); startActivityForResult(i,30);
            }).setNegativeButton("开源许可",(d,w) -> licenses()).show();
    }
    private void licenses() {
        try (InputStream in = getAssets().open("licenses.txt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer,0,n);
            String content = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            ScrollView scroll = new ScrollView(this); TextView view = text(content,12,INK); view.setPadding(dp(20),dp(12),dp(20),dp(12)); scroll.addView(view);
            new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭",null).show();
        } catch (IOException e) { toast("无法读取许可文本"); }
    }
    @Override public void changed() {
        if (service == null || destroyed) return;
        syncPending();
        boolean active = service.connected || service.busy;
        connection.setText(getString(R.string.usb_connection, service.connected ? "●" : "○", service.connected ? service.connectionType : service.busy ? service.connectionType + " 连接中…" : "未连接"));
        connection.setTextColor(service.connected ? GREEN : MUTED);
        connect.setText(active ? "断开" : "连接");
        connect.setContentDescription(active ? "断开当前相机" : "选择 USB 或 Wi-Fi 连接");
        status.setText(service.error.isEmpty() ? service.message : service.error);
        status.setContentDescription(service.message + (service.error.isEmpty()?"":"。"+service.error));
        status.setTextColor(service.error.isEmpty() ? MUTED : Color.rgb(255,178,150));
        progress.setVisibility(service.percent > 0 ? View.VISIBLE : View.INVISIBLE); progress.setProgress(service.percent);
        updateStripActions();
    }
    @Override public void preview(byte[] jpeg, String name,long captureId) {
        if (!follow || destroyed || captureId<latestFollowCapture) {
            android.util.Log.d("PhotoPreview","Ignored "+name+" id="+captureId+" follow="+follow+" latest="+latestFollowCapture);return;
        }
        latestFollowCapture=captureId;
        photoMetadata=PhotoMetadata.EMPTY;refreshParameterPanel();
        waitingForOriginal=true;
        selected=null;
        if(service!=null)for(PhotoItem p:service.pendingPhotos())if(p.captureId==captureId){selected=p;break;}
        export.setEnabled(false);updateSelection();int generation = ++previewGeneration;
        decoding.execute(() -> {
            if (destroyed || generation != previewGeneration) return;
            try { Bitmap bitmap = PhotoDecoder.decode(jpeg,2560); runOnUiThread(() -> {
                if (generation != previewGeneration || destroyed) { bitmap.recycle(); return; }
                photo.photo(bitmap); empty.setVisibility(View.GONE); title.setText(name); detail.setText("最新预览 · 原图等待保存");
                android.util.Log.d("PhotoPreview","Displayed "+name+" id="+captureId+" bitmap="+bitmap.getWidth()+"x"+bitmap.getHeight());
            }); } catch (IOException e) {android.util.Log.w("PhotoPreview","Preview decode failed: "+name,e);}
        });
    }
    @Override public void captured(PhotoItem file, long ms,long captureId) {
        if (destroyed) return;
        boolean reviewing=selected!=null && selected.pending() && selected.captureId==captureId;
        syncPending();
        addHistory(file, true);
        if(galleryAdapter!=null){gallerySaved.removeIf(p -> p.name.equals(file.name));gallerySaved.add(0,file);refreshGalleryRows();}
        if (reviewing || follow && captureId>=latestFollowCapture) {
            latestFollowCapture=Math.max(latestFollowCapture,captureId);
            showPhoto(file,service!=null && !service.savedSummary.isEmpty()?" · "+service.savedSummary:ms>0?String.format(Locale.CHINA," · 接收及排队 %.2f 秒",ms/1000.0):"");
        }
    }
    private void refreshHistory() {
        if (service == null) return;
        files.execute(() -> {
            try { java.util.List<PhotoItem> recent = photos.recent(); runOnUiThread(() -> {
                if (destroyed) return;
                int limit = Math.min(30,recent.size()); for (int i=limit-1;i>=0;i--) addHistory(recent.get(i),true);
                if (selected == null && !waitingForOriginal && !history.isEmpty()) showPhoto(history.get(0),"");
            }); } catch (IOException e) { runOnUiThread(() -> toast(e.getMessage())); }
        });
    }
    private String pendingText(PhotoItem item) {
        return item.queueState+(item.received>0?" · "+Math.min(100,item.received*100/Math.max(1,item.size))+"%":"");
    }
    private void syncPending() {
        if(service==null || strip==null)return;
        LinkedHashMap<String,PhotoItem> current=new LinkedHashMap<>();
        for(PhotoItem p:service.pendingPhotos())current.put(p.key,p);
        for(String key:new ArrayList<>(pendingItems.keySet()))if(!current.containsKey(key)) {
            View cell=thumbnailViews.remove(key);if(cell!=null)strip.removeView(cell);
        }
        int index=0;
        for(PhotoItem p:current.values()) {
            PhotoItem previous=pendingItems.get(p.key);View cell=thumbnailViews.get(p.key);
            if(cell==null)bindThumbnail(p,index);
            else {
                Object[] views=(Object[])cell.getTag();((TextView)views[1]).setText(pendingText(p));
                if(previous==null || previous.preview!=p.preview)loadThumbnail(p,(ImageView)views[0]);
            }
            if(selected!=null && selected.pending() && selected.key.equals(p.key)) {
                selected=p;detail.setText(pendingText(p)+" · 原图 "+String.format(Locale.CHINA,"%.1f MB",p.size/1048576.0));
                if(!follow && p.preview!=null && (previous==null || previous.preview!=p.preview))showPending(p);
            }
            index++;
        }
        pendingItems.clear();pendingItems.putAll(current);
        int n=current.size();boolean paused=service.downloadsPaused;
        String queueState=n>=32?"队列已满，请开始保存":paused?(service.receivingOriginal?"本张后暂停":"拍摄优先，原图暂停"):
            service.percent>0?service.receiveSpeed+" · "+service.percent+"%":service.busy?"正在重新连接…":!service.connected?"未连接相机":service.connectionType.equals("USB")?"自动接收原图":service.downloadImmediately?"立即下载原图":"停拍3秒自动保存";
        queueSummary.setText("待保存 "+n+" / 32\n"+queueState);
        queueSummary.setContentDescription("待保存 "+n+" / 32。"+queueState+"。积压8项自动开始保存。");
        downloadButton.setEnabled(service.connected);
        downloadButton.setText(paused?(service.receivingOriginal?"本张后暂停":"开始保存"):"暂停原图");
        downloadButton.setContentDescription(paused?"恢复自动保存原图":"暂停原图，优先拍摄；正在接收的本张完成后暂停");
        if(galleryAdapter!=null)refreshGalleryRows();
    }
    private void resumeLatest() {
        if(service!=null)for(PhotoItem p:service.pendingPhotos()) {
            if(p.captureId>service.latestCaptureId) {latestFollowCapture=Math.max(latestFollowCapture,p.captureId);showPending(p);return;}
            break;
        }
        if(!history.isEmpty())showPhoto(history.get(0),"");
    }
    private void showPending(PhotoItem item) {
        selected=item;waitingForOriginal=true;photoMetadata=PhotoMetadata.EMPTY;refreshParameterPanel();export.setEnabled(false);title.setText(item.name);
        detail.setText(pendingText(item)+" · 原图尚未保存");updateSelection();int generation=++previewGeneration;
        if(item.preview==null) {photo.photo(null);empty.setText("等待相机预览…\n原图尚未保存");empty.setVisibility(View.VISIBLE);return;}
        decoding.execute(() -> {
            try {
                Bitmap bitmap=PhotoDecoder.decode(item.preview,2560);
                runOnUiThread(() -> {
                    if(destroyed || generation!=previewGeneration){bitmap.recycle();return;}
                    photo.photo(bitmap);empty.setVisibility(View.GONE);
                });
            } catch(IOException e) {android.util.Log.w("PhotoPreview","Pending preview unavailable",e);}
        });
    }
    private void addHistory(PhotoItem file, boolean first) {
        if (history.contains(file)) return;
        if (first) history.add(0,file); else history.add(file);
        while (history.size()>30) {
            PhotoItem removed = history.remove(history.size()-1); View view = thumbnailViews.remove(removed.key);
            if (view != null) strip.removeView(view);
        }
        bindThumbnail(file,first?pendingItems.size():strip.getChildCount());
        savedCount.setText(getString(R.string.capture_count,history.size())); updateSelection();
    }
    private void bindThumbnail(PhotoItem file, int index) {
        int width = wideLayout?(sidebarWidthDp-12)/2:88;
        LinearLayout cell = column(); cell.setPadding(dp(3),dp(3),dp(3),dp(3));
        ImageView image = new ImageView(this); image.setBackground(shape(BG,5)); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(file.displayName());
        FrameLayout picture=new FrameLayout(this);picture.setBackground(shape(BG,9));picture.setClipToOutline(true);picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView check=text("○",16,GREEN);check.setGravity(Gravity.CENTER);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);check.setBackground(shape(0xD0101214,12));
        picture.addView(check,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP|Gravity.END));
        cell.addView(picture,new LinearLayout.LayoutParams(-1,dp((width-6)*2/3f)));
        TextView label = text(file.displayName(),11,INK); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setPadding(0,dp(2),0,0); cell.addView(label,matchWrap());
        TextView state=text(file.pending()?pendingText(file):(file.raw()?"RAF":"JPEG")+String.format(Locale.CHINA," · %.1f MB",file.size/1048576.0),9,file.pending()?GREEN:MUTED);state.setSingleLine(true);state.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(state,matchWrap());cell.setTag(new Object[]{image,state,check,file});
        cell.setContentDescription("查看 " + file.displayName());
        cell.setOnClickListener(v -> {
            if(photoActionsBusy())return;
            if(stripSelection.active()) {if(file.pending())toast("待保存照片不能删除或导出");else {stripSelection.toggle(file.name);updateSelection();}return;}
            follow=false;updateFollow();showPhoto(file.pending()?pendingItems.getOrDefault(file.key,file):file,"");
        });
        cell.setOnLongClickListener(v -> {enterThumbnailSelection(file);return true;});
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width=dp(width); lp.height=-2;
        strip.addView(cell,index,lp); thumbnailViews.put(file.key,cell); loadThumbnail(file,image);
    }
    private void loadThumbnail(PhotoItem file, ImageView image) {
        if(file.pending() && file.preview==null) {image.setImageResource(R.drawable.ic_camera);return;}
        String key=file.key+(file.pending()?":"+System.identityHashCode(file.preview):"");image.setTag(key);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null) { image.setImageBitmap(cached); return; }
        image.setImageDrawable(null);
        thumbnails.execute(() -> {
            if (destroyed) return;
            try {
                Bitmap bitmap = thumbnailCache.get(key);
                if (bitmap == null) { bitmap=file.pending()?PhotoDecoder.decode(file.preview,384):PhotoDecoder.decode(this,file,384); thumbnailCache.put(key,bitmap); }
                Bitmap result=bitmap;
                runOnUiThread(() -> { if(!destroyed && key.equals(image.getTag())) image.setImageBitmap(result); });
            } catch (IOException ignored) {
                runOnUiThread(() -> { if(!destroyed && key.equals(image.getTag())) image.setImageResource(R.drawable.ic_camera); });
            }
        });
    }
    private boolean photoActionsBusy() {return deleting || exporting;}
    private void updateStripActions() {
        if(stripAll==null || export==null)return;
        stripChosen.keySet().removeIf(name -> !stripSelection.contains(name));
        for(PhotoItem item:history)if(stripSelection.contains(item.name))stripChosen.put(item.name,item);
        LinkedHashSet<String> available=new LinkedHashSet<>(stripChosen.keySet());for(PhotoItem item:history)available.add(item.name);
        stripSelection.available(available);
        boolean choosing=stripSelection.active(),busy=photoActionsBusy();
        savedCount.setText(busy?(deleting?"删除 ":"导出 ")+photoBatchDone+" / "+photoBatchTotal:
                choosing?"已选 "+stripSelection.size()+" 张":history.isEmpty()?"拍摄序列":getString(R.string.capture_count,history.size()));
        stripAll.setText(choosing?(stripSelection.allSelected()?"清空":"全选"):"图库");stripAll.setEnabled(!busy);
        stripDelete.setVisibility(choosing?View.VISIBLE:View.GONE);stripDelete.setEnabled(!busy && stripSelection.size()>0);
        export.setEnabled(!busy && (choosing?stripSelection.size()>0:selected!=null && !selected.pending()));
        export.setContentDescription(choosing?"导出所选 "+stripSelection.size()+" 张照片到系统相册":"导出当前照片到系统相册");
        stripDelete.setContentDescription("删除所选 "+stripSelection.size()+" 张照片");
        followButton.setEnabled(!busy);updateFollow();
    }
    private void updateSelection() {
        updateStripActions();
        for(Map.Entry<String,View> entry:thumbnailViews.entrySet()) {
            View cell=entry.getValue();Object[] views=(Object[])cell.getTag();PhotoItem file=(PhotoItem)views[3];
            boolean checked=!file.pending() && stripSelection.contains(file.name);
            boolean active=stripSelection.active()?checked:selected!=null && entry.getKey().equals(selected.key);
            cell.setSelected(active);GradientDrawable background=shape(active?Color.rgb(39,52,39):SURFACE,11);
            if(active)background.setStroke(dp(1.5f),GREEN);cell.setBackground(background);
            TextView check=(TextView)views[2];check.setVisibility(stripSelection.active() && !file.pending()?View.VISIBLE:View.GONE);check.setText(checked?"✓":"○");
            cell.setContentDescription(file.displayName()+(file.pending()?"，待保存，不能删除或导出":stripSelection.active()?(checked?"，已选择":"，未选择"):"，长按多选、删除或导出"));
        }
    }
    private void endStripSelection(boolean resume) {
        boolean wasActive=stripSelection.active();stripSelection.exit();stripChosen.clear();
        if(wasActive)follow=stripFollowBefore;updateSelection();if(wasActive && resume && follow)resumeLatest();
    }
    private List<PhotoItem> stripTargets() {
        updateStripActions();ArrayList<PhotoItem> result=new ArrayList<>();for(PhotoItem item:stripChosen.values())if(stripSelection.contains(item.name))result.add(item);return result;
    }
    private void enterThumbnailSelection(PhotoItem file) {
        if(photoActionsBusy())return;
        if(file.pending()){toast("待保存照片不能删除或导出，请先完成保存");return;}
        if(!stripSelection.active()){stripFollowBefore=follow;follow=false;stripSelection.enter();}
        updateStripActions();if(!stripSelection.contains(file.name))stripSelection.toggle(file.name);stripChosen.put(file.name,file);updateSelection();

    }
    private void showPhoto(PhotoItem file, String suffix) {
        if(file.pending()) {showPending(file);return;}
        waitingForOriginal=false;
        selected = file;photoMetadata=PhotoMetadata.EMPTY;refreshParameterPanel();export.setEnabled(!photoActionsBusy());title.setText(file.displayName());
        detail.setText("正在读取照片…"); int generation = ++previewGeneration;
        updateSelection();
        decoding.execute(() -> {
            if (destroyed || generation != previewGeneration) return;
            PhotoSource opened=null;
            try {
                opened=PhotoSource.open(this,file);
                Bitmap bitmap=opened.preview();
                PhotoSource original=opened;
                runOnUiThread(() -> {
                if (destroyed || generation != previewGeneration) { bitmap.recycle(); original.close(); return; }
                photo.photo(bitmap,original);photoMetadata=original.metadata;refreshParameterPanel();empty.setVisibility(View.GONE);
                String kind = file.name.toLowerCase(Locale.ROOT).endsWith(".raf")?"RAF · 内嵌 JPEG 预览":"JPEG";
                detail.setText(String.format(Locale.CHINA,"%s · %d×%d · %.1f MB%s · %s",kind,
                    original.geometry.uprightWidth,original.geometry.uprightHeight,file.size/1048576.0,suffix,file.location()));
            }); } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if(opened!=null) opened.close();
                runOnUiThread(() -> {
                if (!destroyed && generation == previewGeneration) { photo.photo(null); empty.setText(getString(R.string.decode_failure,e.getMessage())); empty.setVisibility(View.VISIBLE); detail.setText("可导出原始文件"); }
            }); }
        });
    }
    private void allPhotos() {
        if (service == null) return;
        if(photoActionsBusy()){toast("正在处理照片，请稍后打开图库");return;}
        if(stripSelection.active())endStripSelection(false);
        files.execute(() -> {
            try {
                java.util.List<PhotoItem> items = photos.recent();
                runOnUiThread(() -> { if (!destroyed) showGallery(items); });
            } catch (IOException e) { runOnUiThread(() -> toast(e.getMessage())); }
        });
    }
    private void refreshGalleryRows() {
        galleryRows.clear();galleryRows.addAll(pendingItems.values());galleryRows.addAll(gallerySaved);
        ArrayList<String> available=new ArrayList<>();for(PhotoItem item:gallerySaved)available.add(item.name);
        gallerySelection.available(available);updateGalleryControls();
        if(galleryAdapter!=null)galleryAdapter.notifyDataSetChanged();
    }
    private void updateGalleryControls() {
        if(galleryHeading==null)return;
        boolean choosing=gallerySelection.active();
        galleryHeading.setText(choosing?"已选 "+gallerySelection.size()+" 张":"图库 · 待保存 "+pendingItems.size()+" · 已保存 "+gallerySaved.size());
        galleryMode.setText(choosing?"取消":"多选");galleryMode.setEnabled(!deleting && (choosing || !gallerySaved.isEmpty()));
        galleryClose.setEnabled(!deleting);
        galleryAll.setVisibility(choosing?View.VISIBLE:View.INVISIBLE);
        galleryDelete.setVisibility(choosing?View.VISIBLE:View.INVISIBLE);
        galleryAll.setText(gallerySelection.allSelected()?"取消全选":"全选");
        galleryAll.setEnabled(!deleting && gallerySelection.availableCount()>0);
        galleryDelete.setText("删除"+(gallerySelection.size()>0?" ("+gallerySelection.size()+")":""));
        galleryDelete.setEnabled(!deleting && gallerySelection.size()>0);
        galleryHint.setText(deleting?"正在删除…":choosing?"仅选择已保存照片":"长按可多选照片");
    }
    private void showGallery(java.util.List<PhotoItem> items) {
        if(photoActionsBusy() || gallery!=null && gallery.isShowing())return;
        gallerySelection.exit();
        LinkedHashMap<String,PhotoItem> saved=new LinkedHashMap<>();
        for(PhotoItem item:items)saved.put(item.name,item);for(PhotoItem h:history)saved.putIfAbsent(h.name,h);
        gallerySaved.clear();gallerySaved.addAll(saved.values());
        gallerySaved.sort(Comparator.comparingLong((PhotoItem p)->p.modified).reversed());refreshGalleryRows();
        Dialog dialog=new Dialog(this);gallery=dialog;
        LinearLayout content=column();content.setBackgroundColor(BG);
        content.setOnApplyWindowInsetsListener((v,insets) -> {
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        LinearLayout bar=row();bar.setPadding(dp(16),0,dp(8),0);
        galleryHeading=text("图库",16,INK);galleryHeading.setSingleLine(true);galleryHeading.setEllipsize(TextUtils.TruncateAt.END);
        galleryHeading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);bar.addView(galleryHeading,new LinearLayout.LayoutParams(0,-2,1));
        galleryMode=button("多选",false);bar.addView(galleryMode,new LinearLayout.LayoutParams(dp(72),dp(48)));
        galleryMode.setOnClickListener(v -> {if(deleting)return;if(gallerySelection.active())gallerySelection.exit();else gallerySelection.enter();refreshGalleryRows();});
        galleryClose=iconButton(R.drawable.ic_close,"关闭图库");galleryClose.setOnClickListener(v -> {if(!deleting)dialog.dismiss();});bar.addView(galleryClose);
        content.addView(bar,new LinearLayout.LayoutParams(-1,dp(60)));
        GridView grid=new GridView(this);grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(getResources().getConfiguration().screenWidthDp>=600?164:120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(12),dp(4),dp(12),dp(12));grid.setClipToPadding(false);
        galleryAdapter=new BaseAdapter() {
            public int getCount(){return galleryRows.size();}
            public PhotoItem getItem(int position){return galleryRows.get(position);}
            public long getItemId(int position){return getItem(position).key.hashCode();}
            public View getView(int position,View recycled,ViewGroup parent) {
                LinearLayout cell;ImageView image;TextView label,meta;CheckBox check;
                if(recycled==null) {
                    cell=column();cell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);cell.setPadding(dp(4),dp(4),dp(4),dp(6));
                    FrameLayout picture=new FrameLayout(MainActivity.this);
                    image=new ThumbnailView(MainActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(Color.rgb(6,8,9));
                    picture.addView(image,new FrameLayout.LayoutParams(-1,-2));
                    check=new CheckBox(MainActivity.this);check.setClickable(false);check.setFocusable(false);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    check.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));check.setBackground(shape(0xB0101214,18));
                    picture.addView(check,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP|Gravity.END));
                    label=text("",12,INK);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.MIDDLE);label.setPadding(dp(4),dp(6),dp(4),0);
                    meta=text("",10,MUTED);meta.setSingleLine(true);meta.setEllipsize(TextUtils.TruncateAt.END);meta.setPadding(dp(4),dp(2),dp(4),0);
                    cell.addView(picture,matchWrap());cell.addView(label,matchWrap());cell.addView(meta,matchWrap());cell.setTag(new Object[]{image,label,meta,check});
                } else {cell=(LinearLayout)recycled;Object[] views=(Object[])cell.getTag();image=(ImageView)views[0];label=(TextView)views[1];meta=(TextView)views[2];check=(CheckBox)views[3];}
                PhotoItem file=getItem(position);boolean chosen=!file.pending() && gallerySelection.contains(file.name);
                GradientDrawable bg=shape(chosen?0xFF303F2C:PANEL,8);if(chosen || !gallerySelection.active() && file.equals(selected))bg.setStroke(dp(2),GREEN);cell.setBackground(bg);
                check.setVisibility(gallerySelection.active() && !file.pending()?View.VISIBLE:View.GONE);check.setChecked(chosen);
                cell.setContentDescription(file.displayName()+ (file.pending()?"，待保存，不能删除":gallerySelection.active()?(chosen?"，已选择":"，未选择"):"，已保存"));
                label.setText(file.displayName());meta.setText(file.pending()?pendingText(file)+" · "+String.format(Locale.CHINA,"%.1f MB",file.size/1048576.0):"已保存 · "+getString(R.string.gallery_metadata,new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(file.modified)),file.size/1048576.0));
                meta.setTextColor(file.pending()?GREEN:MUTED);image.setContentDescription(file.displayName());loadThumbnail(file,image);return cell;
            }
        };
        grid.setAdapter(galleryAdapter);grid.setOnItemClickListener((parent,view,position,id) -> {
            if(deleting)return;PhotoItem chosen=galleryRows.get(position);
            if(gallerySelection.active()) {if(chosen.pending())toast("待保存照片暂不能删除");else {gallerySelection.toggle(chosen.name);refreshGalleryRows();}return;}
            follow=false;updateFollow();showPhoto(chosen,"");dialog.dismiss();
        });
        grid.setOnItemLongClickListener((parent,view,position,id) -> {
            if(deleting)return true;PhotoItem chosen=galleryRows.get(position);
            if(chosen.pending())toast("待保存照片暂不能删除");else {gallerySelection.toggle(chosen.name);refreshGalleryRows();}return true;
        });
        FrameLayout body=new FrameLayout(this);body.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        TextView noPhotos=text("还没有照片\n连接相机拍摄后，照片会出现在这里",15,MUTED);noPhotos.setGravity(Gravity.CENTER);
        body.addView(noPhotos,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(noPhotos);
        content.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row();footer.setPadding(dp(8),0,dp(8),0);footer.setBackgroundColor(PANEL);
        galleryAll=button("全选",false);footer.addView(galleryAll,new LinearLayout.LayoutParams(dp(88),dp(48)));
        galleryAll.setOnClickListener(v -> {if(!deleting){gallerySelection.selectAll(!gallerySelection.allSelected());refreshGalleryRows();}});
        galleryHint=text("",12,MUTED);galleryHint.setSingleLine(true);galleryHint.setEllipsize(TextUtils.TruncateAt.END);galleryHint.setGravity(Gravity.CENTER);
        footer.addView(galleryHint,new LinearLayout.LayoutParams(0,-2,1));
        galleryDelete=button("删除",false);galleryDelete.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(92,104,108),Color.rgb(255,155,140)}));
        footer.addView(galleryDelete,new LinearLayout.LayoutParams(dp(104),dp(48)));galleryDelete.setOnClickListener(v -> confirmGalleryDelete());
        content.addView(footer,new LinearLayout.LayoutParams(-1,dp(56)));
        dialog.setContentView(content);dialog.setOnDismissListener(d -> {if(gallery==dialog){if(deleteConfirmation!=null)deleteConfirmation.dismiss();gallery=null;galleryAdapter=null;galleryHeading=null;galleryHint=null;galleryMode=galleryAll=galleryDelete=null;galleryClose=null;gallerySaved.clear();galleryRows.clear();gallerySelection.exit();}});
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setDecorFitsSystemWindows(false);}
        updateGalleryControls();dialog.show();if(window!=null)window.setLayout(-1,-1);content.requestApplyInsets();
    }
    private void confirmGalleryDelete() {
        if(deleting || gallerySelection.size()==0 || deleteConfirmation!=null)return;
        Set<String> names=gallerySelection.snapshot();ArrayList<PhotoItem> targets=new ArrayList<>();
        for(PhotoItem item:gallerySaved)if(names.contains(item.name))targets.add(item);
        confirmPhotoDelete(targets);
    }
    private void confirmPhotoDelete(List<PhotoItem> items) {
        if(photoActionsBusy() || items.isEmpty() || deleteConfirmation!=null)return;
        ArrayList<PhotoItem> targets=new ArrayList<>(items);
        deleteConfirmation=new AlertDialog.Builder(this).setTitle(targets.size()==1?"删除这张照片？":"删除 "+targets.size()+" 张照片？")
            .setMessage((targets.size()==1?targets.get(0).displayName()+"\n\n":"")+"将永久删除所选照片在 App 图库中的原图，以及本 App 保存到系统相册或下载目录的同名副本。\n\n无法撤销。待保存照片和相机存储卡不受影响。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w) -> deletePhotos(targets)).create();
        deleteConfirmation.setOnDismissListener(d -> deleteConfirmation=null);deleteConfirmation.show();
    }
    private void deletePhotos(List<PhotoItem> targets) {
        if(photoActionsBusy() || targets.isEmpty())return;
        deleting=true;photoBatchDone=0;photoBatchTotal=targets.size();updateStripActions();
        if(gallery!=null)gallery.setCancelable(false);refreshGalleryRows();TetherService connectedService=service;
        files.execute(() -> {
            Set<String> succeeded=new HashSet<>(),targetNames=new HashSet<>();ArrayList<String> errors=new ArrayList<>();long lastProgress=0;int done=0;
            for(PhotoItem item:targets) {
                targetNames.add(item.name);
                try {photos.delete(item);succeeded.add(item.name);}catch(IOException e){errors.add(item.displayName()+"："+e.getMessage());}
                done++;long now=SystemClock.uptimeMillis();
                if(now-lastProgress>=100 || done==targets.size()) {lastProgress=now;int completed=done;runOnUiThread(() -> {if(!destroyed){photoBatchDone=completed;updateStripActions();if(galleryHint!=null)galleryHint.setText("正在删除 "+completed+" / "+targets.size());}});}
            }
            List<PhotoItem> fresh=null;try {fresh=photos.recent();}catch(IOException e){errors.add("刷新图库失败："+e.getMessage());}
            List<PhotoItem> remaining=fresh;
            runOnUiThread(() -> {
                if(connectedService!=null && connectedService.latest!=null && succeeded.contains(connectedService.latest.name)){connectedService.latest=null;connectedService.latestMillis=0;}
                if(destroyed)return;
                Set<String> remove=remaining!=null?targetNames:succeeded;
                LinkedHashMap<String,PhotoItem> merged=new LinkedHashMap<>();
                if(remaining!=null)for(PhotoItem item:remaining)merged.put(item.name,item);
                for(PhotoItem item:gallerySaved)if(!remove.contains(item.name))merged.putIfAbsent(item.name,item);
                for(PhotoItem item:history)if(!remove.contains(item.name))merged.putIfAbsent(item.name,item);
                ArrayList<PhotoItem> updated=new ArrayList<>(merged.values());updated.sort(Comparator.comparingLong((PhotoItem p)->p.modified).thenComparing(p->p.name).reversed());
                PhotoItem previous=selected;
                boolean reset=selected!=null && !selected.pending() && targetNames.contains(selected.name) && !merged.containsKey(selected.name);
                PhotoItem replacement=selected==null?null:merged.get(selected.name);
                history.clear();strip.removeAllViews();thumbnailViews.clear();thumbnailCache.evictAll();
                for(PhotoItem p:pendingItems.values())bindThumbnail(p,strip.getChildCount());
                for(int i=0;i<Math.min(30,updated.size());i++)addHistory(updated.get(i),false);
                savedCount.setText(history.isEmpty()?"拍摄序列":getString(R.string.capture_count,history.size()));
                if(reset) {
                    ++previewGeneration;selected=null;waitingForOriginal=false;photoMetadata=PhotoMetadata.EMPTY;refreshParameterPanel();photo.photo(null);export.setEnabled(false);
                    title.setText("等待拍摄");detail.setText("");empty.setText("连接相机，开始拍摄");empty.setVisibility(View.VISIBLE);
                    PhotoItem next=null;
                    if(!follow && !updated.isEmpty()) {
                        for(PhotoItem item:updated)if(item.modified<previous.modified || item.modified==previous.modified && item.name.compareTo(previous.name)<0){next=item;break;}
                        if(next==null)next=updated.get(updated.size()-1);
                    }
                    if(next!=null)showPhoto(next,"");else resumeLatest();
                } else if(replacement!=null && !replacement.equals(selected))showPhoto(replacement,"");
                deleting=false;
                for(String name:succeeded)if(stripSelection.contains(name)){stripSelection.toggle(name);stripChosen.remove(name);}
                if(errors.isEmpty() && stripSelection.active())endStripSelection(true);else updateSelection();
                if(gallery!=null){gallery.setCancelable(true);gallerySaved.clear();gallerySaved.addAll(updated);if(errors.isEmpty())gallerySelection.exit();refreshGalleryRows();}
                if(errors.isEmpty())toast("已删除 "+succeeded.size()+" 张照片");
                else new AlertDialog.Builder(this).setTitle("已删除 "+succeeded.size()+" / "+targets.size()+" 张")
                    .setMessage("未完成的照片仍保留在图库，请检查后重试。\n\n"+String.join("\n",errors.subList(0,Math.min(5,errors.size())))+(errors.size()>5?"\n…":""))
                    .setPositiveButton("知道了",null).show();
            });
        });
    }
    private static final class ThumbnailView extends ImageView {
        ThumbnailView(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=MeasureSpec.getSize(widthSpec); setMeasuredDimension(width,Math.round(width*2/3f));
        }
    }
    private void updateFollow() {
        followButton.setText(stripSelection.active()?"取消":follow?"● 跟随":"回到最新"); followButton.setTextColor(stripSelection.active() || follow?GREEN:INK);
        followButton.setContentDescription(stripSelection.active()?"取消缩略图多选":follow?"正在跟随新照片，点按暂停":"跟随已暂停，点按显示最新照片"); followButton.setSelected(follow);
    }
    private void exportSelected() {
        PhotoItem file=selected;if(file==null || file.pending() || photoActionsBusy())return;exportPhotos(Collections.singletonList(file));
    }
    private void exportPhotos(List<PhotoItem> items) {
        if(photoActionsBusy() || items.isEmpty())return;
        ArrayList<PhotoItem> targets=new ArrayList<>();for(PhotoItem item:items)if(!item.pending())targets.add(item);
        if(targets.isEmpty())return;
        exporting=true;photoBatchDone=0;photoBatchTotal=targets.size();updateStripActions();
        files.execute(() -> {
            Set<String> succeeded=new HashSet<>();ArrayList<String> errors=new ArrayList<>();int done=0;
            for(PhotoItem item:targets) {
                try{photos.publish(item);succeeded.add(item.name);}catch(IOException e){errors.add(item.displayName()+"："+e.getMessage());}
                int completed=++done;runOnUiThread(() -> {if(!destroyed){photoBatchDone=completed;updateStripActions();}});
            }
            runOnUiThread(() -> {
                if(destroyed)return;exporting=false;
                for(String name:succeeded)if(stripSelection.contains(name)){stripSelection.toggle(name);stripChosen.remove(name);}
                if(errors.isEmpty() && stripSelection.active())endStripSelection(true);else updateSelection();
                if(errors.isEmpty())toast("已导出 "+succeeded.size()+" 张 · JPEG 到系统相册，RAF 到下载/XT5Tether");
                else new AlertDialog.Builder(this).setTitle("已导出 "+succeeded.size()+" / "+targets.size()+" 张")
                    .setMessage("未完成的照片仍保留，可稍后重试。\n\n"+String.join("\n",errors.subList(0,Math.min(5,errors.size()))))
                    .setPositiveButton("知道了",null).show();
            });
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request != 30 || result != RESULT_OK || data == null || data.getData() == null || service == null) return;
        File log = service.logFile(); Uri destination = data.getData();
        files.execute(() -> {
            try (InputStream in = new FileInputStream(log); OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("无法写入日志"); byte[] buffer=new byte[8192]; int n;
                while((n=in.read(buffer))!=-1) out.write(buffer,0,n); runOnUiThread(() -> toast("日志已导出"));
            } catch (IOException e) { runOnUiThread(() -> toast("暂无日志或导出失败："+e.getMessage())); }
        });
    }
    @Override protected void onDestroy() {
        destroyed = true; previewGeneration++;
        if (service != null) service.removeObserver(this);
        if (bound) unbindService(binding);
        unregisterReceiver(permissionReceiver); unregisterReceiver(attachReceiver);
        if(parameterDialog!=null)parameterDialog.dismiss();
        if(settingsDialog!=null)settingsDialog.dismiss();
        if(deleteConfirmation!=null)deleteConfirmation.dismiss();
        if (gallery != null) gallery.dismiss();
        if (focusMode && Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(exitFocus);
        decoding.shutdown(); thumbnails.shutdown(); thumbnailCache.evictAll(); photo.dispose(); super.onDestroy();
    }
    private void toast(String message) { if (!destroyed) Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1,-2); }
    private TextView text(String value,int size,int color) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private Button button(String value,boolean accent) {
        Button b=new Button(this); b.setText(value); b.setTextSize(12); b.setAllCaps(false); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(12),0,dp(12),0); b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(92,104,108),accent?BG:INK}));
        GradientDrawable surface=shape(accent?GREEN:SURFACE,12);if(!accent)surface.setStroke(dp(1),BORDER);
        b.setBackground(new android.graphics.drawable.InsetDrawable(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x304B633D),surface,null),dp(3),dp(4),dp(3),dp(4)));return b;
    }
    private ImageButton iconButton(int icon,String description) {
        ImageButton button=new ImageButton(this); button.setImageResource(icon); button.setContentDescription(description);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(INK)); button.setPadding(dp(13),dp(13),dp(13),dp(13));
        button.setBackground(new android.graphics.drawable.InsetDrawable(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x303C5728),shape(PANEL,12),null),dp(4)));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48))); return button;
    }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
}
