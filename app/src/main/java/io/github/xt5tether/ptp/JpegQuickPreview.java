package io.github.xt5tether.ptp;

import java.util.Arrays;
import java.util.function.Consumer;

/** Reads complete embedded JPEGs from a bounded metadata prefix, never from unfinished image pixels. */
public final class JpegQuickPreview {
    private final byte[] prefix=new byte[256*1024];
    private final Consumer<byte[]> ready;
    private int size, position=2, orientation=1, bestPixels;
    private byte[] best;
    private boolean done;
    public JpegQuickPreview(Consumer<byte[]> ready) { this.ready=ready; }
    public void accept(byte[] bytes,int offset,int count) {
        if(done) return;
        int n=Math.min(count,prefix.length-size);System.arraycopy(bytes,offset,prefix,size,n);size+=n;
        if(size<2) return;
        if(u(0)!=255 || u(1)!=216) {done=true;return;}
        while(position+4<=size) {
            if(u(position)!=255) {finish();return;}
            int marker=u(position+1);
            if(marker==218 || marker==217) {finish();return;}
            int length=u(position+2)*256+u(position+3);
            if(length<2) {finish();return;}
            int end=position+2+length;
            if(end>size) break;
            int start=position+4;
            if(marker==225 && end-start>=14 && u(start)==69 && u(start+1)==120 && u(start+2)==105 && u(start+3)==102
                    && u(start+4)==0 && u(start+5)==0) readOrientation(start+6,end);
            // EXIF and FlashPix metadata can each contain a complete preview JPEG.
            if(marker==225 || marker==226) {
                for(int p=start;p+3<end;p++) if(u(p)==255 && u(p+1)==216) candidate(p,end);
            }
            position=end;
        }
        if(size==prefix.length) finish();
    }
    private void readOrientation(int tiff,int end) {
        boolean little=u(tiff)==73 && u(tiff+1)==73;
        if(!little && !(u(tiff)==77 && u(tiff+1)==77)) return;
        if(word(tiff+2,little)!=42) return;
        long offset=dword(tiff+4,little);
        if(offset<8 || offset>end-tiff-2) return;
        int ifd=tiff+(int)offset, count=word(ifd,little);
        for(int i=0;i<count && ifd+2L+(i+1)*12<=end;i++) {
            int p=ifd+2+i*12;
            if(word(p,little)==0x112 && word(p+2,little)==3 && dword(p+4,little)==1) {
                int value=word(p+8,little);if(value>=1 && value<=8) orientation=value;return;
            }
        }
    }
    private void candidate(int start,int end) {
        int p=start+2,pixels=0;
        while(p+4<=end) {
            if(u(p)!=255) return;
            int marker=u(p+1),length=u(p+2)*256+u(p+3);
            if(length<2 || p+2+length>end) return;
            if((marker==192 || marker==194) && length>=8) {
                int h=u(p+5)*256+u(p+6), w=u(p+7)*256+u(p+8);
                if(w<1 || h<1 || w>2048 || h>2048) return;
                pixels=w*h;
            }
            p+=2+length;
            if(marker==218) {
                // Entropy-coded data escapes FF as FF00, so FFD9 marks a complete JPEG.
                for(int q=p;q+1<end;q++) if(u(q)==255 && u(q+1)==217) {
                    if(pixels>bestPixels) {best=Arrays.copyOfRange(prefix,start,q+2);bestPixels=pixels;}
                    return;
                }
                return;
            }
        }
    }
    private void finish() {
        if(done) return;done=true;
        if(best==null) return;
        if(orientation!=1) {
            // Add only orientation metadata; JPEG image data is copied without re-encoding.
            byte[] exif={(byte)255,(byte)225,0,34,69,120,105,102,0,0,73,73,42,0,8,0,0,0,
                1,0,18,1,3,0,1,0,0,0,(byte)orientation,0,0,0,0,0,0,0};
            byte[] oriented=new byte[best.length+exif.length];oriented[0]=(byte)255;oriented[1]=(byte)216;
            System.arraycopy(exif,0,oriented,2,exif.length);System.arraycopy(best,2,oriented,2+exif.length,best.length-2);
            best=oriented;
        }
        ready.accept(best);best=null;
    }
    private int u(int p) { return prefix[p]&255; }
    private int word(int p,boolean little) { return little?u(p)|u(p+1)<<8:u(p)<<8|u(p+1); }
    private long dword(int p,boolean little) {
        return little?(long)word(p,little)|(long)word(p+2,little)<<16:(long)word(p,little)<<16|word(p+2,little);
    }
}
