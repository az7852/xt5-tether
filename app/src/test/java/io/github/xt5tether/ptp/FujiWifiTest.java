package io.github.xt5tether.ptp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class FujiWifiTest {
    @Test(timeout=5000) public void receiveHintsPreserveFramesAndAreClosedWithTransport() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> peer=executor.submit(() -> {
                try(Socket remote=server.accept()) {
                    remote.setSoTimeout(3000);byte[] request=new byte[82];FujiWifiIo.readFully(remote.getInputStream(),request);
                    remote.getOutputStream().write(ack("X-T5"));remote.getOutputStream().flush();
                    byte[] command=new byte[12];FujiWifiIo.readFully(remote.getInputStream(),command);
                    remote.getOutputStream().write(PtpClientTest.frame(3,0x2001,0,new byte[0]));remote.getOutputStream().flush();
                } catch(IOException e) {throw new UncheckedIOException(e);}
            });
            java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger(),closes=new java.util.concurrent.atomic.AtomicInteger();
            FujiWifiIo.ReceiveHints hints=new FujiWifiIo.ReceiveHints() {
                public void received(){reads.incrementAndGet();}public String diagnostic(){return "test receive hints";}
                public void close(){closes.incrementAndGet();}
            };
            try(Socket socket=new Socket(server.getInetAddress(),server.getLocalPort())) {
                FujiWifiIo wifi=new FujiWifiIo(socket,"test");wifi.receiveHints(hints);
                try {new PtpClient(wifi).closeSession();assertTrue(reads.get()>0);assertEquals("test receive hints",wifi.diagnostic());}
                finally {wifi.close();wifi.close();}
                assertEquals(1,closes.get());
            } finally {try{peer.get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}}
        }
    }
    private static byte[] ack(String name) {
        byte[] text=(name+"\0").getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(28+text.length).order(ByteOrder.LITTLE_ENDIAN).putInt(28+text.length).putInt(2).putInt(1).put(new byte[16]).put(text).array();
    }
    @Test public void fixedInitializationMatchesVerifiedXt5Handshake() {
        byte[] request=FujiWifiIo.initialization("XT5 Tether Android a very long computer name");
        ByteBuffer b=PtpClient.le(request);
        assertEquals(82,request.length);assertEquals(82,b.getInt());assertEquals(1,b.getInt());assertEquals(0x8F53E4F2,b.getInt());
        assertArrayEquals(new byte[]{(byte)0xad,(byte)0xa5,0x48,0x5d,(byte)0x87,(byte)0xb2,0x7f,0x0b,(byte)0xd3,(byte)0xd5,(byte)0xde,(byte)0xd0,0,0,0,0},Arrays.copyOfRange(request,12,28));
        assertEquals(0,request[80]);assertEquals(0,request[81]);
    }
    @Test public void utf16ComputerNameIsTerminatedWithin82Bytes() {
        byte[] request=FujiWifiIo.initialization("手机 test");
        String name=new String(request,28,54,StandardCharsets.UTF_16LE);
        assertTrue(name.startsWith("__ test\0"));
    }
    @Test(timeout=5000) public void fragmentedAckAndCoalescedPtpArePreserved() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> peer=executor.submit(()->{
                try(Socket remote=server.accept()) {
                    byte[] request=new byte[82];FujiWifiIo.readFully(remote.getInputStream(),request);
                    assertArrayEquals(FujiWifiIo.initialization("test"),request);
                    byte[] response=PtpClientTest.join(ack("FUJIFILM X-T5"),PtpClientTest.frame(3,0x2001,0,new byte[0]));
                    OutputStream out=remote.getOutputStream();
                    for(int i=0;i<response.length;i+=3) {out.write(response,i,Math.min(3,response.length-i));out.flush();}
                    byte[] command=new byte[12];FujiWifiIo.readFully(remote.getInputStream(),command);
                    assertEquals(0x1003,Short.toUnsignedInt(PtpClient.le(command).getShort(6)));
                } catch(IOException e){throw new UncheckedIOException(e);}
            });
            try(Socket socket=new Socket(server.getInetAddress(),server.getLocalPort());FujiWifiIo wifi=new FujiWifiIo(socket,"test")) {
                assertEquals("FUJIFILM X-T5",wifi.cameraName);new PtpClient(wifi).closeSession();
            }
            try{peer.get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        }
    }
    private void rejects(byte[] response) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> peer=executor.submit(()->{
                try(Socket s=server.accept()){byte[] b=new byte[82];FujiWifiIo.readFully(s.getInputStream(),b);s.getOutputStream().write(response);s.getOutputStream().flush();}
                catch(IOException e){throw new UncheckedIOException(e);}
            });
            try(Socket socket=new Socket(server.getInetAddress(),server.getLocalPort())) {
                assertThrows(IOException.class,()->new FujiWifiIo(socket,"test"));
            }
            try{peer.get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        }
    }
    @Test(timeout=5000) public void rejectsAccessDenied() throws Exception {rejects(PtpClientTest.words(12,5,1));}
    @Test(timeout=5000) public void retriesInitialRefusalOnSameConnection() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> peer=executor.submit(()->{
                try(Socket s=server.accept()) {
                    byte[] first=new byte[82],second=new byte[82];
                    FujiWifiIo.readFully(s.getInputStream(),first);s.getOutputStream().write(PtpClientTest.words(12,5,0x2019));s.getOutputStream().flush();
                    FujiWifiIo.readFully(s.getInputStream(),second);assertArrayEquals(first,second);
                    s.getOutputStream().write(ack("X-T5"));s.getOutputStream().flush();
                }catch(IOException e){throw new UncheckedIOException(e);}
            });
            try(Socket socket=new Socket(server.getInetAddress(),server.getLocalPort());FujiWifiIo wifi=new FujiWifiIo(socket,"test")) {assertEquals("X-T5",wifi.cameraName);}
            try{peer.get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        }
    }
    @Test(timeout=5000) public void rejectsUnboundedAck() throws Exception {rejects(PtpClientTest.words(0x7FFFFFFF));}
    @Test(timeout=5000) public void rejectsTruncatedAck() throws Exception {rejects(Arrays.copyOf(ack("X-T5"),12));}
    @Test(timeout=5000) public void rejectsUnexpectedAckType() throws Exception {byte[] b=ack("X-T5");PtpClient.le(b).putInt(4,7);rejects(b);}
    @Test(timeout=5000) public void rejectsUnterminatedCameraName() throws Exception {
        byte[] b=ack("X-T5");b[b.length-2]=65;rejects(b);
    }
    @Test public void directedBroadcastUsesActualPrefix() throws Exception {
        assertEquals("192.168.31.255",FujiDiscovery.broadcast("192.168.31.102",24));
        assertEquals("192.168.31.127",FujiDiscovery.broadcast("192.168.31.102",25));
        assertEquals("10.255.255.255",FujiDiscovery.broadcast("10.4.2.1",8));
        assertEquals("255.255.255.255",FujiDiscovery.broadcast("10.4.2.1",32));
    }
    @Test public void discoveryAdvertisementContainsPhoneAddressAndPcss() {
        String text=new String(FujiDiscovery.advertisement("192.168.31.102"),StandardCharsets.US_ASCII);
        assertEquals("DISCOVERY * HTTP/1.1\r\nHOST: 192.168.31.102\r\nMX: 5\r\nSERVICE: PCSS/1.0\r\n",text);
    }
    @Test public void invitationUsesPeerAndAdvertisedPort() throws Exception {
        FujiDiscovery.Endpoint e=FujiDiscovery.parseInvitation("NOTIFY * HTTP/1.1\r\nDSC: 192.168.31.5\r\nCAMERANAME: X-T5\r\nDSCPORT: 55740\r\n\r\n","192.168.31.5");
        assertEquals("192.168.31.5",e.host);assertEquals("X-T5",e.model);assertEquals(55740,e.port);
    }
    @Test public void refusesDiscoveryRedirectAndInvalidPorts() {
        assertThrows(IOException.class,()->FujiDiscovery.parseInvitation("DSC: 1.2.3.4\r\nDSCPORT: 55740\r\n","192.168.31.5"));
        for(String port:new String[]{"abc","0","65536","-1"})
            assertThrows(IOException.class,()->FujiDiscovery.parseInvitation("DSCPORT: "+port+"\r\n","192.168.31.5"));
        assertThrows(IOException.class,()->FujiDiscovery.parseInvitation("CAMERANAME: X-T5\r\n","192.168.31.5"));
    }
    static FujiDiscovery.NetworkSockets loopback() throws IOException {
        Inet4Address local=(Inet4Address)InetAddress.getByName("127.0.0.1");
        return new FujiDiscovery.NetworkSockets(){
            public Inet4Address localAddress(){return local;}public int prefixLength(){return 8;}
            public Socket socket(){return new Socket();}public DatagramSocket datagram()throws IOException{return new DatagramSocket(null);}
        };
    }
    @Test(timeout=5000) public void cancelledConnectionDoesNotOpenNetwork() throws Exception {
        FujiWifiConnection link=new FujiWifiConnection(loopback());link.close();
        assertThrows(IOException.class,()->link.connect("",s->{}));
    }
    @Test(timeout=5000) public void fullDiscoveryExchangesUdpAndTcpInvitation() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(DatagramSocket camera=new DatagramSocket(new InetSocketAddress("127.0.0.1",51562));FujiDiscovery discovery=new FujiDiscovery(loopback())) {
            camera.setSoTimeout(2500);
            Future<?> peer=executor.submit(()->{
                try {
                    byte[] b=new byte[512];DatagramPacket packet=new DatagramPacket(b,b.length);camera.receive(packet);
                    assertTrue(new String(b,0,packet.getLength(),StandardCharsets.US_ASCII).contains("SERVICE: PCSS/1.0"));
                    try(Socket invite=new Socket("127.0.0.1",51560)) {
                        invite.setSoTimeout(2000);
                        invite.getOutputStream().write("NOTIFY * HTTP/1.1\r\nDSC: 127.0.0.1\r\nCAMERANAME: X-T5\r\nDSCPORT: 55740\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        byte[] reply=new byte[18];FujiWifiIo.readFully(invite.getInputStream(),reply);assertEquals("HTTP/1.1 200 OK\r\n\0",new String(reply,StandardCharsets.US_ASCII));
                    }
                }catch(IOException e){throw new UncheckedIOException(e);}
            });
            FujiDiscovery.Endpoint result=discovery.find("127.0.0.1",2500,s->{});assertEquals(55740,result.port);peer.get(3,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
    }
    @Test public void emptyWirelessInfoRequiresVerifiedXt5Handshake() throws Exception {
        byte[] stream=PtpClientTest.join(PtpClientTest.frame(3,0x2001,0,new byte[0]),PtpClientTest.frame(2,0x1001,1,new byte[0]),PtpClientTest.frame(3,0x2001,1,new byte[0]));
        assertThrows(IOException.class,()->new PtpClient(new PtpClientTest.Pipe(stream)).open());
        assertThrows(IOException.class,()->new PtpClient(new PtpClientTest.Pipe(stream),"X-T4").open());
        assertEquals("X-T5",new PtpClient(new PtpClientTest.Pipe(stream),"X-T5").open().model);
    }
}
