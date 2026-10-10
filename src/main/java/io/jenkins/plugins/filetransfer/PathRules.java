package io.jenkins.plugins.filetransfer;

import hudson.Util;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class PathRules {
    private static final Set<String> WINDOWS_DEVICES = new HashSet<>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
            "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

    private PathRules() {}

    static String normalizeRelative(String raw, int maxDepth) throws IOException {
        String value = Util.fixEmptyAndTrim(raw);
        if (value == null || value.indexOf('\0') >= 0) throw new IOException("Empty or invalid path");
        value = value.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("//") || value.matches("^[A-Za-z]:.*"))
            throw new IOException("Absolute paths are not allowed");
        String[] segments = value.split("/", -1);
        if (segments.length > maxDepth) throw new IOException("Path exceeds the configured depth limit");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IOException("Invalid path segment");
            String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (WINDOWS_DEVICES.contains(base) || segment.endsWith(".") || segment.endsWith(" "))
                throw new IOException("Unsupported file name: " + segment);
            if (segment.indexOf(':') >= 0) throw new IOException("Unsupported file name: " + segment);
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    static Path resolveInside(Path root, String relative, int maxDepth) throws IOException {
        Path target = root.resolve(normalizeRelative(relative, maxDepth)).normalize();
        if (!target.startsWith(root.normalize())) throw new IOException("Path escapes the upload directory");
        return target;
    }
}
