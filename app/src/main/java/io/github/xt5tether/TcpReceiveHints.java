package io.github.xt5tether;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import io.github.xt5tether.ptp.FujiWifiIo;
import java.io.IOException;
import java.net.Socket;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-camera Linux TCP receive hints, with a separately owned duplicate descriptor.
 * QUICKACK is temporary, so re-arm it after each received batch. NODELAY alone
 * only affects our outbound writes. Never change global network settings.
 */
final class TcpReceiveHints implements FujiWifiIo.ReceiveHints {
    // Linux UAPI include/uapi/linux/tcp.h; not exposed in Android's OsConstants.
    private static final int TCP_QUICKACK=12;
    private final ParcelFileDescriptor descriptor;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;
    private final int receiveBuffer;
    private boolean supported=true,closed;
    private boolean armed;
    private long calls,resets;

    static TcpReceiveHints create(Socket socket,BooleanSupplier enabled,Consumer<String> log) {
        try {return new TcpReceiveHints(socket,enabled,log);}
        catch(IOException|RuntimeException e) {log.accept("TCP receive optimization unavailable: "+e);return null;}
    }
    private TcpReceiveHints(Socket socket,BooleanSupplier enabled,Consumer<String> log) throws IOException {
        this.enabled=enabled;this.log=log;receiveBuffer=socket.getReceiveBufferSize();
        descriptor=ParcelFileDescriptor.fromSocket(socket);
        // Probe on the first enabled receive, using only Android's public API.
        log.accept("TCP receive optimization ready: "+diagnostic());
    }
    @Override public synchronized void received() {
        if(closed || !supported)return;
        boolean accelerate=enabled.getAsBoolean();
        if(!accelerate && !armed)return;
        try {
            Os.setsockoptInt(descriptor.getFileDescriptor(),OsConstants.IPPROTO_TCP,TCP_QUICKACK,accelerate?1:0);
            armed=accelerate;if(accelerate)calls++;else resets++;
        }
        catch(ErrnoException|RuntimeException e) {supported=false;log.accept("TCP_QUICKACK disabled after failure: "+e);}
    }
    @Override public synchronized String diagnostic() {
        return "quickAck="+(!supported?"unsupported":enabled.getAsBoolean()?"on":"off")+" ackHints="+calls+" ackResets="+resets+" rcvBuf="+receiveBuffer;
    }
    @Override public synchronized void close() throws IOException {
        if(closed)return;closed=true;descriptor.close();
    }
}
