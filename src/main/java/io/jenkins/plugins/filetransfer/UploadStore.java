package io.jenkins.plugins.filetransfer;

import hudson.Util;
import hudson.model.User;
import hudson.util.XStream2;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

public final class UploadStore {
    private static final Logger LOGGER = Logger.getLogger(UploadStore.class.getName());
    private static final XStream2 XSTREAM = new XStream2();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final UploadStore INSTANCE = new UploadStore(Clock.systemUTC());
    private final Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    UploadStore(Clock clock) {
        this.clock = clock;
    }

    public static UploadStore get() {
        return INSTANCE;
    }

    public synchronized CreatedSession create(
            String jobFullName,
            String parameterName,
            String topLevelName,
            boolean folder,
            long bytes,
            int files,
            long maxBytes,
            int maxFiles)
            throws IOException {
        FileTransferGlobalConfiguration config = FileTransferGlobalConfiguration.get();
        String userId = currentUserId();
        if (bytes < 0 || bytes > Math.min(maxBytes, config.getHardMaxBytes()))
            throw new IOException("Upload exceeds the configured size limit");
        if (files < 1 || files > Math.min(maxFiles, config.getHardMaxFiles()))
            throw new IOException("Upload exceeds the configured file count limit");
        enforceConcurrency(userId, config);
        Path root = sessionsRoot();
        Files.createDirectories(root);
        long usable = Files.getFileStore(root).getUsableSpace();
        long total = Files.getFileStore(root).getTotalSpace();
        long reserve = Math.max(config.getMinimumFreeBytes(), total * config.getMinimumFreePercent() / 100);
        if (usable - bytes < reserve) throw new IOException("Insufficient free space for this upload");
        String id = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        UploadSession session = new UploadSession(
                id,
                hash(secret),
                userId,
                jobFullName,
                parameterName,
                PathRules.normalizeRelative(topLevelName, 1),
                folder,
                bytes,
                files,
                clock.millis());
        Path directory = sessionDirectory(id);
        Files.createDirectories(directory.resolve("files"));
        Files.write(directory.resolve(".managed-by-jenkins-file-transfer"), new byte[0], StandardOpenOption.CREATE_NEW);
        save(session);
        audit("created", session, null);
        return new CreatedSession(id, secret);
    }

    public UploadSession receiveChunk(
            String id,
            String secret,
            String relativePath,
            long fileSize,
            long offset,
            long chunkBytes,
            boolean last,
            InputStream input)
            throws IOException {
        synchronized (lock(id)) {
            UploadSession session = requireOwned(id, secret);
            if (session.getState() != UploadSession.State.UPLOADING)
                throw new IOException("Upload session is not writable");
            String normalized = PathRules.normalizeRelative(
                    relativePath, FileTransferGlobalConfiguration.get().getMaxDepth());
            if (fileSize < 0 || offset < 0 || chunkBytes < 0 || offset > fileSize || chunkBytes > fileSize - offset)
                throw new IOException("Invalid upload chunk range");
            if ((!session.isFolder() && !normalized.equals(session.getTopLevelName()))
                    || (session.isFolder() && !normalized.startsWith(session.getTopLevelName() + "/")))
                throw new IOException("Upload path does not match the selected file or folder");
            for (UploadEntry entry : session.getEntries()) {
                if (entry.getPath().equals(normalized)) {
                    if (last && entry.getSize() == fileSize && offset + chunkBytes == fileSize) return session;
                    throw new IOException("Duplicate upload path: " + normalized);
                }
            }
            if (session.getEntries().size() + 1 > session.getDeclaredFiles())
                throw new IOException("File count exceeds the declared manifest");
            if (session.getReceivedBytes() > session.getDeclaredBytes()
                    || fileSize > session.getDeclaredBytes() - session.getReceivedBytes())
                throw new IOException("Upload size exceeds the declared manifest");
            Path target = PathRules.resolveInside(
                    sessionDirectory(id).resolve("files"),
                    normalized,
                    FileTransferGlobalConfiguration.get().getMaxDepth());
            Path parent = target.getParent();
            if (parent == null) throw new IOException("Upload path has no parent directory");
            Files.createDirectories(parent);
            Path part = target.resolveSibling(target.getFileName() + ".part");
            long current = Files.exists(part) ? Files.size(part) : 0;
            if (current == offset + chunkBytes) return session;
            if (current != offset) throw new IOException("Upload offset mismatch; expected " + current);
            long written;
            try (InputStream limited = new LimitedInputStream(input, chunkBytes + 1);
                    OutputStream output =
                            Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                written = copy(limited, output);
                if (written != chunkBytes)
                    throw new IOException("Received byte count does not match the chunk manifest");
            } catch (IOException exception) {
                try (SeekableByteChannel channel = Files.newByteChannel(part, StandardOpenOption.WRITE)) {
                    channel.truncate(offset);
                } catch (IOException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
            if (last) {
                if (offset + written != fileSize) throw new IOException("Final chunk does not complete the file");
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException exception) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
                session.addEntry(new UploadEntry(normalized, fileSize), clock.millis());
            } else {
                if (offset + written >= fileSize) throw new IOException("A completed file must be marked as final");
                session.touch(clock.millis());
            }
            save(session);
            return session;
        }
    }

    public void validateTarget(String id, String secret, String jobFullName, String parameterName) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = requireOwned(id, secret);
            if (!session.getJobFullName().equals(jobFullName)
                    || !session.getParameterName().equals(parameterName))
                throw new IOException("Upload token does not belong to this job parameter");
        }
    }

    public UploadSession complete(String id, String secret) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = requireOwned(id, secret);
            if (session.getState() != UploadSession.State.UPLOADING)
                throw new IOException("Upload session cannot be completed");
            if (session.getEntries().size() != session.getDeclaredFiles()
                    || session.getReceivedBytes() != session.getDeclaredBytes())
                throw new IOException("Upload manifest is incomplete");
            session.complete(clock.millis());
            save(session);
            audit("completed", session, null);
            return session;
        }
    }

    public UploadSession reserve(String token, String jobFullName, String parameterName) throws IOException {
        Token parsed = Token.parse(token);
        synchronized (lock(parsed.id)) {
            UploadSession session = requireOwned(parsed.id, parsed.secret);
            if (session.getState() != UploadSession.State.COMPLETE)
                throw new IOException("Upload token has already been consumed or is incomplete");
            if (!session.getJobFullName().equals(jobFullName)
                    || !session.getParameterName().equals(parameterName))
                throw new IOException("Upload token does not belong to this job parameter");
            session.reserve(clock.millis());
            save(session);
            audit("reserved", session, null);
            return session;
        }
    }

    public UploadSession bind(String id, String buildExternalizableId) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = load(id);
            if (session.getState() == UploadSession.State.BOUND
                    && buildExternalizableId.equals(session.getBuildExternalizableId())) return session;
            if (session.getState() != UploadSession.State.RESERVED)
                throw new IOException("Upload session is not reserved for a build");
            session.bind(buildExternalizableId, clock.millis());
            save(session);
            audit("bound", session, buildExternalizableId);
            return session;
        }
    }

    public synchronized void bindAll(java.util.List<String> ids, String buildExternalizableId) throws IOException {
        java.util.List<UploadSession> sessions = new java.util.ArrayList<>();
        for (String id : ids) {
            UploadSession session = load(id);
            if (session.getState() == UploadSession.State.BOUND
                    && buildExternalizableId.equals(session.getBuildExternalizableId())) continue;
            if (session.getState() != UploadSession.State.RESERVED)
                throw new IOException("Upload session is not reserved for a build");
            sessions.add(session);
        }
        for (UploadSession session : sessions) {
            session.bind(buildExternalizableId, clock.millis());
            save(session);
            audit("bound", session, buildExternalizableId);
        }
    }

    public void retain(String id, String buildExternalizableId, long expiresAt) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = loadBound(id, buildExternalizableId);
            session.retainUntil(expiresAt, clock.millis());
            save(session);
            audit("retained", session, buildExternalizableId);
        }
    }

    public boolean isBoundTo(String id, String buildExternalizableId) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = load(id);
            return session.getState() == UploadSession.State.BOUND
                    && buildExternalizableId.equals(session.getBuildExternalizableId());
        }
    }

    public UploadSession loadBound(String id, String buildExternalizableId) throws IOException {
        UploadSession session = load(id);
        if (session.getState() != UploadSession.State.BOUND
                || !buildExternalizableId.equals(session.getBuildExternalizableId()))
            throw new IOException("Upload session does not belong to this build");
        verify(session);
        return session;
    }

    public Path files(String id) {
        return sessionDirectory(id).resolve("files");
    }

    public void delete(String id) throws IOException {
        synchronized (lock(id)) {
            UploadSession session = null;
            try {
                session = load(id);
            } catch (IOException exception) {
                LOGGER.log(Level.FINE, "Upload metadata is unavailable while deleting session " + id, exception);
            }
            deleteTree(sessionDirectory(id));
            locks.remove(id);
            if (session != null) audit("deleted", session, session.getBuildExternalizableId());
        }
    }

    public int cleanupExpired() {
        int deleted = 0;
        try {
            Files.createDirectories(sessionsRoot());
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(sessionsRoot())) {
                for (Path directory : directories) {
                    try {
                        Path name = directory.getFileName();
                        if (name == null) continue;
                        UploadSession session = load(name.toString());
                        long minutes = (clock.millis() - session.getUpdatedAt()) / 60_000;
                        int expiry = session.getState() == UploadSession.State.UPLOADING
                                ? FileTransferGlobalConfiguration.get().getIncompleteExpiryMinutes()
                                : FileTransferGlobalConfiguration.get().getCompletedExpiryMinutes();
                        boolean preBuildExpired = session.getState() != UploadSession.State.BOUND && minutes >= expiry;
                        boolean boundExpired = session.getState() == UploadSession.State.BOUND
                                && session.getExpiresAt() > 0
                                && clock.millis() >= session.getExpiresAt();
                        if (preBuildExpired || boundExpired) {
                            delete(session.getId());
                            deleted++;
                        }
                    } catch (Exception exception) {
                        LOGGER.log(Level.WARNING, "Failed to inspect upload session " + directory, exception);
                    }
                }
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to clean upload sessions", exception);
        }
        return deleted;
    }

    private void enforceConcurrency(String userId, FileTransferGlobalConfiguration config) throws IOException {
        int all = 0;
        int user = 0;
        Files.createDirectories(sessionsRoot());
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(sessionsRoot())) {
            for (Path directory : directories) {
                try {
                    Path name = directory.getFileName();
                    if (name == null) continue;
                    UploadSession session = load(name.toString());
                    if (session.getState() == UploadSession.State.UPLOADING) {
                        all++;
                        if (session.getUserId().equals(userId)) user++;
                    }
                } catch (IOException exception) {
                    LOGGER.log(
                            Level.FINE,
                            "Upload metadata is unavailable while counting concurrent sessions in " + directory,
                            exception);
                }
            }
        }
        if (all >= config.getMaxConcurrentUploads()) throw new IOException("The server upload limit has been reached");
        if (user >= config.getMaxConcurrentUploadsPerUser())
            throw new IOException("Your concurrent upload limit has been reached");
    }

    private UploadSession requireOwned(String id, String secret) throws IOException {
        UploadSession session = load(id);
        if (!MessageDigest.isEqual(
                session.getSecretHash().getBytes(StandardCharsets.US_ASCII),
                hash(secret).getBytes(StandardCharsets.US_ASCII))) throw new IOException("Invalid upload token");
        if (!session.getUserId().equals(currentUserId())) throw new IOException("Upload token belongs to another user");
        return session;
    }

    private void verify(UploadSession session) throws IOException {
        long bytes = 0;
        int files = 0;
        for (UploadEntry entry : session.getEntries()) {
            Path file = PathRules.resolveInside(
                    files(session.getId()),
                    entry.getPath(),
                    FileTransferGlobalConfiguration.get().getMaxDepth());
            if (!Files.isRegularFile(file) || Files.size(file) != entry.getSize())
                throw new IOException("Uploaded data is missing or incomplete; upload it again");
            bytes += entry.getSize();
            files++;
        }
        if (bytes != session.getDeclaredBytes() || files != session.getDeclaredFiles())
            throw new IOException("Uploaded data is missing or incomplete; upload it again");
    }

    private UploadSession load(String id) throws IOException {
        Path metadata = sessionDirectory(safeId(id)).resolve("session.xml");
        if (!Files.isRegularFile(metadata)) throw new IOException("Upload session was not found or has expired");
        try (InputStream input = Files.newInputStream(metadata)) {
            return (UploadSession) XSTREAM.fromXML(input);
        } catch (RuntimeException exception) {
            throw new IOException("Upload session metadata is invalid", exception);
        }
    }

    private void save(UploadSession session) throws IOException {
        Path metadata = sessionDirectory(session.getId()).resolve("session.xml");
        Path temporary = metadata.resolveSibling("session.xml.tmp");
        try (OutputStream output =
                Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            XSTREAM.toXML(session, output);
        }
        try {
            Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path sessionsRoot() {
        return FileTransferGlobalConfiguration.get().getStorageRootPath().resolve("sessions");
    }

    private Path sessionDirectory(String id) {
        return sessionsRoot().resolve(safeId(id));
    }

    private Object lock(String id) {
        return locks.computeIfAbsent(safeId(id), ignored -> new Object());
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid session id");
        return id;
    }

    private static String currentUserId() {
        User user = User.current();
        return user == null ? "anonymous" : user.getId();
    }

    private static String hash(String secret) throws IOException {
        try {
            return Base64.getEncoder()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void audit(String event, UploadSession session, String buildId) {
        LOGGER.info("upload " + event + " user=" + session.getUserId() + " job=" + session.getJobFullName() + " build="
                + (buildId == null ? "-" : buildId) + " parameter=" + session.getParameterName() + " type="
                + (session.isFolder() ? "folder" : "file") + " name=" + session.getTopLevelName() + " files="
                + session.getDeclaredFiles() + " bytes=" + session.getDeclaredBytes());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : ordered) Files.deleteIfExists(path);
        }
    }

    public static final class CreatedSession {
        private final String id;
        private final String secret;

        CreatedSession(String id, String secret) {
            this.id = id;
            this.secret = secret;
        }

        public String getId() {
            return id;
        }

        public String getToken() {
            return id + "." + secret;
        }
    }

    static final class Token {
        final String id;
        final String secret;

        Token(String id, String secret) {
            this.id = id;
            this.secret = secret;
        }

        static Token parse(String token) throws IOException {
            String value = Util.fixEmptyAndTrim(token);
            int separator = value == null ? -1 : value.indexOf('.');
            if (separator < 1 || separator == value.length() - 1) throw new IOException("Invalid upload token");
            try {
                return new Token(safeId(value.substring(0, separator)), value.substring(separator + 1));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid upload token", exception);
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(@Nonnull byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
