package io.github.xt5tether;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoMetadataTest {
    private PhotoMetadata values(double aperture,double exposure,long iso,double focal,double bias){return new PhotoMetadata(aperture,exposure,iso,focal,bias,"X-T5","XF35mmF1.4 R","2026:10:19 19:30:00",0);}
    @Test public void typicalCameraValuesAreReadable(){
        PhotoMetadata p=values(2.8,0.008,640,35,2.0/3);
        assertEquals("f/2.8",p.aperture());assertEquals("1/125 s",p.shutter());assertEquals("640",p.iso());assertEquals("35 mm",p.focal());assertEquals("+0.7 EV",p.compensation());
    }
    @Test public void longAndNonReciprocalExposuresAreNotRoundedToWrongShutters(){
        assertEquals("30 s",values(8,30,100,16,0).shutter());assertEquals("1/2 s",values(8,0.5,100,16,0).shutter());
        assertEquals("0.8 s",values(8,0.8,100,16,0).shutter());assertEquals("1/3 s",values(8,0.333,100,16,0).shutter());assertEquals("0.4 s",values(8,0.4,100,16,0).shutter());
        assertEquals("1/8000 s",values(8,1.0/8000,100,16,0).shutter());
    }
    @Test public void missingAndInvalidNumbersAreExplicit(){
        PhotoMetadata p=values(Double.NaN,Double.POSITIVE_INFINITY,-1,0,Double.NaN);
        assertEquals("—",p.aperture());assertEquals("—",p.shutter());assertEquals("—",p.iso());assertEquals("—",p.focal());assertEquals("—",p.compensation());
        assertEquals("—",values(-2,-1,0,-35,31).compensation());
    }
    @Test public void precisionAndZeroCompensationRemainStable(){
        PhotoMetadata p=values(1.4,1,12800,23.4,0);
        assertEquals("f/1.4",p.aperture());assertEquals("1 s",p.shutter());assertEquals("23.4 mm",p.focal());assertEquals("0 EV",p.compensation());
        assertEquals("-0.3 EV",values(4,1,100,35,-1.0/3).compensation());
    }
    @Test public void detailsKeepActualTextAndHandleMissingFields(){
        PhotoMetadata p=new PhotoMetadata(4,0.01,200,50,0,"X-T5\n",null,"2026:10:19 19:30:00",1);
        String[][] rows=p.details();assertEquals("X-T5",rows[0][1]);assertEquals("—",rows[1][1]);assertEquals("2026:10:19 19:30:00",rows[2][1]);assertEquals("手动",rows[8][1]);
        assertEquals("—",PhotoMetadata.EMPTY.details()[8][1]);
    }
    @Test public void additionalCaptureModesAndEquivalentFocalLengthAreMapped(){
        PhotoMetadata p=extended(41,5,3,0,0,0,1);
        assertEquals("41 mm",p.equivalent());assertEquals("多区",p.metering());assertEquals("光圈优先",p.program());assertEquals("sRGB",p.colorSpace());
        assertEquals("自动",p.details()[12][1]);assertEquals("FUJIFILM",p.details()[16][1]);
    }
    @Test public void missingModeTagsDoNotBecomeAutomaticOrSrgb(){
        PhotoMetadata p=extended(0,-1,-1,-1,-1,-1,-1);
        assertEquals("—",p.equivalent());assertEquals("—",p.metering());assertEquals("—",p.program());assertEquals("—",p.flash());assertEquals("—",p.colorSpace());
    }
    @Test public void flashUsesFiredBitRatherThanAnyNonzeroFlag(){
        assertEquals("未闪光",extended(0,0,0,0,16,0,1).flash());assertEquals("已触发",extended(0,0,0,0,1,0,1).flash());
        assertEquals("已触发 · 防红眼",extended(0,0,0,0,65,0,1).flash());
    }
    @Test public void uncalibratedOrUnknownColorSpaceIsNotInvented(){
        assertEquals("未标定",extended(0,0,0,0,0,0,65535).colorSpace());assertEquals("代码 2",extended(0,0,0,0,0,0,2).colorSpace());
    }
    private PhotoMetadata extended(int equivalent,int metering,int program,int mode,int flash,int light,int color){
        return new PhotoMetadata(1.2,0.025,160,27,-1,"X-T5","AF 27/1.2 XF","2026:10:19 01:56:52",0,equivalent,metering,program,mode,flash,light,color,"FUJIFILM");
    }
}
