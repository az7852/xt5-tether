package io.github.xt5tether.ptp;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** PTP/USB framing independent of Android, with bounded metadata and streaming objects. */
public final class PtpClient {
    public static final long STILL_STORE = 0x10000001L;
    public static final int PRIORITY = 0xD207;
    private final PtpIo io;
    private final byte[] readBuffer = new byte[16384];
    private int position, available, transaction;
    private final String handshakeModel;
    private DownloadStats downloading, lastDownload;
    private static final class DownloadStats {
        long readNanos,writeNanos,callbackNanos,maxReadNanos,payloadBytes,reads;
        String diagnostic() {
            return "payloadBytes="+payloadBytes+" readMs="+readNanos/1_000_000+" sinkMs="+writeNanos/1_000_000+
                " callbackMs="+callbackNanos/1_000_000+" reads="+reads+" maxReadMs="+maxReadNanos/1_000_000;
        }
    }
    /** Time spent inside transport reads, output writes and progress callbacks; excludes queueing. */
    public String lastDownloadDiagnostic() {
        String transport=io.diagnostic();
        return (lastDownload==null?"unavailable":lastDownload.diagnostic())+(transport.isEmpty()?"":" "+transport);
    }
    public interface Progress { void update(long bytes); }
    public PtpClient(PtpIo io) { this(io, ""); }
    public PtpClient(PtpIo io, String handshakeModel) { this.io = io; this.handshakeModel = handshakeModel; }

    private void readFully(byte[] out, int offset, int count) throws IOException {
        while (count > 0) {
            if (position == available) {
                long began=downloading==null?0:System.nanoTime();
                try {available = io.read(readBuffer);}
                finally {if(downloading!=null) {
                    long elapsed=System.nanoTime()-began;downloading.readNanos+=elapsed;
                    downloading.maxReadNanos=Math.max(downloading.maxReadNanos,elapsed);downloading.reads++;
                }}
                position = 0;
                if (available <= 0) throw new EOFException("相机连接数据中断");
            }
            int n = Math.min(count, available - position);
            System.arraycopy(readBuffer, position, out, offset, n);
            position += n; offset += n; count -= n;
        }
    }
    public static ByteBuffer le(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] container(int type, int code, int tx, byte[] payload) {
        return ByteBuffer.allocate(12 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(12 + payload.length).putShort((short) type).putShort((short) code).putInt(tx).put(payload).array();
    }
    public void call(int operation, long[] params, byte[] send, OutputStream sink, Progress progress) throws IOException {
        int tx = transaction++;
        ByteBuffer p = ByteBuffer.allocate(params.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : params) p.putInt((int) value);
        io.write(container(1, operation, tx, p.array()));
        if (send != null) io.write(container(2, operation, tx, send));
        boolean gotData = false;
        while (true) {
            byte[] header = new byte[12]; readFully(header, 0, 12);
            ByteBuffer h = le(header);
            long length = Integer.toUnsignedLong(h.getInt());
            int type = Short.toUnsignedInt(h.getShort()), code = Short.toUnsignedInt(h.getShort());
            int responseTx = h.getInt();
            if (length < 12 || length > 1024L * 1024 * 1024 || responseTx != tx)
                throw new IOException("PTP 帧长度或事务编号异常；请重新连接相机");
            long remaining = length - 12;
            if (type == 3) {
                if (remaining > 20 || remaining % 4 != 0) throw new IOException("PTP 响应长度异常");
                byte[] response = new byte[(int) remaining]; readFully(response, 0, response.length);
                if (code != 0x2001) throw new PtpException(operation, code);
                if (sink != null && !gotData) throw new IOException("PTP 缺少数据阶段");
                return;
            }
            if (type != 2 || code != operation || sink == null || gotData || send != null)
                throw new IOException("PTP 数据阶段异常；请重新连接相机");
            gotData = true;
            byte[] chunk = new byte[16384]; long received = 0;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                readFully(chunk, 0, n);
                long began=downloading==null?0:System.nanoTime();
                try {sink.write(chunk, 0, n);}
                finally {if(downloading!=null)downloading.writeNanos+=System.nanoTime()-began;}
                if(downloading!=null)downloading.payloadBytes+=n;
                remaining -= n; received += n;
                if (progress != null) {
                    began=downloading==null?0:System.nanoTime();
                    try {progress.update(received);}
                    finally {if(downloading!=null)downloading.callbackNanos+=System.nanoTime()-began;}
                }
            }
        }
    }
    public byte[] data(int op, long... params) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(buffer) {
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (buffer.size() + len > 8 * 1024 * 1024) throw new IOException("PTP 元数据超过上限");
                out.write(b, off, len);
            }
        };
        call(op, params, null, bounded, null); return buffer.toByteArray();
    }
    public void command(int op, long... params) throws IOException { call(op, params, null, null, null); }
    public DeviceInfo open() throws IOException {
        // libfuji opens the session before querying the complete tether-mode capabilities.
        try { command(0x1002, 1); } catch (PtpException e) { if (e.code != 0x201E) throw e; }
        byte[] device = data(0x1001);
        if (device.length == 0 && handshakeModel.toUpperCase(Locale.ROOT).contains("X-T5")) {
            // Some Fuji WLAN implementations return an empty DeviceInfo. Only allow this
            // after the separate initialization identified an X-T5; priority is probed next.
            DeviceInfo d = new DeviceInfo(); d.model = handshakeModel; d.manufacturer = "FUJIFILM"; d.version = "未返回";
            d.operations = new HashSet<>(Arrays.asList(0x1007,0x1008,0x1009,0x100B));
            d.properties = new HashSet<>(Collections.singleton(PRIORITY)); return d;
        }
        return DeviceInfo.parse(device);
    }
    public void closeSession() throws IOException { command(0x1003); }
    public int property16(int code) throws IOException {
        byte[] b = data(0x1015, code);
        if (b.length != 2) throw new IOException("相机属性类型异常");
        return Short.toUnsignedInt(le(b).getShort());
    }
    public void setProperty16(int code, int value) throws IOException {
        call(0x1016, new long[]{code}, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)value).array(), null, null);
    }
    public long[] handles(long storage) throws IOException { return array32(le(data(0x1007, storage, 0, 0))); }
    public ObjectInfo info(long handle) throws IOException { return ObjectInfo.parse(data(0x1008, handle)); }
    public byte[] thumbnail(long handle) throws IOException { return data(0x100A, handle); }
    public void download(long handle, OutputStream sink, Progress progress) throws IOException {
        downloading=new DownloadStats();
        try {call(0x1009, new long[]{handle}, null, sink, progress);}
        finally {lastDownload=downloading;downloading=null;}
    }
    public byte[] partial(long handle,long offset,int count) throws IOException {
        if(offset<0 || offset>0xFFFFFFFFL || count<1 || count>256*1024) throw new IllegalArgumentException("Invalid partial object range");
        byte[] bytes=data(0x101B,handle,offset,count);
        if(bytes.length==0 || bytes.length>count) throw new IOException("相机返回的分段长度异常");
        return bytes;
    }
    public void release(long handle) throws IOException { command(0x100B, handle, 0); }

    static String string(ByteBuffer b) throws IOException {
        require(b, 1); int n = Byte.toUnsignedInt(b.get());
        if (n == 0) return "";
        require(b, n * 2); byte[] text = new byte[(n - 1) * 2]; b.get(text); b.getShort();
        return new String(text, StandardCharsets.UTF_16LE);
    }
    static void require(ByteBuffer b, int n) throws IOException {
        if (n < 0 || b.remaining() < n) throw new IOException("相机返回的数据不完整");
    }
    static Set<Integer> array16(ByteBuffer b) throws IOException {
        require(b, 4); long n = Integer.toUnsignedLong(b.getInt());
        if (n > b.remaining() / 2) throw new IOException("PTP 数组长度异常");
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < n; i++) result.add(Short.toUnsignedInt(b.getShort()));
        return result;
    }
    static long[] array32(ByteBuffer b) throws IOException {
        require(b, 4); long n = Integer.toUnsignedLong(b.getInt());
        if (n > b.remaining() / 4) throw new IOException("PTP 句柄数组长度异常");
        long[] result = new long[(int)n]; for (int i = 0; i < n; i++) result[i] = Integer.toUnsignedLong(b.getInt());
        return result;
    }
    public static final class DeviceInfo {
        public String manufacturer, model, version;
        public Set<Integer> operations, properties;
        public static DeviceInfo parse(byte[] data) throws IOException {
            ByteBuffer b = le(data); require(b, 8); b.position(8); string(b); require(b, 2); b.getShort();
            DeviceInfo d = new DeviceInfo(); d.operations = array16(b); array16(b); d.properties = array16(b); array16(b); array16(b);
            d.manufacturer = string(b); d.model = string(b); d.version = string(b); string(b); return d;
        }
    }
    public static final class ObjectInfo {
        public long storage, size; public int format; public String filename;
        public static ObjectInfo parse(byte[] data) throws IOException {
            ByteBuffer b = le(data); require(b, 52); ObjectInfo o = new ObjectInfo();
            o.storage = Integer.toUnsignedLong(b.getInt()); o.format = Short.toUnsignedInt(b.getShort());
            b.getShort(); o.size = Integer.toUnsignedLong(b.getInt()); b.position(52); o.filename = string(b);
            return o;
        }
        public boolean image() {
            String n = filename.toLowerCase(Locale.ROOT);
            return format == 0x3801 || format == 0xB103 || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".raf");
        }
    }
}
