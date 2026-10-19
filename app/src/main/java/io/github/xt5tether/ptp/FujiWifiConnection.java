package io.github.xt5tether.ptp;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/** Owns discovery and the command socket, including cancellation before PTP starts. */
public final class FujiWifiConnection implements Closeable {
    private final FujiDiscovery.NetworkSockets network;
    private volatile Socket socket;
    private FujiWifiIo stream;
    private volatile FujiDiscovery discovery;
    private volatile boolean closed;
    public FujiDiscovery.Endpoint endpoint;
    public FujiWifiConnection(FujiDiscovery.NetworkSockets network) { this.network = network; }
    public FujiWifiIo connect(String requested, Consumer<String> status) throws IOException {
        if (closed) throw new IOException("已取消 Wi‑Fi 连接");
        if (!requested.isEmpty()) {
            status.accept("正在连接相机 " + requested + "…");
            try { connectSocket(requested,55740,3000); }
            catch (IOException unavailable) {
                closeSocket();
                if (closed) throw new IOException("已取消 Wi‑Fi 连接",unavailable);
            }
        }
        if (socket == null) {
            FujiDiscovery search = new FujiDiscovery(network); discovery = search;
            if (closed) search.close();
            try { endpoint = search.find(requested, requested.isEmpty()?20000:10000,status); }
            finally { search.close(); discovery = null; }
            if (closed) throw new IOException("已取消 Wi‑Fi 连接");
            status.accept("已发现相机 " + endpoint.host + ":" + endpoint.port + " · 正在握手…");
            // Match libfuji's settling interval after the PCSS invitation.
            FujiWifiIo.pause(1200);
            connectSocket(endpoint.host, endpoint.port,5000);
        } else endpoint = new FujiDiscovery.Endpoint(requested,55740,"");
        if (closed) { closeSocket(); throw new IOException("已取消 Wi‑Fi 连接"); }
        status.accept("正在建立富士无线联机会话 · 如有提示请在相机确认");
        // The handshake name can be user-configured. TetherEngine verifies DeviceInfo's model
        // before accessing captures; the empty-DeviceInfo fallback requires an X-T5 name.
        Socket connected=socket;if(connected==null)throw new IOException("已取消 Wi‑Fi 连接");
        FujiWifiIo ready=new FujiWifiIo(connected,"XT5 Tether Android");
        synchronized(this) {
            if(closed) {ready.close();throw new IOException("已取消 Wi‑Fi 连接");}
            stream=ready;
            FujiWifiIo.ReceiveHints hints=network.receiveHints(connected);
            if(hints!=null)ready.receiveHints(hints);
        }
        return ready;
    }
    private void connectSocket(String host, int port, int timeout) throws IOException {
        if (closed) throw new IOException("已取消 Wi‑Fi 连接");
        Socket s = network.socket(); socket = s;
        if (closed) { closeSocket(); throw new IOException("已取消 Wi‑Fi 连接"); }
        s.connect(new InetSocketAddress(host,port), timeout);
    }
    private synchronized void closeSocket() {
        FujiWifiIo io=stream;stream=null;if(io!=null)try {io.close();}catch(IOException ignored) { }
        Socket s = socket; socket = null; if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        closed = true; FujiDiscovery d = discovery; if (d != null) d.close(); closeSocket();
    }
}
