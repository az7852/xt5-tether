package io.github.xt5tether;

import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone real-device loopback check. Does not attach to or close the camera App. */
public final class TcpReceiveProbe {
    public static void main(String[] args) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Future<?> peer=worker.submit(() -> {
                try(Socket s=server.accept()) {
                    s.setSoTimeout(3000);
                    for(int i=0;i<3;i++) {
                        s.getOutputStream().write(70+i);s.getOutputStream().flush();
                        if(s.getInputStream().read()!=90+i)throw new AssertionError("Echo mismatch");
                    }
                } catch(IOException e) {throw new UncheckedIOException(e);}
            });
            try(Socket socket=new Socket(server.getInetAddress(),server.getLocalPort())) {
                socket.setSoTimeout(3000);AtomicBoolean enabled=new AtomicBoolean(true);
                TcpReceiveHints hints=TcpReceiveHints.create(socket,enabled::get,System.out::println);
                if(hints==null)throw new AssertionError("Descriptor unavailable");
                try {
                    if(socket.getInputStream().read()!=70)throw new AssertionError("First byte changed");
                    hints.received();String on=hints.diagnostic();
                    if(!on.contains("quickAck=on ackHints=1"))throw new AssertionError(on);
                    socket.getOutputStream().write(90);socket.getOutputStream().flush();
                    enabled.set(false);
                    if(socket.getInputStream().read()!=71)throw new AssertionError("Second byte changed");
                    hints.received();String off=hints.diagnostic();
                    if(!off.contains("quickAck=off ackHints=1"))throw new AssertionError(off);
                    if(!off.contains("ackResets=1"))throw new AssertionError("ACK policy not reset: "+off);
                    hints.received();
                    if(!hints.diagnostic().contains("ackResets=1"))throw new AssertionError("Off mode keeps changing policy");
                    socket.getOutputStream().write(91);socket.getOutputStream().flush();
                    hints.close();hints.close();hints.received();
                    // Closing our duplicate must leave Java's original socket usable.
                    if(socket.getInputStream().read()!=72)throw new AssertionError("Original socket closed");
                    socket.getOutputStream().write(92);socket.getOutputStream().flush();
                    System.out.println("PASS: QUICKACK supported; live toggle respected; duplicate close idempotent; original socket and bytes preserved");
                } finally {hints.close();}
            }
            peer.get(4,TimeUnit.SECONDS);
        } finally {worker.shutdownNow();}
    }
}
