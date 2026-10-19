package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public class JpegQuickPreviewTest {
    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:parts)out.write(p);return out.toByteArray();
    }
    private static byte[] segment(int marker,byte[] bytes) throws IOException {
        int length=bytes.length+2;return join(new byte[]{(byte)255,(byte)marker,(byte)(length>>8),(byte)length},bytes);
    }
    static byte[] jpeg(int width,int height) throws IOException {
        return join(new byte[]{(byte)255,(byte)216},segment(192,new byte[]{8,(byte)(height>>8),(byte)height,(byte)(width>>8),(byte)width,1}),
            segment(218,new byte[]{1,1,0,0,63,0}),new byte[]{12,45,(byte)255,0,3,(byte)255,(byte)217});
    }
    private static byte[] parent(byte[]... metadata) throws IOException {
        return join(new byte[]{(byte)255,(byte)216},join(metadata),segment(218,new byte[]{1,1,0,0,63,0}),new byte[16000]);
    }
    @Test public void fragmentedHeaderDeliversBestPreviewBeforeOriginalFinishesOnce() throws Exception {
        byte[] small=jpeg(160,120),large=jpeg(640,480);
        byte[] bytes=parent(segment(225,small),segment(226,large));
        List<byte[]> previews=new ArrayList<>();JpegQuickPreview reader=new JpegQuickPreview(previews::add);
        for(int i=0;i<bytes.length;i++) {
            reader.accept(bytes,i,1);
            if(i==bytes.length-1000) assertEquals(1,previews.size());
        }
        assertEquals(1,previews.size());assertArrayEquals(large,previews.get(0));
    }
    @Test public void copiesParentExifOrientationInBothByteOrders() throws Exception {
        for(ByteOrder order:new ByteOrder[]{ByteOrder.LITTLE_ENDIAN,ByteOrder.BIG_ENDIAN}) {
            ByteBuffer exif=ByteBuffer.allocate(32).order(order);
            exif.put(new byte[]{69,120,105,102,0,0});
            exif.putShort((short)(order==ByteOrder.LITTLE_ENDIAN?0x4949:0x4d4d)).putShort((short)42).putInt(8)
                .putShort((short)1).putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0);
            List<byte[]> previews=new ArrayList<>();JpegQuickPreview reader=new JpegQuickPreview(previews::add);
            byte[] original=parent(segment(225,exif.array()),segment(226,jpeg(640,480)));
            reader.accept(original,0,original.length);assertEquals(1,previews.size());
            byte[] result=previews.get(0);
            assertEquals(6,result[30]); // Inserted TIFF orientation value.
            assertArrayEquals(Arrays.copyOfRange(jpeg(640,480),2,jpeg(640,480).length),Arrays.copyOfRange(result,38,result.length));
        }
    }
    @Test public void truncatedAndNonJpegHeadersNeverPublishPartialPictures() throws Exception {
        List<byte[]> previews=new ArrayList<>();
        byte[] truncated=parent(segment(226,Arrays.copyOf(jpeg(640,480),20)));
        new JpegQuickPreview(previews::add).accept(truncated,0,truncated.length);
        byte[] raw=new byte[1024];new JpegQuickPreview(previews::add).accept(raw,0,raw.length);
        assertTrue(previews.isEmpty());
    }
    @Test public void rejectsHugeEmbeddedDimensionsAndMalformedExifOffsets() throws Exception {
        List<byte[]> previews=new ArrayList<>();
        byte[] malformed={69,120,105,102,0,0,73,73,42,0,(byte)255,(byte)255,(byte)255,127};
        byte[] bytes=parent(segment(225,malformed),segment(226,jpeg(30000,30000)));
        new JpegQuickPreview(previews::add).accept(bytes,0,bytes.length);assertTrue(previews.isEmpty());
    }
    @Test public void boundedPrefixStopsOnUnendingMetadata() {
        List<byte[]> previews=new ArrayList<>();JpegQuickPreview reader=new JpegQuickPreview(previews::add);
        byte[] bytes=new byte[1024*1024];bytes[0]=(byte)255;bytes[1]=(byte)216;
        for(int p=2;p+65537<bytes.length;p+=65537) {
            bytes[p]=(byte)255;bytes[p+1]=(byte)226;bytes[p+2]=(byte)255;bytes[p+3]=(byte)255;
        }
        reader.accept(bytes,0,bytes.length);reader.accept(bytes,0,bytes.length);assertTrue(previews.isEmpty());
    }
}
