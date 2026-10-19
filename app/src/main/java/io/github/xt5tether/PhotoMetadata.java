package io.github.xt5tether;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display values from EXIF; missing or invalid values never masquerade as camera settings. */
final class PhotoMetadata {
    static final PhotoMetadata EMPTY=new PhotoMetadata(Double.NaN,Double.NaN,0,Double.NaN,Double.NaN,null,null,null,-1);
    private static final String MISSING="—";
    private final double aperture,seconds,focal,bias;
    private final long iso;
    private final String model,lens,taken;
    private final int whiteBalance,equivalent,metering,program,exposureMode,flash,lightSource,colorSpace;
    private final String make;
    PhotoMetadata(double aperture,double seconds,long iso,double focal,double bias,String model,String lens,String taken,int whiteBalance) {
        this(aperture,seconds,iso,focal,bias,model,lens,taken,whiteBalance,0,-1,-1,-1,-1,-1,-1,null);
    }
    PhotoMetadata(double aperture,double seconds,long iso,double focal,double bias,String model,String lens,String taken,int whiteBalance,
                  int equivalent,int metering,int program,int exposureMode,int flash,int lightSource,int colorSpace,String make) {
        this.aperture=aperture;this.seconds=seconds;this.iso=iso;this.focal=focal;this.bias=bias;
        this.model=clean(model);this.lens=clean(lens);this.taken=clean(taken);this.whiteBalance=whiteBalance;
        this.equivalent=equivalent;this.metering=metering;this.program=program;this.exposureMode=exposureMode;this.flash=flash;
        this.lightSource=lightSource;this.colorSpace=colorSpace;this.make=clean(make);
    }
    String aperture(){return positive(aperture,256)?"f/"+number(aperture,1):MISSING;}
    String shutter(){
        if(!positive(seconds,86400) || seconds<1e-9)return MISSING;
        double reciprocal=1/seconds;
        if(seconds<1 && reciprocal<=1000000000) {
            long denominator=Math.round(reciprocal);
            if(denominator>=2 && Math.abs(reciprocal-denominator)/reciprocal<=0.015)return "1/"+denominator+" s";
        }
        return number(seconds,6)+" s";
    }
    String iso(){return iso>0 && iso<=1000000000?Long.toString(iso):MISSING;}
    String focal(){return positive(focal,10000)?number(focal,1)+" mm":MISSING;}
    String compensation(){return Double.isFinite(bias) && Math.abs(bias)<=30?(bias>0?"+":"")+number(bias,1)+" EV":MISSING;}
    String equivalent(){return equivalent>0 && equivalent<=10000?equivalent+" mm":MISSING;}
    String metering(){return switch(metering){case -1 -> MISSING;case 0 -> "未注明";case 1 -> "平均";case 2 -> "中央重点";case 3 -> "点测光";case 4 -> "多点";case 5 -> "多区";case 6 -> "局部";default -> "其他";};}
    String program(){return switch(program){case -1 -> MISSING;case 0 -> "未注明";case 1 -> "手动";case 2 -> "程序自动";case 3 -> "光圈优先";case 4 -> "快门优先";case 5 -> "创意程序";case 6 -> "运动程序";case 7 -> "人像";case 8 -> "风景";default -> "其他";};}
    private String mode(){return switch(exposureMode){case -1 -> MISSING;case 0 -> "自动";case 1 -> "手动";case 2 -> "自动包围";default -> "其他";};}
    String flash(){return flash<0?MISSING:(flash&1)==0?"未闪光":(flash&64)!=0?"已触发 · 防红眼":"已触发";}
    private String light(){return switch(lightSource){case -1 -> MISSING;case 0 -> "未注明";case 1 -> "日光";case 2 -> "荧光灯";case 3 -> "钨丝灯";case 4 -> "闪光灯";case 9 -> "晴天";case 10 -> "阴天";case 11 -> "阴影";case 12 -> "日光荧光灯";case 13 -> "昼白色荧光灯";case 14 -> "冷白色荧光灯";case 15 -> "白色荧光灯";case 17 -> "标准光 A";case 18 -> "标准光 B";case 19 -> "标准光 C";case 20 -> "D55";case 21 -> "D65";case 22 -> "D75";case 23 -> "D50";case 24 -> "ISO 影室灯";default -> "其他";};}
    String colorSpace(){return colorSpace<0?MISSING:colorSpace==1?"sRGB":colorSpace==65535?"未标定":"代码 "+colorSpace;}
    String[][] details(){return new String[][]{{"相机",model},{"镜头",lens},{"拍摄时间",taken},{"光圈",aperture()},
            {"快门",shutter()},{"感光度",iso().equals(MISSING)?MISSING:"ISO "+iso()},{"焦距",focal()},{"曝光补偿",compensation()},
            {"白平衡",whiteBalance==0?"自动":whiteBalance==1?"手动":MISSING},{"等效焦距",equivalent()},{"测光模式",metering()},{"曝光程序",program()},
            {"曝光模式",mode()},{"闪光灯",flash()},{"光源",light()},{"色彩空间",colorSpace()},{"相机厂商",make}};}
    private static boolean positive(double value,double maximum){return Double.isFinite(value) && value>0 && value<=maximum;}
    private static String number(double value,int places){return BigDecimal.valueOf(value).setScale(places,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private static String clean(String value){
        if(value==null)return MISSING;
        String text=value.replaceAll("[\\p{Cntrl}]"," ").trim();
        return text.isEmpty()?MISSING:text.substring(0,Math.min(160,text.length()));
    }
}
