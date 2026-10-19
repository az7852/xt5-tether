package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class TetherEngineTest {
    private static byte[] shorts(int... values) {
        ByteBuffer b=ByteBuffer.allocate(4+values.length*2).order(ByteOrder.LITTLE_ENDIAN);b.putInt(values.length);for(int v:values)b.putShort((short)v);return b.array();
    }
    static byte[] device(boolean tether) throws IOException {
        return device(tether,false);
    }
    static byte[] device(boolean tether,boolean thumb) throws IOException {
        return PtpClientTest.join(new byte[8],PtpClientTest.ptpString(""),new byte[2],thumb?shorts(0x1007,0x1008,0x1009,0x100A,0x100B):shorts(0x1007,0x1008,0x1009,0x100B),shorts(),tether?shorts(PtpClient.PRIORITY):shorts(),shorts(),shorts(),
                PtpClientTest.ptpString("FUJIFILM"),PtpClientTest.ptpString("X-T5"),PtpClientTest.ptpString("test"),PtpClientTest.ptpString("test"));
    }
    static final class CameraPipe implements PtpIo {
        byte[] response=new byte[0];int position;int downloads,deletes,releaseAttempts,shots;
        boolean tether=true,wrongStore,shortFile,failResponse,releaseBusy,advertiseThumb;int priority=1;
        Runnable deleted=()->{};boolean savedBeforeDelete;
        File directory;
        public void write(byte[] bytes) throws IOException {
            ByteBuffer b=PtpClient.le(bytes);int type=Short.toUnsignedInt(b.getShort(4)),op=Short.toUnsignedInt(b.getShort(6)),tx=b.getInt(8);
            if(type==2) { priority=Short.toUnsignedInt(b.getShort(12)); reply(0x2001,tx,null,op);return; }
            byte[] payload=null;int rc=0x2001;
            switch(op) {
                case 0x1001:payload=device(tether,advertiseThumb);break;
                case 0x100A:throw new AssertionError("JPEG must stream its embedded preview without an extra camera transaction");
                case 0x1015:payload=new byte[]{(byte)priority,0};break;
                case 0x1016:response=new byte[0];position=0;return;
                case 0x1007:
                    assertEquals(PtpClient.STILL_STORE,Integer.toUnsignedLong(b.getInt(12)));
                    payload=PtpClientTest.words(1,1);break;
                case 0x1008:payload=PtpClientTest.info(wrongStore?0x00010001:PtpClient.STILL_STORE,4,"DSCF0001.JPG");break;
                case 0x1009:downloads++;payload=new byte[shortFile?3:4];if(failResponse)rc=0x2002;break;
                case 0x101B:rc=0x2005;break;
                case 0x100B:
                    releaseAttempts++;
                    if(releaseBusy && releaseAttempts==1){rc=0x2019;break;}
                    deletes++;shots++;
                    File[] captures=directory.listFiles(f->f.getName().endsWith(".JPG"));
                    savedBeforeDelete=captures!=null&&captures.length==downloads&&Arrays.stream(captures).allMatch(f->f.length()==4);
                    deleted.run();break;
                default:break;
            }
            reply(rc,tx,payload,op);
        }
        void reply(int rc,int tx,byte[] payload,int op)throws IOException {
            response=payload==null?PtpClientTest.frame(3,rc,tx,new byte[0]):PtpClientTest.join(PtpClientTest.frame(2,op,tx,payload),PtpClientTest.frame(3,rc,tx,new byte[0]));position=0;
        }
        public int read(byte[] out) {
            if(position==response.length)return -1;int n=Math.min(7,Math.min(out.length,response.length-position));System.arraycopy(response,position,out,0,n);position+=n;return n;
        }
        public void close(){}
    }
    static class Listener implements TetherEngine.Listener {
        int captures;public void status(String message){}public void ready(String model){}public void thumbnail(byte[] b,String name){}public void progress(String name,long n,long total){}
        public void saved(File file,long ms){captures++;}public void diagnostic(String text){}
    }
    private static void clean(File directory) {for(File f:Objects.requireNonNull(directory.listFiles()))f.delete();directory.delete();}
    @Test(timeout=5000) public void releasesOnlyAfterDurableSaveAndAcceptsReusedHandle() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;
        Listener listener=new Listener();TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),listener);
        pipe.deleted=()->{if(pipe.deletes==2)engine.stop();};engine.run();
        assertEquals(2,pipe.downloads);assertEquals(2,listener.captures);assertEquals(2,pipe.deletes);assertTrue(pipe.savedBeforeDelete);assertEquals(1,pipe.priority);clean(dir);
    }
    @Test(timeout=5000) public void busyReleaseRetriesWithoutDownloadingAgain() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.releaseBusy=true;
        Listener listener=new Listener();TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),listener);pipe.deleted=engine::stop;engine.run();
        assertEquals(1,pipe.downloads);assertEquals(1,listener.captures);assertEquals(2,pipe.releaseAttempts);assertTrue(pipe.savedBeforeDelete);clean(dir);
    }
    @Test(timeout=5000) public void truncatedFileNeverDeletesCameraCopy() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.shortFile=true;
        TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());assertThrows(IOException.class,engine::run);
        assertEquals(0,pipe.deletes);assertEquals(0,Objects.requireNonNull(dir.list()).length);clean(dir);
    }
    @Test(timeout=5000) public void negativeTransferResponseNeverDeletesCameraCopy() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.failResponse=true;
        TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());assertThrows(IOException.class,engine::run);
        assertEquals(0,pipe.deletes);assertEquals(0,Objects.requireNonNull(dir.list()).length);clean(dir);
    }
    @Test(timeout=5000) public void refusesCardStorageBeforeDownloadOrDelete() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.wrongStore=true;
        TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());assertThrows(IOException.class,engine::run);
        assertEquals(0,pipe.downloads);assertEquals(0,pipe.deletes);clean(dir);
    }
    @Test(timeout=5000) public void refusesCardReaderMode() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.tether=false;
        TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());assertThrows(IOException.class,engine::run);
        assertEquals(0,pipe.downloads);assertEquals(0,pipe.deletes);clean(dir);
    }
    @Test(timeout=5000) public void restoresCameraPriorityWithoutTakingHostPriority() throws Exception {
        File dir=Files.createTempDirectory("xt5-engine").toFile();CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.priority=2;
        TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());pipe.deleted=engine::stop;engine.run();assertEquals(1,pipe.priority);clean(dir);
    }
    @Test(timeout=5000) public void jpegStartsOriginalWithoutWaitingForSeparateThumbnail() throws Exception {
        File dir=Files.createTempDirectory("xt5-no-extra-thumb").toFile();
        try {
            CameraPipe pipe=new CameraPipe();pipe.directory=dir;pipe.advertiseThumb=true;
            TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener());
            pipe.deleted=engine::stop;engine.run();assertEquals(1,pipe.downloads);assertTrue(pipe.savedBeforeDelete);
        } finally {clean(dir);}
    }
    @Test(timeout=5000) public void failedFinalDestinationKeepsPrivateOriginalAndCameraCopy() throws Exception {
        File dir=Files.createTempDirectory("xt5-destination").toFile();
        try {
            CameraPipe pipe=new CameraPipe(); pipe.directory=dir; Listener listener=new Listener();
            TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),listener,true,
                    file -> { throw new IOException("album unavailable"); });
            CaptureSaveException failure=assertThrows(CaptureSaveException.class,engine::run);
            assertTrue(failure.file.isFile()); assertEquals(4,failure.file.length());
            assertEquals(0,pipe.deletes); assertEquals(0,listener.captures); assertEquals(1,pipe.downloads);
        } finally {clean(dir);}
    }
    @Test(timeout=5000) public void finalDestinationCommitsOnceAndCleanupWaitsForCameraRelease() throws Exception {
        File dir=Files.createTempDirectory("xt5-destination").toFile();
        try {
            CameraPipe pipe=new CameraPipe(); pipe.directory=dir; pipe.releaseBusy=true;
            int[] committed={0},cleaned={0};
            TetherEngine engine=new TetherEngine(new PtpClient(pipe),new CaptureStore(dir),new Listener(),true,new TetherEngine.Destination() {
                public void commit(File file) {assertEquals(0,pipe.releaseAttempts);assertEquals(4,file.length());committed[0]++;}
                public void released(File file) {assertEquals(1,pipe.deletes);assertEquals(2,pipe.releaseAttempts);assertTrue(file.delete());cleaned[0]++;}
            });
            pipe.deleted=()->{assertEquals(1,committed[0]);assertEquals(0,cleaned[0]);engine.stop();}; engine.run();
            assertEquals(1,pipe.downloads); assertEquals(1,committed[0]); assertEquals(1,cleaned[0]); assertEquals(0,dir.list().length);
        } finally {clean(dir);}
    }
}
