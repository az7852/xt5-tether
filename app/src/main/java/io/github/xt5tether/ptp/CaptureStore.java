package io.github.xt5tether.ptp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Originals are published only after length verification, fsync and atomic rename. */
public final class CaptureStore {
    private final File directory;
    public CaptureStore(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建照片目录");
    }
    public File directory() { return directory; }
    public Incoming begin(PtpClient.ObjectInfo info,Consumer<byte[]> preview) throws IOException {
        return new Incoming(info,preview);
    }
    /** One bounded, private partial original. It never appears in recent() before finish(). */
    public final class Incoming implements Closeable {
        private final File partial,finished;
        private final FileOutputStream file;
        private final BufferedOutputStream out;
        private final JpegQuickPreview preview;
        private final long expected;
        private long received;
        private boolean closed;
        private Incoming(PtpClient.ObjectInfo info,Consumer<byte[]> ready) throws IOException {
            if(info.size<=0 || info.size>=1024L*1024*1024) throw new IOException("相机文件大小异常");
            if(directory.getUsableSpace()<info.size+32L*1024*1024) throw new IOException("手机存储空间不足，照片仍保留在相机缓冲区");
            String name=info.filename.replaceAll("[^a-zA-Z0-9._-]","_");if(name.isEmpty())name="capture.bin";
            expected=info.size;
            partial=File.createTempFile("incoming-",".part",directory);
            finished=new File(directory,System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+"_"+name);
            try {file=new FileOutputStream(partial);out=new BufferedOutputStream(file,256*1024);}
            catch(IOException e) {if(!partial.delete())partial.deleteOnExit();throw e;}
            String lower=name.toLowerCase(Locale.ROOT);
            preview=ready!=null && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))?new JpegQuickPreview(ready):null;
        }
        public long received() {return received;}
        public void append(byte[] bytes) throws IOException {
            if(closed || received+bytes.length>expected) throw new IOException("分段原图大小不匹配");
            out.write(bytes);received+=bytes.length;
            if(preview!=null) try {preview.accept(bytes,0,bytes.length);} catch(RuntimeException ignored) { /* Original has priority over optional preview errors. */ }
        }
        public File finish() throws IOException {
            if(closed || received!=expected) throw new IOException("原图尚未完整接收，保留相机原文件");
            out.flush();
            if(file.getChannel().size()!=expected) throw new IOException("文件长度校验失败，保留相机原文件");
            file.getFD().sync();out.close();closed=true;
            if(!partial.renameTo(finished)) throw new IOException("照片落盘失败，保留相机原文件");
            return finished;
        }
        @Override public void close() throws IOException {
            try {if(!closed) {closed=true;out.close();}}
            finally {if(partial.exists() && !partial.delete())partial.deleteOnExit();}
        }
    }
    // Conservative free-space check keeps this protocol/storage core runnable on a desktop JVM.
    @SuppressWarnings("UsableSpace")
    public File receive(PtpClient client, long handle, PtpClient.ObjectInfo info, PtpClient.Progress progress) throws IOException {
        return receive(client,handle,info,progress,null);
    }
    @SuppressWarnings("UsableSpace")
    public File receive(PtpClient client, long handle, PtpClient.ObjectInfo info, PtpClient.Progress progress,Consumer<byte[]> earlyPreview) throws IOException {
        if (info.size <= 0 || info.size >= 1024L * 1024 * 1024) throw new IOException("相机文件大小异常");
        if (directory.getUsableSpace() < info.size + 32L * 1024 * 1024) throw new IOException("手机存储空间不足，照片仍保留在相机缓冲区");
        String name = info.filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isEmpty()) name = "capture.bin";
        File partial = File.createTempFile("incoming-", ".part", directory);
        File finished = new File(directory, System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + name);
        try {
            try (FileOutputStream out = new FileOutputStream(partial)) {
                BufferedOutputStream buffered=new BufferedOutputStream(out,256*1024);
                JpegQuickPreview preview=earlyPreview!=null && (name.toLowerCase(Locale.ROOT).endsWith(".jpg") || name.toLowerCase(Locale.ROOT).endsWith(".jpeg"))?new JpegQuickPreview(earlyPreview):null;
                OutputStream sink=new OutputStream() {
                    @Override public void write(int value) throws IOException {write(new byte[]{(byte)value},0,1);}
                    @Override public void write(byte[] bytes,int offset,int count) throws IOException {
                        buffered.write(bytes,offset,count);
                        if(preview!=null) {
                            try {preview.accept(bytes,offset,count);}
                            catch(RuntimeException ignored) { /* Optional preview must not abort original saving. */ }
                        }
                    }
                };
                client.download(handle, sink, progress);
                buffered.flush();
                if (out.getChannel().size() != info.size) throw new IOException("文件长度校验失败，保留相机原文件，请重新连接");
                out.getFD().sync();
            }
            if (!partial.renameTo(finished)) throw new IOException("照片落盘失败，保留相机原文件");
            return finished;
        } finally {
            if (partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }
    public List<File> recent() {
        File[] files = directory.listFiles(f -> {
            String name = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".raf"));
        });
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return new ArrayList<>(Arrays.asList(files));
    }
    public static String displayName(File file) {
        return file.getName().replaceFirst("^\\d+_[0-9a-f]{8}_", "");
    }
    /** RAF header stores the embedded camera-rendered JPEG range as big-endian uint32s. */
    public static byte[] rafPreview(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[16]; raf.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).startsWith("FUJIFILMCCD-RAW")) throw new IOException("无效 RAF 文件");
            raf.seek(84); long offset = Integer.toUnsignedLong(raf.readInt()), size = Integer.toUnsignedLong(raf.readInt());
            if (offset < 92 || size < 4 || size > 32 * 1024 * 1024 || offset + size > raf.length()) throw new IOException("RAF 没有可用的内嵌 JPEG");
            raf.seek(offset); byte[] jpeg = new byte[(int)size]; raf.readFully(jpeg);
            if ((jpeg[0] & 255) != 0xFF || (jpeg[1] & 255) != 0xD8) throw new IOException("RAF 预览不是 JPEG");
            return jpeg;
        }
    }
}
