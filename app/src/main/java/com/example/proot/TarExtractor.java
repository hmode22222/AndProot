package com.example.proot;

import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Minimal GNU/ustar .tar.gz extractor used as a fallback when the system
 * tar binary is not available. Handles regular files, directories, symlinks,
 * hard links and GNU long-name/long-link extensions.
 */
public class TarExtractor {

    private static final int BLOCK = 512;

    public static void extractGzip(File archive, File dest) throws IOException {
        extractGzip(archive, dest, null);
    }

    public static void extractGzip(File archive, File dest, SetupProgress progress) throws IOException {
        long total = archive.length();
        try (InputStream in = new GZIPInputStream(
                new ProgressInputStream(new FileInputStream(archive), total, progress))) {
            extractTar(in, dest);
        }
    }

    /** Counts raw bytes read from the compressed archive and reports progress. */
    private static final class ProgressInputStream extends InputStream {
        private final InputStream in;
        private final long total;
        private final SetupProgress progress;
        private long done;
        private long lastPercent = -1;

        ProgressInputStream(InputStream in, long total, SetupProgress progress) {
            this.in = in;
            this.total = total;
            this.progress = progress;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                done++;
                report();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                done += n;
                report();
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        private void report() {
            if (progress == null || total <= 0) return;
            long percent = done * 100 / total;
            if (percent != lastPercent) {
                lastPercent = percent;
                progress.onProgress(done, total);
            }
        }
    }

    public static void extractTar(InputStream in, File dest) throws IOException {
        byte[] header = new byte[BLOCK];
        String pendingName = null;
        String pendingLink = null;

        while (true) {
            if (!readBlock(in, header)) break;
            if (isZeroBlock(header)) break;

            char type = (char) header[156];
            long size = parseOctal(header, 124, 12);

            if (type == 'L') {
                pendingName = readData(in, (int) size);
                skip(in, (BLOCK - (size % BLOCK)) % BLOCK);
                continue;
            }
            if (type == 'K') {
                pendingLink = readData(in, (int) size);
                skip(in, (BLOCK - (size % BLOCK)) % BLOCK);
                continue;
            }

            String name = pendingName != null ? pendingName : readString(header, 0, 100);
            String link = pendingLink != null ? pendingLink : readString(header, 157, 100);
            pendingName = null;
            pendingLink = null;

            if (name.isEmpty()) {
                skip(in, size + (BLOCK - (size % BLOCK)) % BLOCK);
                continue;
            }
            if (name.startsWith("./")) name = name.substring(2);
            if (name.startsWith("/")) name = name.substring(1);

            File target = new File(dest, name);
            long mode = parseOctal(header, 100, 8);

            switch (type) {
                case '5': // directory
                    target.mkdirs();
                    break;
                case '2': // symbolic link
                    mkdirs(target.getParentFile());
                    try {
                        Os.symlink(link, target.getAbsolutePath());
                    } catch (Exception e) {
                        throw new IOException("symlink failed for " + name + ": " + e.getMessage(), e);
                    }
                    break;
                case '1': // hard link
                    mkdirs(target.getParentFile());
                    File linkSource = new File(dest, link);
                    try {
                        Os.link(linkSource.getAbsolutePath(), target.getAbsolutePath());
                    } catch (Exception e) {
                        // fallback: copy the file content
                        if (linkSource.exists() && linkSource.isFile()) {
                            copyFile(linkSource, target);
                        } else {
                            try {
                                Os.symlink(link, target.getAbsolutePath());
                            } catch (Exception ex) {
                                throw new IOException("symlink failed for " + name + ": " + ex.getMessage(), ex);
                            }
                        }
                    }
                    break;
                default: // regular file (or device nodes, which we skip)
                    if (type == '0' || type == '\0') {
                        mkdirs(target.getParentFile());
                        try (OutputStream out = new FileOutputStream(target)) {
                            copyN(in, out, size);
                        }
                        applyMode(target, mode);
                    } else {
                        skip(in, size);
                    }
                    break;
            }

            long pad = (BLOCK - (size % BLOCK)) % BLOCK;
            skip(in, pad);
        }
    }

    private static void applyMode(File f, long mode) {
        f.setExecutable((mode & 0100) != 0, false);
        f.setReadable((mode & 0400) != 0, false);
        f.setWritable((mode & 0200) != 0, false);
    }

    private static String readData(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        String s = new String(buf, 0, off, StandardCharsets.UTF_8);
        if (s.endsWith("\0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) break;
                n--;
            } else {
                n -= s;
            }
        }
    }

    private static boolean readBlock(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off > 0;
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long v = 0;
        boolean started = false;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == ' ' || b == 0) {
                if (started) break;
                continue;
            }
            if (b < '0' || b > '7') break;
            started = true;
            v = (v << 3) | (b - '0');
        }
        return v;
    }

    private static String readString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.exists()) dir.mkdirs();
    }

    private static void copyFile(File src, File dst) throws IOException {
        mkdirs(dst.getParentFile());
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
