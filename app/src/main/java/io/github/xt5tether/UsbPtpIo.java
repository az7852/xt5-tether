package io.github.xt5tether;

import android.hardware.usb.*;
import java.io.IOException;
import io.github.xt5tether.ptp.PtpIo;

final class UsbPtpIo implements PtpIo {
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint input, output;
    private volatile boolean closed;
    UsbPtpIo(UsbManager manager, UsbDevice device) throws IOException {
        UsbInterface chosen = null; UsbEndpoint in = null, out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;
            UsbEndpoint ci = null, co = null;
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint e = candidate.getEndpoint(j);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) ci = e; else co = e;
            }
            if (ci != null && co != null) { chosen = candidate; in = ci; out = co; break; }
        }
        if (chosen == null) throw new IOException("未找到 PTP 接口，请选择 USB 联机拍摄模式");
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("无法打开 USB，请重新授权或检查 OTG 连接");
        if (!connection.claimInterface(chosen, true)) { connection.close(); throw new IOException("相机 USB 接口被其他应用占用"); }
        usbInterface = chosen; input = in; output = out;
    }
    @Override public int read(byte[] buffer) throws IOException {
        if (closed) throw new IOException("USB 已断开");
        // Always request whole USB packets. PtpClient preserves any following container bytes.
        int n = connection.bulkTransfer(input, buffer, buffer.length, 5000);
        if (n == 0 && !closed) n = connection.bulkTransfer(input, buffer, buffer.length, 5000);
        if (n <= 0) throw new IOException("USB 读取超时或连接中断，请检查数据线并重新连接");
        return n;
    }
    @Override public void write(byte[] bytes) throws IOException {
        if (closed) throw new IOException("USB 已断开");
        int n = connection.bulkTransfer(output, bytes, bytes.length, 5000);
        if (n != bytes.length) throw new IOException("USB 写入不完整，请重新连接");
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        connection.releaseInterface(usbInterface); connection.close();
    }
}
