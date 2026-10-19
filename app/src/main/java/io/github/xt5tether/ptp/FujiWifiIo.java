package io.github.xt5tether.ptp;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Fujifilm wireless tether: an 82-byte initialization, then USB-shaped PTP over TCP. */
public final class FujiWifiIo implements PtpIo {
    public interface ReceiveHints extends Closeable {
        void received();
        String diagnostic();
    }
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    public final String cameraName;
    private volatile ReceiveHints receiveHints;
    private boolean closed;
    public synchronized void receiveHints(ReceiveHints hints) throws IOException {
        if(closed) {hints.close();return;}
        if(receiveHints!=null)receiveHints.close();receiveHints=hints;
    }

    public FujiWifiIo(Socket socket, String clientName) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true); socket.setKeepAlive(true); socket.setSoTimeout(15000);
        input = new BufferedInputStream(socket.getInputStream(), 65536);
        output = socket.getOutputStream();
        byte[] packet;
        for (int attempt = 0; ; attempt++) {
            output.write(initialization(clientName)); output.flush();
            byte[] size = new byte[4]; readFully(input, size);
            long length = Integer.toUnsignedLong(PtpClient.le(size).getInt());
            if (length < 8 || length > 4096) throw new IOException("富士 Wi‑Fi 握手长度异常");
            packet = new byte[(int)length]; System.arraycopy(size, 0, packet, 0, 4);
            readFully(input, packet, 4, packet.length - 4);
            int type = PtpClient.le(packet).getInt(4);
            if (type == 5) {
                long reason = length >= 12 ? Integer.toUnsignedLong(PtpClient.le(packet).getInt(8)) : 0;
                // X-T5 4.31 first replies DeviceBusy, then accepts the identical request.
                if (reason == 0x2019 && attempt < 3) {
                    pause(600);
                    continue;
                }
                String detail = reason == 0x201D ? "初始化参数无效" : reason == 0x2019 ? "相机忙，请关闭其他联机软件后重试" : "请检查相机联机设置";
                throw new IOException(String.format(java.util.Locale.ROOT,"相机拒绝无线联机（0x%04X）：%s",reason,detail));
            }
            if (type != 2 || length < 30) throw new IOException("相机没有返回有效的富士无线联机握手");
            break;
        }
        int end = 28;
        while (end + 1 < packet.length && (packet[end] != 0 || packet[end + 1] != 0)) end += 2;
        if (end + 1 >= packet.length) throw new IOException("富士无线握手缺少相机名称结束符");
        cameraName = new String(packet, 28, end - 28, StandardCharsets.UTF_16LE).trim();
        pause(50);
    }
    public static byte[] initialization(String name) {
        byte[] packet = new byte[82];
        ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(82).putInt(1).putInt(0x8F53E4F2);
        // libfuji's compatibility words, verified against a physical X-T5 (firmware 4.31).
        // Random UUID bytes here produce InitFail 0x201D; these are not a host identifier.
        b.putInt(0x5d48a5ad).putInt(0x0b7fb287).putInt(0xd0ded5d3).putInt(0);
        // Fixed 26 UCS-2 characters plus terminator. Do not let long names change the packet size.
        String safe = name.replaceAll("[^a-zA-Z0-9 _.-]", "_");
        byte[] text = safe.substring(0, Math.min(26, safe.length())).getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(text, 0, packet, 28, text.length); return packet;
    }
    static void pause(long milliseconds) throws IOException {
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("已取消无线连接",e); }
    }
    static void readFully(InputStream in, byte[] buffer) throws IOException { readFully(in, buffer, 0, buffer.length); }
    static void readFully(InputStream in, byte[] buffer, int offset, int count) throws IOException {
        while (count > 0) {
            int n = in.read(buffer, offset, count);
            if (n < 0) throw new EOFException("相机 Wi‑Fi 连接中断");
            if (n == 0) continue;
            offset += n; count -= n;
        }
    }
    @Override public int read(byte[] bytes) throws IOException {
        int n=input.read(bytes);ReceiveHints hints=receiveHints;
        if(n>0 && hints!=null)hints.received();return n;
    }
    @Override public String diagnostic() {ReceiveHints hints=receiveHints;return hints==null?"":hints.diagnostic();}
    @Override public void write(byte[] bytes) throws IOException { output.write(bytes); output.flush(); }
    @Override public synchronized void close() throws IOException {
        closed=true;ReceiveHints hints=receiveHints;receiveHints=null;
        try {if(hints!=null)hints.close();}finally {socket.close();}
    }
}
