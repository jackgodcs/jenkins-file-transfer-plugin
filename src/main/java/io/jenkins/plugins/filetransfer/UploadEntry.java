package io.jenkins.plugins.filetransfer;

import java.io.Serializable;

public final class UploadEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String path;
    private final long size;

    public UploadEntry(String path, long size) {
        this.path = path;
        this.size = size;
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }
}
