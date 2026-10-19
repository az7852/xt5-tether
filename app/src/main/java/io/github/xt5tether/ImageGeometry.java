package io.github.xt5tether;

/** Source-pixel geometry, independent of Android so rotations and memory limits are testable. */
final class ImageGeometry {
    final int width, height, uprightWidth, uprightHeight;
    final float a, b, c, d, tx, ty;
    ImageGeometry(int width, int height, int orientation) {
        this.width=width; this.height=height;
        uprightWidth=orientation>=5 && orientation<=8?height:width;
        uprightHeight=orientation>=5 && orientation<=8?width:height;
        switch(orientation) {
            case 2: a=-1;b=0;c=0;d=1;tx=width;ty=0;break;
            case 3: a=-1;b=0;c=0;d=-1;tx=width;ty=height;break;
            case 4: a=1;b=0;c=0;d=-1;tx=0;ty=height;break;
            case 5: a=0;b=1;c=1;d=0;tx=0;ty=0;break;
            case 6: a=0;b=-1;c=1;d=0;tx=height;ty=0;break;
            case 7: a=0;b=-1;c=-1;d=0;tx=height;ty=width;break;
            case 8: a=0;b=1;c=-1;d=0;tx=0;ty=width;break;
            default:a=1;b=0;c=0;d=1;tx=0;ty=0;
        }
    }
    static final class Region {
        final int left, top, right, bottom, sample, outputWidth, outputHeight;
        Region(int left,int top,int right,int bottom,int sample) {
            this(left,top,right,bottom,sample,(right-left+sample-1)/sample,(bottom-top+sample-1)/sample);
        }
        Region(int left,int top,int right,int bottom,int sample,int outputWidth,int outputHeight) {
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.sample=sample;
            this.outputWidth=outputWidth;this.outputHeight=outputHeight;
        }
        long bytes() { return 4L*outputWidth*outputHeight; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Region)) return false;
            Region r=(Region)other;
            return left==r.left && top==r.top && right==r.right && bottom==r.bottom && sample==r.sample
                && outputWidth==r.outputWidth && outputHeight==r.outputHeight;
        }
        @Override public int hashCode() { return java.util.Objects.hash(left,top,right,bottom,sample,outputWidth,outputHeight); }
    }
    static Region region(int width,int height,float left,float top,float right,float bottom,float scale,long budget) {
        if(width<=0 || height<=0 || !(scale>0) || budget<4) throw new IllegalArgumentException("Invalid image viewport");
        int l=Math.max(0,Math.min(width-1,(int)Math.floor(left)));
        int t=Math.max(0,Math.min(height-1,(int)Math.floor(top)));
        int r=Math.max(l+1,Math.min(width,(int)Math.ceil(right)));
        int b=Math.max(t+1,Math.min(height,(int)Math.ceil(bottom)));
        double resolution=Math.min(1,scale);
        while(true) {
            int sample=1;
            while(sample<32768 && sample*2*resolution<=1) sample*=2;
            int alignedLeft=l/sample*sample, alignedTop=t/sample*sample;
            int alignedRight=Math.min(width,(r+sample-1)/sample*sample);
            int alignedBottom=Math.min(height,(b+sample-1)/sample*sample);
            Region result=new Region(alignedLeft,alignedTop,alignedRight,alignedBottom,sample,
                Math.max(1,(int)Math.ceil((alignedRight-alignedLeft)*resolution)),
                Math.max(1,(int)Math.ceil((alignedBottom-alignedTop)*resolution)));
            if(result.bytes()<=budget) return result;
            resolution*=Math.min(.95,Math.sqrt(budget/(double)result.bytes())*.99);
        }
    }
}
