package io.github.xt5tether.ptp;

import java.io.*;

/** A complete private original exists, but the chosen final destination failed. */
public final class CaptureSaveException extends IOException {
    public final File file;
    CaptureSaveException(File file,IOException cause) { super(cause.getMessage(),cause); this.file=file; }
}
