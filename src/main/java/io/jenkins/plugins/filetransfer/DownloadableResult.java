package io.jenkins.plugins.filetransfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownloadableResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String id;
    private final String name;
    private final String artifactPath;
    private final boolean directory;
    private final List<String> files;

    public DownloadableResult(String id, String name, String artifactPath, boolean directory, List<String> files) {
        this.id = id;
        this.name = name;
        this.artifactPath = artifactPath;
        this.directory = directory;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public boolean isDirectory() {
        return directory;
    }

    public List<String> getFiles() {
        return files;
    }
}
