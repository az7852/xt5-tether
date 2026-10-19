package io.github.xt5tether.ptp;

import java.util.*;

/** Bounded UI snapshots of camera originals that have not finished local publication. */
public final class CaptureQueue {
    public static final int LIMIT=32;
    public static final class Entry {
        public final long id,total,received;
        public final String name,state;
        public final byte[] preview;
        Entry(long id,String name,long total,long received,String state,byte[] preview) {
            this.id=id;this.name=name;this.total=total;this.received=received;this.state=state;this.preview=preview;
        }
    }
    private final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>();
    public synchronized void discovered(long id,String name,long total) {
        if(!entries.containsKey(id) && entries.size()<LIMIT)entries.put(id,new Entry(id,name,total,0,"待下载",null));
    }
    public synchronized void preview(long id,byte[] jpeg) {
        Entry e=entries.get(id);
        if(e!=null && jpeg.length<=256*1024)entries.put(id,new Entry(id,e.name,e.total,e.received,e.state,jpeg));
    }
    public synchronized void progress(long id,long received) {
        Entry e=entries.get(id);
        if(e!=null)entries.put(id,new Entry(id,e.name,e.total,Math.max(e.received,Math.min(e.total,received)),
            received>=e.total?"正在保存":"下载中",e.preview));
    }
    public synchronized void waiting(boolean paused) {
        for(Entry e:new ArrayList<>(entries.values())) {
            if(e.received==0)entries.put(e.id,new Entry(e.id,e.name,e.total,0,paused?"等待开始保存":"待下载",e.preview));
        }
    }
    public synchronized void saved(long id) {entries.remove(id);}
    public synchronized void disconnected() {
        for(Entry e:new ArrayList<>(entries.values()))entries.put(e.id,new Entry(e.id,e.name,e.total,e.received,"未保存 · 已断开",e.preview));
    }
    public synchronized void clear() {entries.clear();}
    public synchronized List<Entry> snapshot() {
        ArrayList<Entry> result=new ArrayList<>(entries.values());result.sort((a,b)->Long.compare(b.id,a.id));return result;
    }
}
