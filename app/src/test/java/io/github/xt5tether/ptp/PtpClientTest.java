package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class PtpClientTest {
    static byte[] frame(int type, int code, int tx, byte[] payload) {
        return ByteBuffer.allocate(payload.length+12).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length+12)
                .putShort((short)type).putShort((short)code).putInt(tx).put(payload).array();
    }
    static byte[] join(byte[]... parts) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); for(byte[] p:parts) out.write(p); return out.toByteArray(); }
    static byte[] words(long... values) { ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN); for(long v:values)b.putInt((int)v);return b.array(); }
    static byte[] ptpString(String s) { ByteBuffer b=ByteBuffer.allocate(1+(s.length()+1)*2).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)(s.length()+1));for(char c:s.toCharArray())b.putChar(c);b.putChar((char)0);return b.array(); }
    static byte[] info(long storage,long size,String name) throws IOException {
        byte[] fixed=new byte[52];ByteBuffer b=PtpClient.le(fixed);b.putInt((int)storage).putShort((short)0x3801).putShort((short)0).putInt((int)size);return join(fixed,ptpString(name));
    }
    static class Pipe implements PtpIo {
        final byte[] bytes;int pos;int fragment=16384;final List<byte[]> writes=new ArrayList<>();
        Pipe(byte[] bytes){this.bytes=bytes;}
        public int read(byte[] out){if(pos==bytes.length)return -1;int n=Math.min(Math.min(fragment,out.length),bytes.length-pos);System.arraycopy(bytes,pos,out,0,n);pos+=n;return n;}
        public void write(byte[] bytes){writes.add(bytes);}
        public void close(){}
    }
    @Test public void fragmentedAndCoalescedContainers() throws Exception {
        byte[] payload=new byte[70001];new Random(9).nextBytes(payload);
        for(int fragment:new int[]{1,7,12,64,512,16384}) {
            Pipe pipe=new Pipe(join(frame(2,0x1009,0,payload),frame(3,0x2001,0,new byte[0])));pipe.fragment=fragment;
            ByteArrayOutputStream result=new ByteArrayOutputStream();new PtpClient(pipe).download(123,result,null);
            assertArrayEquals(payload,result.toByteArray());assertEquals(0x1009,Short.toUnsignedInt(PtpClient.le(pipe.writes.get(0)).getShort(6)));
        }
    }
    @Test public void unsignedHandles() throws Exception {
        Pipe p=new Pipe(join(frame(2,0x1007,0,words(2,0xFFFFFFFFL,0x80000001L)),frame(3,0x2001,0,new byte[0])));
        assertArrayEquals(new long[]{0xFFFFFFFFL,0x80000001L},new PtpClient(p).handles(PtpClient.STILL_STORE));
    }
    @Test public void downloadDiagnosticResetsAndIgnoresMetadataReads() throws Exception {
        byte[] first=new byte[70001],second=new byte[33];new Random(3).nextBytes(first);
        Pipe pipe=new Pipe(join(frame(2,0x1009,0,first),frame(3,0x2001,0,new byte[0]),
                frame(2,0x1007,1,words(0)),frame(3,0x2001,1,new byte[0]),
                frame(2,0x1009,2,second),frame(3,0x2001,2,new byte[0])));
        pipe.fragment=512;PtpClient camera=new PtpClient(pipe);
        ByteArrayOutputStream result=new ByteArrayOutputStream();camera.download(1,result,n -> {});
        assertArrayEquals(first,result.toByteArray());String diagnostic=camera.lastDownloadDiagnostic();
        assertTrue(diagnostic.contains("payloadBytes=70001 "));assertTrue(diagnostic.contains("sinkMs="));
        assertEquals(0,camera.handles(PtpClient.STILL_STORE).length);
        assertEquals(diagnostic,camera.lastDownloadDiagnostic());
        result.reset();camera.download(2,result,null);assertArrayEquals(second,result.toByteArray());
        assertTrue(camera.lastDownloadDiagnostic().contains("payloadBytes=33 "));
    }
    @Test public void mismatchTransactionRejected() throws Exception {
        Pipe p=new Pipe(frame(3,0x2001,99,new byte[0])); assertThrows(IOException.class,()->new PtpClient(p).command(0x1003));
    }
    @Test public void truncatedPayloadRejected() throws Exception {
        byte[] response=frame(2,0x1009,0,new byte[100]);Pipe p=new Pipe(Arrays.copyOf(response,29));
        assertThrows(EOFException.class,()->new PtpClient(p).download(1,new ByteArrayOutputStream(),null));
    }
    @Test public void busyIsRecoverable() throws Exception {
        Pipe p=new Pipe(join(frame(3,0x2019,0,new byte[0]),frame(2,0x1007,1,words(0)),frame(3,0x2001,1,new byte[0])));PtpClient c=new PtpClient(p);
        PtpException e=assertThrows(PtpException.class,()->c.handles(PtpClient.STILL_STORE));assertTrue(e.transientFailure());assertEquals(0,c.handles(PtpClient.STILL_STORE).length);
    }
    @Test public void noDataPhaseRejected() throws Exception {
        assertThrows(IOException.class,()->new PtpClient(new Pipe(frame(3,0x2001,0,new byte[0]))).data(0x1001));
    }
    @Test public void malformedArrayAndObjectRejected() throws Exception {
        assertThrows(IOException.class,()->PtpClient.array32(PtpClient.le(words(0xFFFFFFFFL))));
        assertThrows(IOException.class,()->PtpClient.ObjectInfo.parse(new byte[51]));
        assertThrows(IOException.class,()->PtpClient.ObjectInfo.parse(join(new byte[52],new byte[]{20,1})));
    }
    @Test public void objectInfoUnicodeAndSize() throws Exception {
        PtpClient.ObjectInfo o=PtpClient.ObjectInfo.parse(info(PtpClient.STILL_STORE,123456789,"测试.RAF"));
        assertEquals("测试.RAF",o.filename);assertEquals(123456789,o.size);assertEquals(PtpClient.STILL_STORE,o.storage);assertTrue(o.image());
    }
    @Test public void failedLengthNeverPublishesFile() throws Exception {
        File dir=Files.createTempDirectory("xt5-test").toFile();CaptureStore store=new CaptureStore(dir);
        Pipe p=new Pipe(join(frame(2,0x1009,0,new byte[10]),frame(3,0x2001,0,new byte[0])));
        assertThrows(IOException.class,()->store.receive(new PtpClient(p),1,PtpClient.ObjectInfo.parse(info(PtpClient.STILL_STORE,11,"DSCF0001.JPG")),null));
        assertEquals(0,Objects.requireNonNull(dir.list()).length);dir.delete();
    }
    @Test public void failingResponseNeverPublishesFile() throws Exception {
        File dir=Files.createTempDirectory("xt5-test").toFile();CaptureStore store=new CaptureStore(dir);
        Pipe p=new Pipe(join(frame(2,0x1009,0,new byte[10]),frame(3,0x2002,0,new byte[0])));
        assertThrows(IOException.class,()->store.receive(new PtpClient(p),1,PtpClient.ObjectInfo.parse(info(PtpClient.STILL_STORE,10,"DSCF0001.JPG")),null));
        assertEquals(0,Objects.requireNonNull(dir.list()).length);dir.delete();
    }
    @Test public void successfulAtomicSaveSanitizesFilenameAndPreservesOldPhoto() throws Exception {
        File dir=Files.createTempDirectory("xt5-test").toFile();CaptureStore store=new CaptureStore(dir);byte[] data={1,2,3,4};
        PtpClient.ObjectInfo i=PtpClient.ObjectInfo.parse(info(PtpClient.STILL_STORE,4,"../../DSCF0001.JPG"));
        for(int n=0;n<2;n++) {
            Pipe p=new Pipe(join(frame(2,0x1009,0,data),frame(3,0x2001,0,new byte[0])));
            File f=store.receive(new PtpClient(p),1,i,null);assertEquals(dir.getCanonicalPath(),f.getParentFile().getCanonicalPath());assertArrayEquals(data,Files.readAllBytes(f.toPath()));
        }
        assertEquals(2,store.recent().size());for(File f:store.recent())f.delete();dir.delete();
    }
    @Test public void rafPreviewBounds() throws Exception {
        File file=File.createTempFile("xt5-test",".raf");
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")) {
            f.setLength(110);f.write("FUJIFILMCCD-RAW ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));f.seek(84);f.writeInt(100);f.writeInt(4);f.seek(100);f.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});
        }
        assertArrayEquals(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217},CaptureStore.rafPreview(file));
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")){f.seek(88);f.writeInt(Integer.MAX_VALUE);}
        assertThrows(IOException.class,()->CaptureStore.rafPreview(file));file.delete();
    }
    @Test public void streamingPreviewArrivesBeforeDurableOriginalAndPreservesEveryByte() throws Exception {
        File dir=Files.createTempDirectory("xt5-quick").toFile();
        try {
            byte[] quick=JpegQuickPreviewTest.jpeg(640,480);
            int length=quick.length+2;
            byte[] payload=join(new byte[]{(byte)255,(byte)216,(byte)255,(byte)226,(byte)(length>>8),(byte)length},
                quick,new byte[]{(byte)255,(byte)218,0,2},new byte[150000]);
            Pipe pipe=new Pipe(join(frame(2,0x1009,0,payload),frame(3,0x2001,0,new byte[0])));
            CaptureStore store=new CaptureStore(dir);int[] previews={0};
            File saved=store.receive(new PtpClient(pipe),1,ObjectInfoFor(payload),null,bytes -> {
                assertArrayEquals(quick,bytes);assertTrue(pipe.pos<pipe.bytes.length-1000);
                assertTrue(store.recent().isEmpty());previews[0]++;
            });
            assertEquals(1,previews[0]);assertArrayEquals(payload,Files.readAllBytes(saved.toPath()));
        } finally {for(File f:Objects.requireNonNull(dir.listFiles()))f.delete();dir.delete();}
    }
    @Test public void optionalPreviewFailureDoesNotAbortVerifiedSave() throws Exception {
        File dir=Files.createTempDirectory("xt5-quick-error").toFile();
        try {
            byte[] quick=JpegQuickPreviewTest.jpeg(640,480);int length=quick.length+2;
            byte[] payload=join(new byte[]{(byte)255,(byte)216,(byte)255,(byte)226,(byte)(length>>8),(byte)length},
                quick,new byte[]{(byte)255,(byte)218,0,2},new byte[60000]);
            Pipe pipe=new Pipe(join(frame(2,0x1009,0,payload),frame(3,0x2001,0,new byte[0])));
            File saved=new CaptureStore(dir).receive(new PtpClient(pipe),1,ObjectInfoFor(payload),null,
                bytes -> {throw new IllegalStateException("preview closed");});
            assertArrayEquals(payload,Files.readAllBytes(saved.toPath()));
        } finally {for(File f:Objects.requireNonNull(dir.listFiles()))f.delete();dir.delete();}
    }
    private static PtpClient.ObjectInfo ObjectInfoFor(byte[] payload) throws IOException {
        return PtpClient.ObjectInfo.parse(info(PtpClient.STILL_STORE,payload.length,"DSCF0001.JPG"));
    }
}
