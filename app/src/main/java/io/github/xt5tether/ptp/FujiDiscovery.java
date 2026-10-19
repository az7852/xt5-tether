package io.github.xt5tether.ptp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** PCSS discovery: advertise UDP 51562 and accept the camera's invitation at TCP 51560. */
public final class FujiDiscovery implements Closeable {
    public interface NetworkSockets {
        Inet4Address localAddress();
        int prefixLength();
        Socket socket() throws IOException;
        default FujiWifiIo.ReceiveHints receiveHints(Socket socket) {return null;}
        DatagramSocket datagram() throws IOException;
    }
    public static final class Endpoint {
        public final String host, model; public final int port;
        public Endpoint(String host, int port, String model) { this.host = host; this.port = port; this.model = model; }
    }
    private final NetworkSockets network;
    private volatile ServerSocket server;
    private volatile DatagramSocket datagram;
    private volatile Socket invitation;
    private volatile boolean closed;
    public FujiDiscovery(NetworkSockets network) { this.network = network; }

    public Endpoint find(String requested, int timeoutMillis, Consumer<String> status) throws IOException {
        Inet4Address local = network.localAddress();
        ServerSocket listener = new ServerSocket(); server = listener;
        listener.setReuseAddress(true); listener.bind(new InetSocketAddress(local, 51560)); listener.setSoTimeout(400);
        DatagramSocket udp = network.datagram(); datagram = udp;
        udp.setBroadcast(true);
        if (!udp.isBound()) udp.bind(new InetSocketAddress(local, 0));
        byte[] message = advertisement(local.getHostAddress());
        Set<String> targets = new LinkedHashSet<>();
        if (!requested.isEmpty()) targets.add(requested);
        else { targets.add(broadcast(local.getHostAddress(), network.prefixLength())); targets.add("255.255.255.255"); }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L, advertised = 0;
        status.accept("正在发现 X‑T5 · 手机 " + local.getHostAddress() + " · 请启用无线联机拍摄固定");
        while (!closed && System.nanoTime() < deadline) {
            if (System.nanoTime() - advertised >= 1_000_000_000L) {
                for (String target : targets) udp.send(new DatagramPacket(message, message.length, InetAddress.getByName(target), 51562));
                advertised = System.nanoTime();
            }
            try (Socket client = listener.accept()) {
                invitation = client; client.setSoTimeout(1200);
                String peer = client.getInetAddress().getHostAddress();
                if (!requested.isEmpty() && !requested.equals(peer)) continue;
                String greeting = readInvitation(client.getInputStream());
                Endpoint endpoint;
                try { endpoint = parseInvitation(greeting, peer); }
                catch (IOException invalid) { continue; }
                client.getOutputStream().write("HTTP/1.1 200 OK\r\n\0".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return endpoint;
            } catch (SocketTimeoutException idle) { /* Keep discovery cancellable. */ }
            finally { invitation = null; }
        }
        throw new IOException(closed ? "已取消 Wi‑Fi 连接" : "未发现 X‑T5，请确认同一 Wi‑Fi、无线联机固定模式，并关闭路由器 AP/访客隔离；也可输入相机 IP");
    }
    public static byte[] advertisement(String local) {
        return ("DISCOVERY * HTTP/1.1\r\nHOST: " + local + "\r\nMX: 5\r\nSERVICE: PCSS/1.0\r\n").getBytes(StandardCharsets.US_ASCII);
    }
    public static String broadcast(String address, int prefix) throws IOException {
        if (prefix < 1 || prefix > 30) return "255.255.255.255";
        byte[] a = InetAddress.getByName(address).getAddress();
        if (a.length != 4) throw new IOException("无线联机需要 IPv4 网络");
        int ip = ((a[0]&255)<<24)|((a[1]&255)<<16)|((a[2]&255)<<8)|(a[3]&255);
        int mask = -1 << (32-prefix); int result = ip | ~mask;
        return ((result>>>24)&255)+"."+((result>>>16)&255)+"."+((result>>>8)&255)+"."+(result&255);
    }
    private static String readInvitation(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            while (bytes.size() < 4096) {
                int c = input.read(); if (c < 0 || c == 0) break; bytes.write(c);
                byte[] b = bytes.toByteArray(); int n = b.length;
                if (n >= 4 && b[n-4]==13 && b[n-3]==10 && b[n-2]==13 && b[n-1]==10) break;
            }
        } catch (SocketTimeoutException end) { if (bytes.size() == 0) throw end; }
        if (bytes.size() >= 4096) throw new IOException("相机发现信息过长");
        return bytes.toString(StandardCharsets.US_ASCII.name());
    }
    public static Endpoint parseInvitation(String text, String peer) throws IOException {
        Map<String,String> headers = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0,colon).trim().toUpperCase(Locale.ROOT), line.substring(colon+1).trim());
        }
        String address = headers.getOrDefault("DSC", peer);
        if (!address.equals(peer) || !headers.containsKey("DSCPORT")) throw new IOException("无效的相机发现地址");
        int port;
        try { port = Integer.parseInt(headers.get("DSCPORT")); } catch (NumberFormatException e) { throw new IOException("无效的相机端口",e); }
        if (port < 1024 || port > 65535) throw new IOException("无效的相机端口");
        return new Endpoint(peer, port, headers.getOrDefault("CAMERANAME", ""));
    }
    @Override public void close() {
        closed = true; DatagramSocket d = datagram; if (d != null) d.close();
        try { Socket i = invitation; if (i != null) i.close(); } catch (IOException ignored) { }
        try { ServerSocket s = server; if (s != null) s.close(); } catch (IOException ignored) { }
    }
}
