package io.github.xt5tether;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageGeometryTest {
    @Test public void allExifOrientationsMapCornersAndRoundTrip() {
        // Expected source top-left, top-right, bottom-left after EXIF correction.
        float[][] expected={{0,0,600,0,0,400},{600,0,0,0,600,400},{600,400,0,400,600,0},
            {0,400,600,400,0,0},{0,0,0,600,400,0},{400,0,400,600,0,0},
            {400,600,400,0,0,600},{0,600,0,0,400,600}};
        for(int orientation=1;orientation<=8;orientation++) {
            ImageGeometry g=new ImageGeometry(600,400,orientation);
            float[][] points={{0,0},{600,0},{0,400},{600,400},{137,291}};
            for(int i=0;i<points.length;i++) {
                float x=points[i][0],y=points[i][1];
                float u=g.a*x+g.b*y+g.tx,v=g.c*x+g.d*y+g.ty;
                assertTrue(u>=0 && u<=g.uprightWidth);assertTrue(v>=0 && v<=g.uprightHeight);
                if(i<3) {assertEquals(expected[orientation-1][i*2],u,0);assertEquals(expected[orientation-1][i*2+1],v,0);}
                // The inverse of each EXIF linear transform is its transpose.
                assertEquals(x,g.a*(u-g.tx)+g.c*(v-g.ty),0);
                assertEquals(y,g.b*(u-g.tx)+g.d*(v-g.ty),0);
            }
        }
    }
    @Test public void tabletFitImprovesOldQuarterSizePreview() {
        ImageGeometry.Region r=ImageGeometry.region(5472,3648,0,0,5472,3648,.48f,40*1024*1024);
        assertEquals(2,r.sample);assertEquals(2736,(r.right-r.left)/r.sample);
        assertEquals((int)Math.ceil(5472*.48f),r.outputWidth);
        assertEquals((int)Math.ceil(3648*.48f),r.outputHeight);
        assertTrue(r.bytes()<=40*1024*1024);
    }
    @Test public void nativeZoomLoadsOriginalPixelsInsteadOfUpscalingPreview() {
        ImageGeometry.Region r=ImageGeometry.region(5472,3648,810,562,4010,2698,1,40*1024*1024);
        assertEquals(1,r.sample);assertEquals(3200,r.right-r.left);assertEquals(2136,r.bottom-r.top);
    }
    @Test public void budgetsHoldAcrossZoomLevelsAndFortyMegapixelImages() {
        for(int w:new int[]{5472,7728,40000}) for(float scale:new float[]{.02f,.25f,.48f,1,3,8}) {
            for(long budget:new long[]{2*1024*1024,40*1024*1024}) {
                ImageGeometry.Region r=ImageGeometry.region(w,4001,-40,-90,w+100,5000,scale,budget);
                assertTrue(r.bytes()<=budget);assertTrue(r.left>=0 && r.top>=0);
                assertTrue(r.right<=w && r.bottom<=4001);assertTrue(r.right>r.left && r.bottom>r.top);
                assertEquals(0,r.sample & (r.sample-1));
            }
        }
    }
    @Test public void fractionalAndEdgeViewportsStayCovered() {
        ImageGeometry.Region r=ImageGeometry.region(5473,3649,123.7f,321.4f,5469.5f,3648.9f,.2f,40*1024*1024);
        assertTrue(r.left<=123.7f && r.top<=321.4f);assertTrue(r.right>=5469.5f && r.bottom>=3648.9f);
        assertTrue(r.right<=5473 && r.bottom<=3649);assertEquals(4,r.sample);
    }
    @Test public void adaptsToPhysicalPixelsForPhoneTabletAndSplitWindows() {
        for(int[] viewport:new int[][]{{1200,2608},{3200,2136},{2136,3200},{640,900},{1920,1080}}) {
            for(int orientation:new int[]{1,6}) {
                ImageGeometry g=new ImageGeometry(7728,5152,orientation);
                float scale=Math.min(viewport[0]/(float)g.uprightWidth,viewport[1]/(float)g.uprightHeight);
                ImageGeometry.Region r=ImageGeometry.region(g.width,g.height,0,0,g.width,g.height,scale,40*1024*1024);
                assertEquals((int)Math.ceil(g.width*(double)scale),r.outputWidth);
                assertEquals((int)Math.ceil(g.height*(double)scale),r.outputHeight);
                assertTrue(r.bytes()<=40*1024*1024);
            }
        }
    }
    @Test public void fullscreenTabletKeepsScreenResolutionWithoutFullOriginalAllocation() {
        float scale=3200f/5472;
        ImageGeometry.Region r=ImageGeometry.region(5472,3648,0,0,5472,3648,scale,40*1024*1024);
        assertEquals(1,r.sample);assertEquals(3200,r.outputWidth);
        assertEquals(2134,r.outputHeight);assertTrue(r.bytes()<28*1024*1024);
    }
    @Test public void resizeChangesDetailRequestEvenWhenDecodeSampleStaysTheSame() {
        ImageGeometry.Region a=ImageGeometry.region(5472,3648,0,0,5472,3648,.45f,40*1024*1024);
        ImageGeometry.Region b=ImageGeometry.region(5472,3648,0,0,5472,3648,.49f,40*1024*1024);
        assertEquals(a.sample,b.sample);assertNotEquals(a,b);
        assertTrue(b.outputWidth>a.outputWidth);
    }
}
