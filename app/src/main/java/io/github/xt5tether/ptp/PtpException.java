package io.github.xt5tether.ptp;

import java.io.IOException;

public final class PtpException extends IOException {
    public final int code;
    public PtpException(int operation, int code) {
        super(String.format("PTP 0x%04X → 0x%04X", operation, code));
        this.code = code;
    }
    public boolean transientFailure() { return code == 0x2019 || code == 0xA002; }
}
