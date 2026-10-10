package io.jenkins.plugins.filetransfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class UploadSession implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum State {
        UPLOADING,
        COMPLETE,
        RESERVED,
        BOUND
    }

    private final String id;
    private final String secretHash;
    private final String userId;
    private final String jobFullName;
    private final String parameterName;
    private final String topLevelName;
    private final boolean folder;
    private final long declaredBytes;
    private final int declaredFiles;
    private final long createdAt;
    private long updatedAt;
    private long receivedBytes;
    private State state = State.UPLOADING;
    private String buildExternalizableId;
    private long expiresAt;
    private final List<UploadEntry> entries = new ArrayList<>();

    public UploadSession(
            String id,
            String secretHash,
            String userId,
            String jobFullName,
            String parameterName,
            String topLevelName,
            boolean folder,
            long declaredBytes,
            int declaredFiles,
            long now) {
        this.id = id;
        this.secretHash = secretHash;
        this.userId = userId;
        this.jobFullName = jobFullName;
        this.parameterName = parameterName;
        this.topLevelName = topLevelName;
        this.folder = folder;
        this.declaredBytes = declaredBytes;
        this.declaredFiles = declaredFiles;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getUserId() {
        return userId;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public String getParameterName() {
        return parameterName;
    }

    public String getTopLevelName() {
        return topLevelName;
    }

    public boolean isFolder() {
        return folder;
    }

    public long getDeclaredBytes() {
        return declaredBytes;
    }

    public int getDeclaredFiles() {
        return declaredFiles;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public State getState() {
        return state;
    }

    public String getBuildExternalizableId() {
        return buildExternalizableId;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public List<UploadEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void addEntry(UploadEntry entry, long now) {
        entries.add(entry);
        receivedBytes += entry.getSize();
        updatedAt = now;
    }

    public void touch(long now) {
        updatedAt = now;
    }

    public void complete(long now) {
        state = State.COMPLETE;
        updatedAt = now;
    }

    public void reserve(long now) {
        state = State.RESERVED;
        updatedAt = now;
    }

    public void bind(String buildId, long now) {
        state = State.BOUND;
        buildExternalizableId = buildId;
        updatedAt = now;
    }

    public void retainUntil(long timestamp, long now) {
        expiresAt = timestamp;
        updatedAt = now;
    }
}
