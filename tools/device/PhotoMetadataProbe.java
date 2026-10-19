package io.github.xt5tether;

import java.io.File;

/** Read-only EXIF/decoder check, in a separate process. Does not start or stop the application. */
public final class PhotoMetadataProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Provide one existing JPEG or RAF path");
        File file=new File(args[0]);if(!file.isFile())throw new IllegalArgumentException("Photo not found");
        try(PhotoSource source=PhotoSource.open(null,PhotoItem.local(file))) {
            System.out.println("Image="+source.geometry.uprightWidth+"x"+source.geometry.uprightHeight);
            for(String[] row:source.metadata.details())System.out.println(row[0]+"="+row[1]);
            System.out.println("PASS: existing original opened read-only through production PhotoSource");
        }
    }
}
