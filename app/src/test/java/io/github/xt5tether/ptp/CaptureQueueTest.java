package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CaptureQueueTest {
    @Test public void unfinishedShotsAreVisibleBeforeSavingNewestFirst() {
        CaptureQueue q=new CaptureQueue();q.discovered(10,"A.JPG",100);q.discovered(11,"B.JPG",200);
        assertEquals(2,q.snapshot().size());assertEquals("B.JPG",q.snapshot().get(0).name);
        assertEquals("待下载",q.snapshot().get(0).state);
        q.progress(10,50);assertEquals(50,q.snapshot().get(1).received);
        q.saved(10);assertEquals("B.JPG",q.snapshot().get(0).name);
    }
    @Test public void progressAndPreviewDoNotCreateDuplicatesOrRegressBytes() {
        CaptureQueue q=new CaptureQueue();q.discovered(1,"A.JPG",100);byte[] preview={1,2,3};q.preview(1,preview);
        CaptureQueue.Entry before=q.snapshot().get(0);q.progress(1,70);q.progress(1,20);q.discovered(1,"A.JPG",100);
        assertEquals(1,q.snapshot().size());assertEquals(70,q.snapshot().get(0).received);
        assertSame(preview,q.snapshot().get(0).preview);assertEquals(0,before.received);
        q.progress(1,150);assertEquals(100,q.snapshot().get(0).received);assertEquals("正在保存",q.snapshot().get(0).state);
    }
    @Test public void limitsRecordsAndCachedPreviewBytesWithoutDroppingTrackedShots() {
        CaptureQueue q=new CaptureQueue();for(int i=0;i<40;i++)q.discovered(i,"A"+i+".JPG",1000);
        assertEquals(32,q.snapshot().size());q.preview(31,new byte[300*1024]);assertNull(q.snapshot().get(0).preview);
        q.saved(0);q.discovered(40,"LAST.JPG",1000);assertEquals(32,q.snapshot().size());assertEquals(40,q.snapshot().get(0).id);
    }
    @Test public void disconnectKeepsUnsavedEntriesAndTheirPreviewClearlyMarked() {
        CaptureQueue q=new CaptureQueue();q.discovered(1,"A.JPG",100);q.preview(1,new byte[]{1});q.progress(1,40);q.disconnected();
        assertEquals("未保存 · 已断开",q.snapshot().get(0).state);assertEquals(40,q.snapshot().get(0).received);assertNotNull(q.snapshot().get(0).preview);
        q.clear();assertTrue(q.snapshot().isEmpty());
    }
}
