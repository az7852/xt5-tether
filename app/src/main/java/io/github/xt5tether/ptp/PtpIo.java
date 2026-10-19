package io.github.xt5tether.ptp;

import java.io.Closeable;
import java.io.IOException;

/** One USB bulk pipe pair. Reads may split or combine PTP containers. */
public interface PtpIo extends Closeable {
    int read(byte[] buffer) throws IOException;
    void write(byte[] bytes) throws IOException;
    default String diagnostic() {return "";}
}
