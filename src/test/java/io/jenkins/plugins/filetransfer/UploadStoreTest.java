package io.jenkins.plugins.filetransfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

public class UploadStoreTest {
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void interruptedChunkCanBeRetriedFromItsOriginalOffset() throws Exception {
        FileTransferGlobalConfiguration.get()
                .setStorageRoot(temporary.newFolder().getAbsolutePath());
        FileTransferGlobalConfiguration.get().setMinimumFreeBytes(0);
        FileTransferGlobalConfiguration.get().setMinimumFreePercent(0);
        UploadStore store = new UploadStore(Clock.systemUTC());
        UploadStore.CreatedSession created = store.create("job", "INPUT", "sample.bin", false, 6, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(created.getToken());

        assertThrows(
                IOException.class,
                () -> store.receiveChunk(
                        token.id,
                        token.secret,
                        "sample.bin",
                        6,
                        0,
                        6,
                        true,
                        new FailingInputStream(new byte[] {1, 2, 3, 4, 5, 6}, 3)));
        store.receiveChunk(token.id, token.secret, "sample.bin", 6, 0, 6, true, new ByteArrayInputStream(new byte[] {
            1, 2, 3, 4, 5, 6
        }));

        UploadSession session = store.complete(token.id, token.secret);
        assertEquals(UploadSession.State.COMPLETE, session.getState());
        assertArrayEquals(
                new byte[] {1, 2, 3, 4, 5, 6},
                Files.readAllBytes(store.files(token.id).resolve("sample.bin")));
    }

    @Test
    public void uploadPathsMustRemainUnderTheDeclaredSelection() throws Exception {
        UploadStore store = configuredStore();
        UploadStore.CreatedSession file = store.create("job", "FILE", "sample.bin", false, 1, 1, 1024, 10);
        UploadStore.Token fileToken = UploadStore.Token.parse(file.getToken());
        assertThrows(
                IOException.class,
                () -> store.receiveChunk(
                        fileToken.id,
                        fileToken.secret,
                        "other.bin",
                        1,
                        0,
                        1,
                        true,
                        new ByteArrayInputStream(new byte[] {1})));

        UploadStore.CreatedSession folder = store.create("job", "FOLDER", "input-root", true, 1, 1, 1024, 10);
        UploadStore.Token folderToken = UploadStore.Token.parse(folder.getToken());
        assertThrows(
                IOException.class,
                () -> store.receiveChunk(
                        folderToken.id,
                        folderToken.secret,
                        "different/data.txt",
                        1,
                        0,
                        1,
                        true,
                        new ByteArrayInputStream(new byte[] {1})));
    }

    @Test
    public void overflowingChunkRangeIsRejected() throws Exception {
        UploadStore store = configuredStore();
        UploadStore.CreatedSession created = store.create("job", "INPUT", "sample.bin", false, 1, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(created.getToken());
        assertThrows(
                IOException.class,
                () -> store.receiveChunk(
                        token.id,
                        token.secret,
                        "sample.bin",
                        Long.MAX_VALUE,
                        Long.MAX_VALUE - 1,
                        10,
                        true,
                        new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void tokenCannotBeUsedThroughAnotherJobParameter() throws Exception {
        UploadStore store = configuredStore();
        UploadStore.CreatedSession created = store.create("job", "INPUT", "sample.bin", false, 1, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(created.getToken());
        assertThrows(IOException.class, () -> store.validateTarget(token.id, token.secret, "job", "OTHER"));
        assertThrows(IOException.class, () -> store.validateTarget(token.id, token.secret, "other-job", "INPUT"));
        store.validateTarget(token.id, token.secret, "job", "INPUT");
    }

    @Test
    public void expiredIncompleteAndCompletedSessionsAreCleaned() throws Exception {
        FileTransferGlobalConfiguration config = FileTransferGlobalConfiguration.get();
        config.setStorageRoot(temporary.newFolder().getAbsolutePath());
        config.setMinimumFreeBytes(0);
        config.setMinimumFreePercent(0);
        config.setIncompleteExpiryMinutes(60);
        config.setCompletedExpiryMinutes(120);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UploadStore initial = new UploadStore(Clock.fixed(now, ZoneOffset.UTC));
        UploadStore.CreatedSession incomplete = initial.create("job", "FIRST", "first.bin", false, 1, 1, 1024, 10);
        UploadStore.CreatedSession complete = initial.create("job", "SECOND", "second.bin", false, 1, 1, 1024, 10);
        UploadStore.Token token = UploadStore.Token.parse(complete.getToken());
        initial.receiveChunk(
                token.id, token.secret, "second.bin", 1, 0, 1, true, new ByteArrayInputStream(new byte[] {1}));
        initial.complete(token.id, token.secret);

        assertEquals(
                1, new UploadStore(Clock.fixed(now.plus(Duration.ofMinutes(61)), ZoneOffset.UTC)).cleanupExpired());
        assertEquals(
                1, new UploadStore(Clock.fixed(now.plus(Duration.ofMinutes(121)), ZoneOffset.UTC)).cleanupExpired());
        assertFalse(Files.exists(initial.files(incomplete.getId())));
        assertFalse(Files.exists(initial.files(complete.getId())));
    }

    @Test
    public void allowedAndDeniedExtensionsAreAppliedCaseInsensitively() {
        FileOrFolderParameterDefinition definition = new FileOrFolderParameterDefinition("INPUT");
        definition.setAllowedExtensionsText(".TXT, csv");
        FileTransferGlobalConfiguration.get().setDeniedExtensions("CSV");

        assertEquals(true, definition.allows("folder/README.TXT"));
        assertEquals(false, definition.allows("folder/data.csv"));
        assertEquals(false, definition.allows("folder/image.png"));
    }

    private UploadStore configuredStore() throws Exception {
        FileTransferGlobalConfiguration.get()
                .setStorageRoot(temporary.newFolder().getAbsolutePath());
        FileTransferGlobalConfiguration.get().setMinimumFreeBytes(0);
        FileTransferGlobalConfiguration.get().setMinimumFreePercent(0);
        return new UploadStore(Clock.systemUTC());
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] bytes;
        private final int failAt;
        private int offset;

        FailingInputStream(byte[] bytes, int failAt) {
            this.bytes = bytes;
            this.failAt = failAt;
        }

        @Override
        public int read() throws IOException {
            if (offset == failAt) throw new IOException("simulated interruption");
            return offset == bytes.length ? -1 : bytes[offset++];
        }

        @Override
        public int read(byte[] target, int start, int length) throws IOException {
            if (offset == failAt) throw new IOException("simulated interruption");
            if (offset == bytes.length) return -1;
            int count = Math.min(length, Math.min(failAt - offset, bytes.length - offset));
            System.arraycopy(bytes, offset, target, start, count);
            offset += count;
            return count;
        }
    }
}
