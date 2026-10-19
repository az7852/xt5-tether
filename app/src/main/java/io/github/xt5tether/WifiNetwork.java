package io.github.xt5tether;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import io.github.xt5tether.ptp.FujiDiscovery;
import io.github.xt5tether.ptp.FujiWifiIo;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Binds only the camera sockets to Wi-Fi; mobile data and ADB routing remain independent. */
final class WifiNetwork implements FujiDiscovery.NetworkSockets {
    private final Network network;
    private final Inet4Address local;
    private final int prefix;
    private final BooleanSupplier accelerated;
    private final Consumer<String> log;
    private WifiNetwork(Network network, Inet4Address local, int prefix,BooleanSupplier accelerated,Consumer<String> log) {
        this.network=network;this.local=local;this.prefix=prefix;this.accelerated=accelerated;this.log=log;
    }
    static WifiNetwork current(Context context,BooleanSupplier accelerated,Consumer<String> log) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            LinkProperties link = manager.getLinkProperties(network);
            // VPN capabilities can include the underlying WIFI transport as well as VPN.
            // A VPN's tun address is not the phone's LAN address, and binding may be forbidden.
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || link == null) continue;
            for (LinkAddress a : link.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress())
                return new WifiNetwork(network,(Inet4Address)a.getAddress(),a.getPrefixLength(),accelerated,log);
        }
        throw new IOException("手机未连接可用的 IPv4 Wi‑Fi，请先与相机连接同一路由器");
    }
    @Override public Inet4Address localAddress() { return local; }
    @Override public int prefixLength() { return prefix; }
    @Override public Socket socket() throws IOException { return network.getSocketFactory().createSocket(); }
    @Override public FujiWifiIo.ReceiveHints receiveHints(Socket socket) {return TcpReceiveHints.create(socket,accelerated,log);}
    @Override public DatagramSocket datagram() throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        try { network.bindSocket(socket); return socket; } catch (IOException|RuntimeException e) { socket.close(); throw e; }
    }
}
