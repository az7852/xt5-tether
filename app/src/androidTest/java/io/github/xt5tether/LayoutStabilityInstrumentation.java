package io.github.xt5tether;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import io.github.xt5tether.ptp.CaptureQueue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Real-device regression: status changes must not resize or move the photo workspace.
 * Uses in-memory states only, and refuses to modify an active camera session or queue.
 */
public final class LayoutStabilityInstrumentation extends Instrumentation {
    private MainActivity activity;
    private TetherService service;
    private CaptureQueue queue;
    private final Map<String,Object> original=new LinkedHashMap<>();
    private int checks;

    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try {
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
            try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand(
                    "am start -n io.github.xt5tether/.MainActivity")) {
                activity=(MainActivity)waitForMonitorWithTimeout(monitor,10000);
            } finally {removeMonitor(monitor);}
            if(activity==null)throw new AssertionError("Activity did not start within 10 seconds");
            onMain(() -> {activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);return null;});
            for(int i=0;i<100;i++) {
                service=onMain(() -> (TetherService)field(activity,"service"));
                if(service!=null)break;
                SystemClock.sleep(50);
            }
            if(service==null)throw new AssertionError("Service did not bind");
            onMain(() -> {
                queue=(CaptureQueue)field(service,"captureQueue");
                if(service.connected || service.busy || !queue.snapshot().isEmpty())
                    throw new AssertionError("Refusing to modify an active camera session or pending queue");
                for(String name:new String[]{"connected","busy","percent","message","error","downloadsPaused","receivingOriginal","receiveSummary","receiveSpeed"})
                    original.put(name,field(service,name));
                return null;
            });
            settle();
            exercise(false);
            onMain(() -> {focus(true);return null;});settle();
            exercise(true);
            onMain(() -> {
                focus(false);
                activity.getWindow().setLayout(Math.round(400*activity.getResources().getDisplayMetrics().density),WindowManager.LayoutParams.MATCH_PARENT);
                return null;
            });settle();
            onMain(() -> {if((boolean)field(activity,"wideLayout"))throw new AssertionError("Narrow window did not use bottom filmstrip");return null;});
            exercise(false);
            onMain(() -> {focus(true);return null;});settle();exercise(true);
            result.putString("stream","\nPASS: "+checks+" stable real-device layouts, including a 400dp narrow window; photo, header, queue and controls retain bounds through reconnect, progress, error, pause and queue 0/1/32.\n");
        } catch(Throwable e) {
            code=Activity.RESULT_CANCELED;
            result.putString("stream","\nFAIL: "+android.util.Log.getStackTraceString(e)+"\n");
        } finally {
            try {
                if(!original.isEmpty())onMain(() -> {
                    for(Map.Entry<String,Object> e:original.entrySet())set(service,e.getKey(),e.getValue());
                    queue.clear();focus(false);activity.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);activity.changed();return null;
                });
                if(activity!=null)onMain(() -> {activity.finish();return null;});
            } catch(Exception e) {code=Activity.RESULT_CANCELED;result.putString("cleanup",e.toString());}
        }
        finish(code,result);
    }
    private void exercise(boolean fullscreen) throws Exception {
        Map<String,int[]> baseline=onMain(() -> bounds(fullscreen));
        for(int state=0;state<8;state++) {
            final int next=state;
            onMain(() -> {
                service.connected=next>=2 && next<=6;service.busy=next==1;
                service.percent=next==3 || next==4?57:0;
                service.receivingOriginal=next==3 || next==4;
                service.downloadsPaused=next==4 || next==5;
                service.message=next==1?"Wi-Fi 中断，正在重新连接（3/3）…请检查相机网络并等待握手完成":
                        "接收 DSCF0123456789.jpg · 57% · 5.7 / 10.0 MB · 0.30 MB/s · 待保存 32/32 张";
                service.error=next==7?"连接失败：无法访问相机，请检查无线联机固定模式\n详细错误内容超过一行时也不能挤动照片区域":"";
                service.receiveSummary="5.7 / 10.0 MB · 0.30 MB/s";
                service.receiveSpeed="0.30 MB/s";
                queue.clear();
                int count=next==2?1:next==6?32:0;
                for(int i=0;i<count;i++)queue.discovered(900000+i,"LAYOUT_TEST_"+i+".jpg",10485760);
                activity.changed();return null;
            });
            settle();
            Map<String,int[]> current=onMain(() -> bounds(fullscreen));
            for(String name:baseline.keySet())if(!Arrays.equals(baseline.get(name),current.get(name)))
                throw new AssertionError(name+" moved in state "+state+" fullscreen="+fullscreen+": "+
                        Arrays.toString(baseline.get(name))+" -> "+Arrays.toString(current.get(name)));
            checks++;
        }
    }
    private Map<String,int[]> bounds(boolean fullscreen) throws Exception {
        Map<String,int[]> result=new LinkedHashMap<>();
        String[] names=fullscreen?new String[]{"photo","focusExit"}:new String[]{"photo","header","statusBar","library","queueSummary","downloadButton","connect","followButton"};
        for(String name:names) {
            View view=(View)field(activity,name);int[] xy=new int[2];view.getLocationOnScreen(xy);
            if(view.getWidth()<=0 || view.getHeight()<=0)throw new AssertionError(name+" has no area");
            result.put(name,new int[]{xy[0],xy[1],view.getWidth(),view.getHeight()});
        }
        return result;
    }
    private void focus(boolean value) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("setFocusMode",boolean.class);m.setAccessible(true);m.invoke(activity,value);
    }
    private void settle() {waitForIdleSync();SystemClock.sleep(250);waitForIdleSync();}
    private <T> T onMain(Callable<T> task) throws Exception {
        FutureTask<T> future=new FutureTask<>(task);runOnMainSync(future);return future.get();
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
}
