package io.jenkins.plugins.filetransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DownloadableResultTest {
    @Test
    public void keepsAnImmutableDirectoryFileList() {
        List<String> files = new ArrayList<>(List.of("a.txt", "nested/b.txt"));
        DownloadableResult result = new DownloadableResult("id", "Results", "prefix/", true, files);
        files.clear();
        assertEquals(List.of("a.txt", "nested/b.txt"), result.getFiles());
        assertThrows(
                UnsupportedOperationException.class, () -> result.getFiles().add("other.txt"));
    }
}
