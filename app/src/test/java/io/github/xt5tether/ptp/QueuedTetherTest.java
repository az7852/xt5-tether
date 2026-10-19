package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class QueuedTetherTest {
    static byte[] original(int seed) throws IOException {
        byte[] quick=JpegQuickPreviewTest.jpeg(640,480);int length=quick.length+2;
        byte[] body=new byte[900000];new Random(seed).nextBytes(body);
        return PtpClientTest.join(new byte[]{(byte)255,(byte)216,(byte)255,(byte)226,(byte)(length>>8),(byte)length},quick,
            new byte[]{(byte)255,(byte)218,0,2},body);
    }
    static class Camera implements PtpIo {
        final Map<Long,byte[]> data=new LinkedHashMap<>();
        byte[] reply;int cursor,partials,deletes,polls,secondAtPoll,thumbnailGets,fullDownloads;
        boolean injectSecond=true,rejectPartial,busyOnce,advertiseThumb;
        boolean firstReleased;Runnable completed=()->{};
        Camera() throws IOException {data.put(1L,original(1));}
        public void write(byte[] command) throws IOException {
            ByteBuffer b=PtpClient.le(command);int op=Short.toUnsignedInt(b.getShort(6)),tx=b.getInt(8),rc=0x2001;
            byte[] payload=null;
            long handle=command.length>=16?Integer.toUnsignedLong(b.getInt(12)):0;
            switch(op) {
                case 0x1001:payload=TetherEngineTest.device(true,advertiseThumb);break;
                case 0x1015:payload=new byte[]{1,0};break;
                case 0x1007:
                    polls++;
                    if((partials>=1 || secondAtPoll>0 && polls>=secondAtPoll) && injectSecond && !data.containsKey(2L) && !firstReleased)data.put(2L,original(2));
                    ByteBuffer handles=ByteBuffer.allocate(4+data.size()*4).order(ByteOrder.LITTLE_ENDIAN);
                    handles.putInt(data.size());for(long h:data.keySet())handles.putInt((int)h);payload=handles.array();break;
                case 0x1008:payload=PtpClientTest.info(PtpClient.STILL_STORE,data.get(handle).length,"DSCF000"+handle+".JPG");break;
                case 0x101B:
                    if(rejectPartial) {rc=0x2005;break;}
                    if(busyOnce) {busyOnce=false;rc=0x2019;break;}
                    int offset=b.getInt(16),count=b.getInt(20);byte[] source=data.get(handle);
                    assertTrue(count<=256*1024);assertTrue(offset>=0 && offset<source.length);
                    payload=Arrays.copyOfRange(source,offset,Math.min(source.length,offset+count));partials++;
                    // One bounded block takes time, allowing new-frame polling during transfer.
                    try {Thread.sleep(80);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    break;
                case 0x1009:assertTrue(rejectPartial);fullDownloads++;payload=data.get(handle);break;
                case 0x100A:thumbnailGets++;payload=JpegQuickPreviewTest.jpeg(640,480);break;
                case 0x100B:
                    data.remove(handle);deletes++;if(handle==1)firstReleased=true;
                    if(data.isEmpty())completed.run();break;
                default:break;
            }
            reply=payload==null?PtpClientTest.frame(3,rc,tx,new byte[0]):PtpClientTest.join(PtpClientTest.frame(2,op,tx,payload),PtpClientTest.frame(3,rc,tx,new byte[0]));cursor=0;
        }
        public int read(byte[] out) {int n=Math.min(out.length,reply.length-cursor);if(n==0)return -1;System.arraycopy(reply,cursor,out,0,n);cursor+=n;return n;}
        public void close() { }
    }
    private static void clean(File dir) {for(File f:Objects.requireNonNull(dir.listFiles()))f.delete();dir.delete();}
    @Test(timeout=10000) public void previewsNewShotBeforeOlderOriginalFinishesAndSavesExactBytes() throws Exception {
        File dir=Files.createTempDirectory("xt5-queue").toFile();Camera camera=new Camera();
        try {
            List<Long> previewIds=new ArrayList<>();List<String> saved=new ArrayList<>();
            TetherEngine.Listener listener=new TetherEngineTest.Listener() {
                @Override public void thumbnail(byte[] bytes,String name,long id) {
                    if(name.equals("DSCF0002.JPG"))assertFalse(camera.firstReleased);
                    previewIds.add(id);
                }
                @Override public void saved(File file,long ms,long id) {
                    saved.add(CaptureStore.displayName(file));
                    try {assertArrayEquals(original(saved.size()),Files.readAllBytes(file.toPath()));}catch(IOException e){throw new AssertionError(e);}
                }
            };
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);
            camera.completed=engine::stop;engine.run();
            assertEquals(Arrays.asList("DSCF0001.JPG","DSCF0002.JPG"),saved);
            assertEquals(2,previewIds.size());assertTrue(previewIds.get(1)>previewIds.get(0));assertEquals(2,camera.deletes);
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void unsupportedPartialFallsBackWithoutLosingOriginal() throws Exception {
        File dir=Files.createTempDirectory("xt5-fallback").toFile();Camera camera=new Camera();camera.rejectPartial=true;camera.injectSecond=false;
        try {
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),new TetherEngineTest.Listener(),true);
            camera.completed=engine::stop;engine.run();assertEquals(1,camera.deletes);
            assertArrayEquals(original(1),Files.readAllBytes(new CaptureStore(dir).recent().get(0).toPath()));
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void publishFailureNeverReleasesAnyIncompleteOrFailedOriginal() throws Exception {
        File dir=Files.createTempDirectory("xt5-queued-publish").toFile();Camera camera=new Camera();
        try {
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),new TetherEngineTest.Listener(),true,
                file->{throw new IOException("album failed");});
            CaptureSaveException e=assertThrows(CaptureSaveException.class,engine::run);
            assertEquals(0,camera.deletes);assertArrayEquals(original(1),Files.readAllBytes(e.file.toPath()));
            assertEquals(0,Objects.requireNonNull(dir.listFiles(f->f.getName().endsWith(".part"))).length);
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void busyChunkRetriesAtSameOffsetWithoutDuplicatingBytes() throws Exception {
        File dir=Files.createTempDirectory("xt5-queued-busy").toFile();Camera camera=new Camera();camera.busyOnce=true;camera.injectSecond=false;
        try {
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),new TetherEngineTest.Listener(),true);
            camera.completed=engine::stop;engine.run();
            assertArrayEquals(original(1),Files.readAllBytes(new CaptureStore(dir).recent().get(0).toPath()));assertEquals(1,camera.deletes);
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void stoppingAfterPreviewKeepsCameraCopyAndPublishesNoPartialOriginal() throws Exception {
        File dir=Files.createTempDirectory("xt5-queued-stop").toFile();Camera camera=new Camera();camera.injectSecond=false;
        try {
            TetherEngine[] engine={null};
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener() {
                @Override public void thumbnail(byte[] bytes,String name,long id) {engine[0].stop();}
            };
            engine[0]=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);
            engine[0].run();assertEquals(0,camera.deletes);assertTrue(camera.data.containsKey(1L));assertEquals(0,Objects.requireNonNull(dir.list()).length);
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void thumbnailFallbackPreviewsNewShotsBeforeDownloadingQueuedOriginals() throws Exception {
        File dir=Files.createTempDirectory("xt5-thumbnail-queue").toFile();Camera camera=new Camera();
        camera.rejectPartial=true;camera.advertiseThumb=true;camera.secondAtPoll=4;
        try {
            List<String> previews=new ArrayList<>();List<String> saved=new ArrayList<>();
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener() {
                @Override public void thumbnail(byte[] jpeg,String name,long id) {
                    if(camera.fullDownloads==0)previews.add(name);
                }
                @Override public void saved(File file,long ms,long id) {
                    saved.add(CaptureStore.displayName(file));
                    try {assertArrayEquals(original(saved.size()),Files.readAllBytes(file.toPath()));}catch(IOException e){throw new AssertionError(e);}
                }
            };
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);
            camera.completed=engine::stop;engine.run();
            assertEquals(Arrays.asList("DSCF0001.JPG","DSCF0002.JPG"),previews);
            assertEquals(previews,saved);assertEquals(2,camera.deletes);assertEquals(2,camera.thumbnailGets);
        } finally {clean(dir);}
    }
    @Test(timeout=10000) public void quietModeWaitsThreeSecondsBeforeOriginalTransaction() throws Exception {
        File dir=Files.createTempDirectory("xt5-quiet-timing").toFile();Camera camera=new Camera();camera.rejectPartial=true;camera.advertiseThumb=true;camera.injectSecond=false;
        try {
            long[] detected={0},started={0};
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener(){
                @Override public void queued(long id,String name,long size){detected[0]=System.nanoTime();}
                @Override public void transferStarted(long id){started[0]=System.nanoTime();}
            };
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);camera.completed=engine::stop;engine.run();
            assertTrue("Quiet mode must retain the three-second window",started[0]-detected[0]>=2_900_000_000L);
            assertEquals(1,camera.fullDownloads);assertEquals(1,camera.deletes);
            assertArrayEquals(original(1),Files.readAllBytes(new CaptureStore(dir).recent().get(0).toPath()));
        }finally{clean(dir);}
    }
    @Test(timeout=10000) public void immediateModePreviewsThenDownloadsWithoutEnteringQuietWait() throws Exception {
        File dir=Files.createTempDirectory("xt5-immediate-timing").toFile();Camera camera=new Camera();camera.rejectPartial=true;camera.advertiseThumb=true;camera.injectSecond=false;
        try {
            int[] waiting={0};boolean[] previewed={false};
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener(){
                @Override public void status(String text){if(text.startsWith("优先预览 · 停拍"))waiting[0]++;}
                @Override public void thumbnail(byte[] bytes,String name,long id){if(!previewed[0])assertEquals(0,camera.fullDownloads);previewed[0]=true;}
                @Override public void transferStarted(long id){assertTrue(previewed[0]);}
            };
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);engine.immediateDownloads(()->true);
            camera.completed=engine::stop;engine.run();assertEquals(0,waiting[0]);assertEquals(1,camera.fullDownloads);assertEquals(1,camera.deletes);
            assertArrayEquals(original(1),Files.readAllBytes(new CaptureStore(dir).recent().get(0).toPath()));
        }finally{clean(dir);}
    }
    @Test(timeout=10000) public void switchingExistingSessionToImmediateReleasesWaitingOriginal() throws Exception {
        File dir=Files.createTempDirectory("xt5-live-timing").toFile();Camera camera=new Camera();camera.rejectPartial=true;camera.advertiseThumb=true;camera.injectSecond=false;
        try {
            java.util.concurrent.atomic.AtomicBoolean immediate=new java.util.concurrent.atomic.AtomicBoolean(false);int[] waiting={0};
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener(){
                @Override public void status(String text){if(text.startsWith("优先预览 · 停拍")){assertEquals(0,camera.fullDownloads);waiting[0]++;immediate.set(true);}}
            };
            TetherEngine engine=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);engine.immediateDownloads(immediate::get);
            camera.completed=engine::stop;engine.run();assertEquals(1,waiting[0]);assertEquals(1,camera.fullDownloads);assertEquals(1,camera.deletes);
        }finally{clean(dir);}
    }
    @Test(timeout=10000) public void immediateModeStillHonorsManualPause() throws Exception {
        File dir=Files.createTempDirectory("xt5-paused-immediate").toFile();Camera camera=new Camera();camera.rejectPartial=true;camera.advertiseThumb=true;camera.injectSecond=false;
        try {
            TetherEngine[] engine={null};boolean[] paused={false};
            TetherEngineTest.Listener listener=new TetherEngineTest.Listener(){
                @Override public void status(String text){if(text.startsWith("拍摄优先 ·")){assertEquals(0,camera.fullDownloads);assertEquals(0,camera.deletes);paused[0]=true;engine[0].pauseDownloads(false);}}
            };
            engine[0]=new TetherEngine(new PtpClient(camera),new CaptureStore(dir),listener,true);engine[0].immediateDownloads(()->true);engine[0].pauseDownloads(true);
            camera.completed=engine[0]::stop;engine[0].run();assertTrue(paused[0]);assertEquals(1,camera.fullDownloads);assertEquals(1,camera.deletes);
            assertArrayEquals(original(1),Files.readAllBytes(new CaptureStore(dir).recent().get(0).toPath()));
        }finally{clean(dir);}
    }
}
