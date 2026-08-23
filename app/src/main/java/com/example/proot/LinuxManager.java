package com.example.proot;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a lightweight Linux system (Ubuntu base rootfs) plus a real PRoot
 * binary, then runs real Linux commands inside the app without root.
 */
public class LinuxManager {

    private static final String PROOT_BASE =
            "https://skirsten.github.io/proot-portable-android-binaries/";
    private static final String UBUNTU_CODENAME = "noble";
    private static final String[] UBUNTU_BASES = {
            "https://cdimage.ubuntu.com/ubuntu-base/releases/" + UBUNTU_CODENAME + "/release/",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/" + UBUNTU_CODENAME + "/release/",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/" + UBUNTU_CODENAME + "/release/",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/" + UBUNTU_CODENAME + "/release/"
    };
    private static final String ROOTFS_VERSION = "ubuntu-" + UBUNTU_CODENAME;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android) PRootTerminal/1.0";
    private static final String HOST = "android";
    private static final String[] DIRECT_VERSIONS = {
            "24.04", "24.04.1", "24.04.2", "24.04.3",
            "24.04.4", "24.04.5", "24.04.6"
    };

    private final Context context;
    private final Shell.Terminal terminal;
    private final File base;
    private final File rootfs;
    private final File binDir;
    private final File prootBin;
    private final File tmpDir;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile Process shellProcess;
    private volatile BufferedWriter shellIn;
    private volatile boolean shellActive;

    private volatile SetupProgress setupProgress;
    private volatile String lastSetupError;

    public LinuxManager(Context context, Shell.Terminal terminal) {
        this.context = context;
        this.terminal = terminal;
        this.base = new File(context.getFilesDir(), "linux");
        this.rootfs = new File(base, "rootfs");
        this.binDir = new File(base, "bin");
        this.tmpDir = new File(base, "tmp");
        this.prootBin = new File(binDir, "proot");
    }

    public void setSetupProgress(SetupProgress progress) {
        this.setupProgress = progress;
    }

    public boolean isInstalled() {
        File marker = new File(base, "rootfs-version");
        File osRelease = new File(rootfs, "etc/os-release");
        return prootBin.exists() && prootBin.canExecute()
                && rootfs.isDirectory()
                && new File(rootfs, "bin").exists()
                && osRelease.isFile() && readFileTrim(osRelease).contains("Ubuntu")
                && marker.isFile() && ROOTFS_VERSION.equals(readFileTrim(marker));
    }

    public boolean isShellActive() {
        return shellActive;
    }

    public String getProotPath() {
        return prootBin.getAbsolutePath();
    }

    public String getRootfsPath() {
        return rootfs.getAbsolutePath();
    }

    public String getTmpPath() {
        return tmpDir.getAbsolutePath();
    }

    public void prepareForShell() {
        try {
            writeResolvConf();
            postConfigure();
        } catch (Exception ignored) {
        }
    }

    public String status() {
        if (isInstalled()) {
            return "Ubuntu installed for " + detectArch()
                    + ". Use 'linux' to enter the shell, or type any command directly.";
        }
        return "Linux environment not installed. Run 'linux-setup' to download Ubuntu (~30 MB).";
    }

    public void setup() {
        try {
            String arch = detectArch();
            String archUbuntu = ubuntuArch();
            stage("جاري تثبيت أوبونتو (" + UBUNTU_CODENAME + ")...");
            terminal.append("Installing Ubuntu (" + UBUNTU_CODENAME + ") for " + arch + " ...\n");
            base.mkdirs();
            binDir.mkdirs();
            tmpDir.mkdirs();
            new File(base, "dl").mkdirs();

            if (!prootBin.exists()) {
                terminal.append("  Installing PRoot binary ...\n");
                if (!extractProotFromAssets()) {
                    stage("Downloading PRoot binary");
                    terminal.append("  Downloading PRoot binary ...\n");
                    download(new URL(PROOT_BASE + arch + "/proot"), prootBin);
                }
                prootBin.setExecutable(true, false);
                terminal.append("  PRoot ready.\n");
            }

            if (!extractRootfsFromAssets(archUbuntu)) {
                List<String> tarballUrls = candidateTarballUrls(archUbuntu);
                File tarball = new File(base, "dl/ubuntu-base-" + archUbuntu + ".tar.gz");
                if (tarball.exists() && tarball.length() >= 1_000_000) {
                    terminal.append("  Using cached Ubuntu base rootfs.\n");
                } else {
                    stage("Downloading Ubuntu base rootfs");
                    terminal.append("  Downloading Ubuntu base rootfs ...\n");
                    downloadFirst(tarballUrls, tarball);
                }
                stage("Extracting rootfs");
                terminal.append("  Extracting rootfs ...\n");
                if (rootfs.exists()) deleteRecursive(rootfs);
                rootfs.mkdirs();
                extractRootfs(tarball, rootfs);
            }

            writeResolvConf();
            writeFile(new File(rootfs, "etc/hostname"), HOST + "\n");
            new File(rootfs, "root").mkdirs();
            new File(rootfs, "tmp").mkdirs();
            new File(rootfs, "dev").mkdirs();
            new File(rootfs, "proc").mkdirs();
            new File(rootfs, "sys").mkdirs();
            stage("إعداد النظام...");
            postConfigure();
            writeFile(new File(base, "rootfs-version"), ROOTFS_VERSION);

            stage("تم!");
            terminal.append("\nDone! Type 'linux' to enter the Ubuntu shell, "
                    + "or run any command directly, e.g.: apt update && apt install python3\n");
        } catch (Exception e) {
            lastSetupError = describe(e);
            terminal.append("Setup failed: " + e.getMessage() + "\n");
            terminal.append("Check your internet connection and try again.\n");
            try {
                java.io.File f = new java.io.File(context.getFilesDir(), "crash.log");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                     java.io.PrintWriter pw = new java.io.PrintWriter(fos)) {
                    pw.println("=== LinuxManager.setup @ " + System.currentTimeMillis() + " ===");
                    e.printStackTrace(pw);
                    pw.println();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void downloadFirst(List<String> urls, File dest) throws IOException {
        IOException last = null;
        int tried = 0;
        for (String u : urls) {
            if (tried >= 9) break;
            if (dest.exists() && dest.length() >= 1_000_000) {
                last = null;
                break;
            }
            try {
                stage("Downloading Ubuntu base rootfs (mirror " + (tried + 1) + ")");
                download(new URL(u), dest, setupProgress);
                return;
            } catch (IOException e) {
                last = e;
                tried++;
                try {
                    if (dest.exists()) dest.delete();
                } catch (Exception ignored) {
                }
            }
        }
        throw last != null ? last : new IOException("No usable mirror found");
    }

    private void stage(String message) {
        SetupProgress p = setupProgress;
        if (p != null) p.onStage(message);
    }

    public void startShell() {
        executor.execute(() -> {
            try {
                if (!isInstalled()) {
                    terminal.append("Linux environment not installed. Run 'linux-setup' first.\n");
                    return;
                }
                try {
                    writeResolvConf();
                    postConfigure();
                } catch (Exception ignored) {
                }
                List<String> cmd = prootCommand();
                cmd.add("/bin/sh");
                shellProcess = startProcess(cmd);
                shellIn = new BufferedWriter(
                        new OutputStreamWriter(shellProcess.getOutputStream()));
                shellActive = true;

                terminal.append("Entering Ubuntu shell inside PRoot.\n"
                        + "Type 'exit' to return. Your files are under /host\n"
                        + "root@android:~# ");

                Process p = shellProcess;
                Thread reader = new Thread(() -> pumpOutput(p), "proot-reader");
                reader.start();
            } catch (Exception e) {
                terminal.append("Failed to start Linux shell: " + e.getMessage() + "\n");
                shellActive = false;
            }
        });
    }

    public void sendLine(String line) {
        executor.execute(() -> {
            try {
                if (line.trim().equals("exit")) {
                    closeShell();
                    return;
                }
                if (shellIn == null) {
                    shellActive = false;
                    return;
                }
                terminal.append(line + "\n");
                shellIn.write(line);
                shellIn.newLine();
                shellIn.flush();
            } catch (Exception e) {
                terminal.append("[linux shell closed: " + e.getMessage() + "]\n");
                shellActive = false;
            }
        });
    }

    public void runCommand(String line) {
        executor.execute(() -> {
            try {
                if (!isInstalled()) {
                    terminal.append("Linux environment not installed. Run 'linux-setup' first.\n");
                    return;
                }
                try {
                    writeResolvConf();
                    postConfigure();
                } catch (Exception ignored) {
                }
                List<String> cmd = prootCommand();
                cmd.add("/bin/sh");
                cmd.add("-lc");
                cmd.add(line);
                Process p = startProcess(cmd);
                pumpOutput(p);
            } catch (Exception e) {
                terminal.append(e.getMessage() + "\n");
            }
        });
    }

    public void close() {
        closeShell();
        executor.shutdownNow();
    }

    private void closeShell() {
        try { if (shellIn != null) shellIn.close(); } catch (IOException ignored) {}
        try { if (shellProcess != null) shellProcess.destroy(); } catch (Exception ignored) {}
        shellIn = null;
        shellProcess = null;
        if (shellActive) {
            shellActive = false;
            terminal.append("\n[Left Linux shell]\n");
        }
    }

    private void pumpOutput(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                terminal.append(line + "\n");
            }
        } catch (IOException ignored) {
        }
        if (p == shellProcess) {
            shellActive = false;
            terminal.append("\n[Linux shell exited]\n");
        }
    }

    private Process startProcess(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TERM", "xterm-256color");
        return pb.start();
    }

    private List<String> prootCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(context.getFilesDir().getAbsolutePath() + ":/host");
        return cmd;
    }

    // ---- Download helpers ----

    private List<String> candidateTarballUrls(String arch) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String base : UBUNTU_BASES) {
            try {
                String page = fetchString(base);
                Pattern p = Pattern.compile("ubuntu-base-[0-9][0-9.]*-base-" + arch + "\\.tar\\.gz(?!\\.)");
                Matcher m = p.matcher(page);
                if (m.find()) urls.add(base + m.group());
            } catch (IOException ignored) {
            }
        }
        for (String base : UBUNTU_BASES) {
            for (String v : DIRECT_VERSIONS) {
                urls.add(base + "ubuntu-base-" + v + "-base-" + arch + ".tar.gz");
            }
        }
        return new ArrayList<>(urls);
    }

    public String getLastSetupError() {
        return lastSetupError;
    }

    /** Quick on-device diagnostics shown when setup fails. Run off the UI thread. */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        try {
            android.os.StatFs sf = new android.os.StatFs(base.getParentFile().getAbsolutePath());
            sb.append("Disk free: ").append(sf.getAvailableBytes() / (1024 * 1024)).append(" MB\n");
        } catch (Throwable t) {
            sb.append("Disk check failed\n");
        }
        for (String host : new String[]{"cdimage.ubuntu.com",
                "mirrors.tuna.tsinghua.edu.cn", "mirrors.ustc.edu.cn",
                "mirrors.aliyun.com", "skirsten.github.io"}) {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                sb.append(host).append(" -> ");
                for (InetAddress a : addrs) sb.append(a.getHostAddress()).append(' ');
                sb.append('\n');
            } catch (Exception e) {
                sb.append(host).append(" -> DNS FAIL: ")
                        .append(e.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
        return msg;
    }

    private String fetchString(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return fetchStringOnce(url);
            } catch (IOException e) {
                last = e;
                sleep(600L * (attempt + 1));
            }
        }
        throw last;
    }

    private String fetchStringOnce(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private void download(URL url, File dest) throws IOException {
        download(url, dest, null);
    }

    private void download(URL url, File dest, SetupProgress progress) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                downloadOnce(url, dest, progress);
                return;
            } catch (IOException e) {
                last = e;
                sleep(700L * (attempt + 1));
            }
        }
        throw last;
    }

    private void downloadOnce(URL url, File dest, SetupProgress progress) throws IOException {
        HttpURLConnection conn = open(url.toString());
        try {
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && total > 0) {
                        progress.onProgress(done, total);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    // ---- Rootfs extraction ----

    private boolean extractProotFromAssets() {
        String asset = "proot-" + detectArch();
        try (InputStream in = context.getAssets().open(asset)) {
            prootBin.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(prootBin)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean extractRootfsFromAssets(String archUbuntu) {
        String[] names = {
                "ubuntu-base-" + archUbuntu + ".bin",
                "ubuntu-base-" + archUbuntu + ".tgz",
                "ubuntu-base-" + archUbuntu + ".tar.gz",
                "ubuntu-base-" + archUbuntu + ".tar"
        };
        String found = null;
        for (String name : names) {
            try {
                context.getAssets().open(name).close();
                found = name;
                break;
            } catch (IOException ignored) {
            }
        }
        if (found == null) return false;
        stage("جاري فك ضغط النظام...");
        terminal.append("  Extracting rootfs ...\n");
        try (InputStream in = new GZIPInputStream(context.getAssets().open(found))) {
            if (rootfs.exists()) deleteRecursive(rootfs);
            rootfs.mkdirs();
            TarExtractor.extractTar(in, rootfs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractRootfs(File tarball, File dest) throws Exception {
        if (trySystemTar(tarball, dest)) return;
        terminal.append("  (using built-in tar extractor)\n");
        TarExtractor.extractGzip(tarball, dest, setupProgress);
    }

    private boolean trySystemTar(File tarball, File dest) {
        for (String tar : new String[]{"/system/bin/tar", "/system/xbin/tar"}) {
            File tf = new File(tar);
            if (!tf.exists()) continue;
            try {
                Process p = new ProcessBuilder(tar, "-xzf", tarball.getAbsolutePath())
                        .directory(dest)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    while (r.readLine() != null) { /* drain */ }
                }
                if (p.waitFor() == 0 && new File(dest, "bin").exists()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private String detectArch() {
        if (Build.SUPPORTED_ABIS != null) {
            for (String abi : Build.SUPPORTED_ABIS) {
                switch (abi) {
                    case "arm64-v8a": return "aarch64";
                    case "x86_64": return "x86_64";
                    case "armeabi-v7a":
                    case "armv7a": return "armv7";
                    case "x86": return "x86";
                }
            }
        }
        return "aarch64";
    }

    private String ubuntuArch() {
        switch (detectArch()) {
            case "x86_64": return "amd64";
            case "armv7": return "armhf";
            case "x86": return "i386";
            default: return "arm64";
        }
    }

    private void writeFile(File f, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(content.getBytes("UTF-8"));
        out.close();
    }

    // ---- DNS configuration ----

    private void writeResolvConf() throws IOException {
        Set<String> servers = new LinkedHashSet<>();
        for (String s : deviceDnsServers()) servers.add(s);
        servers.add("1.1.1.1");
        servers.add("8.8.8.8");
        servers.add("8.8.4.4");
        servers.add("9.9.9.9");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : servers) {
            if (n >= 4) break;
            sb.append("nameserver ").append(s).append('\n');
            n++;
        }
        writeFile(new File(rootfs, "etc/resolv.conf"), sb.toString());
    }

    private void postConfigure() throws IOException {
        File cacheDir = new File(rootfs, "var/cache/apt/archives");
        cacheDir.mkdirs();
        cacheDir.setReadable(true, false);
        cacheDir.setWritable(true, false);

        File certPem = new File(rootfs, "etc/ssl/cert.pem");
        File caFile = new File(rootfs, "etc/ssl/certs/ca-certificates.crt");
        if (caFile.isFile() && (!certPem.isFile() || certPem.isDirectory())) {
            try {
                certPem.delete();
                copyFile(caFile, certPem);
            } catch (Exception ignored) {
            }
        }

        // apt sources: use plain http (no certs needed for a base install) and a
        // classic sources.list. Remove the deb822 ubuntu.sources if present to
        // avoid duplicate-sources errors on noble and newer.
        File deb822 = new File(rootfs, "etc/apt/sources.list.d/ubuntu.sources");
        if (deb822.exists()) deb822.delete();
        File legacy = new File(rootfs, "etc/apt/sources.list");
        if (legacy.exists()) legacy.delete();
        StringBuilder sb = new StringBuilder();
        sb.append("deb http://archive.ubuntu.com/ubuntu/ ").append(UBUNTU_CODENAME)
                .append(" main restricted universe multiverse\n");
        sb.append("deb http://archive.ubuntu.com/ubuntu/ ").append(UBUNTU_CODENAME)
                .append("-updates main restricted universe multiverse\n");
        sb.append("deb http://archive.ubuntu.com/ubuntu/ ").append(UBUNTU_CODENAME)
                .append("-backports main restricted universe multiverse\n");
        sb.append("deb http://security.ubuntu.com/ubuntu/ ").append(UBUNTU_CODENAME)
                .append("-security main restricted universe multiverse\n");
        writeFile(legacy, sb.toString());
    }

    private String readFileTrim(File f) {
        try {
            List<String> lines = readLines(f);
            return lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private List<String> deviceDnsServers() {
        List<String> dns = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network net = cm.getActiveNetwork();
                    if (net != null) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null) {
                            for (InetAddress addr : lp.getDnsServers()) {
                                if (addr.getAddress().length != 4) continue;
                                String s = addr.getHostAddress();
                                int pct = s.indexOf('%');
                                if (pct > 0) s = s.substring(0, pct);
                                dns.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return dns;
    }

    private boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return fileOrDir.delete();
    }
}
