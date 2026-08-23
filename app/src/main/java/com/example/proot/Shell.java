package com.example.proot;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class Shell {

    public interface Terminal {
        void append(String text);
        void clear();
        void updatePrompt(String prompt);
        void finishApp();
    }

    public static final String USER = "proot";
    public static final String HOST = "android";

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "help", "ls", "cd", "pwd", "echo", "cat", "touch", "cp", "mv",
            "mkdir", "rm", "head", "tail", "wc", "grep", "find", "sort",
            "history", "whoami", "id", "hostname", "which", "uname", "date",
            "env", "sleep", "clear", "exit", "sh", "linux", "linux-setup", "linux-status",
            "linux-netcheck"));

    private final Terminal terminal;
    private final File home;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LinuxManager linux;

    private File cwd;
    private File prevDir;

    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    private String draft = "";

    public Shell(Terminal terminal, Context context) {
        this.terminal = terminal;
        this.home = context.getFilesDir();
        this.cwd = home;
        this.linux = new LinuxManager(context, terminal);
    }

    public File getCwd() {
        return cwd;
    }

    public void restoreCwd(File dir) {
        if (dir != null && dir.isDirectory()) {
            cwd = dir;
        }
    }

    public String getPrompt() {
        String path = cwd.getAbsolutePath();
        String homePath = home.getAbsolutePath();
        if (path.equals(homePath)) {
            path = "~";
        } else if (path.startsWith(homePath + "/")) {
            path = "~" + path.substring(homePath.length());
        }
        return USER + "@" + HOST + ":" + path + "$ ";
    }

    public void execute(final String rawInput) {
        executor.execute(() -> {
            try {
                if (linux.isShellActive()) {
                    linux.sendLine(rawInput);
                    return;
                }
                handleCommand(rawInput);
            } catch (Exception e) {
                terminal.append("[error] " + e + "\n");
            } finally {
                terminal.updatePrompt(getPrompt());
            }
        });
    }

    public void autoStart() {
        executor.execute(() -> {
            try {
                if (!linux.isInstalled()) {
                    terminal.append("No Linux system found. Installing automatically...\n");
                    linux.setup();
                }
                if (linux.isInstalled()) {
                    terminal.append("Starting Linux shell automatically...\n");
                    linux.startShell();
                } else {
                    terminal.append("Linux setup did not complete. "
                            + "Type 'linux-setup' to retry.\n");
                }
            } catch (Exception e) {
                terminal.append("[error] " + e + "\n");
            } finally {
                terminal.updatePrompt(getPrompt());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
        linux.close();
    }

    // ---- History (arrow keys) ----

    public String historyUp(String current) {
        if (history.isEmpty()) return current;
        if (historyIndex >= history.size()) {
            draft = current;
            historyIndex = history.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        return history.get(historyIndex);
    }

    public String historyDown() {
        if (history.isEmpty()) return "";
        if (historyIndex < history.size() - 1) {
            historyIndex++;
            return history.get(historyIndex);
        }
        historyIndex = history.size();
        return draft;
    }

    // ---- Command dispatch ----

    private void handleCommand(String input) {
        if (input.trim().isEmpty()) {
            terminal.append(getPrompt());
            return;
        }
        input = input.trim();
        history.add(input);
        historyIndex = history.size();
        draft = "";

        terminal.append(getPrompt() + input + "\n");

        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "help":       cmdHelp(); break;
            case "ls":         cmdLs(args); break;
            case "cd":         cmdCd(args); break;
            case "pwd":        terminal.append(cwd.getAbsolutePath() + "\n"); break;
            case "echo":       cmdEcho(args); break;
            case "cat":        cmdCat(args); break;
            case "touch":      cmdTouch(args); break;
            case "cp":         cmdCp(args); break;
            case "mv":         cmdMv(args); break;
            case "mkdir":      cmdMkdir(args); break;
            case "rm":         cmdRm(args); break;
            case "head":       cmdHead(args); break;
            case "tail":       cmdTail(args); break;
            case "wc":         cmdWc(args); break;
            case "grep":       cmdGrep(args); break;
            case "find":       cmdFind(args); break;
            case "sort":       cmdSort(args); break;
            case "history":    cmdHistory(); break;
            case "whoami":     terminal.append(USER + "\n"); break;
            case "id":         cmdId(); break;
            case "hostname":   terminal.append(HOST + "\n"); break;
            case "which":      cmdWhich(args); break;
            case "uname":      cmdUname(args); break;
            case "date":       cmdDate(args); break;
            case "env":        cmdEnv(); break;
            case "sleep":      cmdSleep(args); break;
            case "clear":      terminal.clear(); break;
            case "exit":       terminal.append("Closing terminal...\n"); terminal.finishApp(); break;
            case "sh":
            case "linux":      cmdLinux(args); break;
            case "linux-setup": cmdLinuxSetup(); break;
            case "linux-status": terminal.append(linux.status() + "\n"); break;
            case "linux-netcheck": cmdLinuxNetCheck(); break;
            default:           runExternal(command, args); break;
        }
    }

    private void cmdHelp() {
        terminal.append(
            "Built-in commands:\n" +
            "  help       - Show this help message\n" +
            "  ls [-a|-l] - List directory contents\n" +
            "  cd [-|~|dir] - Change directory\n" +
            "  pwd        - Print working directory\n" +
            "  echo       - Print text\n" +
            "  cat        - Read file contents\n" +
            "  touch      - Create/update file\n" +
            "  cp [-r]    - Copy file or directory\n" +
            "  mv         - Move/rename file or directory\n" +
            "  mkdir [-p] - Create directory\n" +
            "  rm [-r|-f] - Remove file or directory\n" +
            "  head/tail  - Print first/last lines\n" +
            "  wc [-l|-w|-c] - Count lines/words/bytes\n" +
            "  grep [-i|-r|-n] - Search text\n" +
            "  find [-name PAT] - Find files\n" +
            "  sort [-r]  - Sort lines\n" +
            "  which      - Locate a command\n" +
            "  history    - Command history\n" +
            "  uname/date/env/id/sleep/clear/exit\n" +
            "Linux (PRoot):\n" +
            "  linux-setup - Install Ubuntu Linux (~30 MB)\n" +
            "  linux       - Enter Ubuntu shell (real Linux)\n" +
            "  linux CMD   - Run one command inside Linux\n" +
            "  linux-status - Show install status\n" +
            "  linux-netcheck - Diagnose network inside Linux\n");
    }

    // ---- File commands ----

    private void cmdLs(String args) {
        boolean all = false, list = false;
        List<String> paths = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("-") && t.length() > 1) {
                for (char c : t.substring(1).toCharArray()) {
                    if (c == 'a') all = true;
                    else if (c == 'l') list = true;
                }
            } else {
                paths.add(t);
            }
        }
        if (paths.isEmpty()) paths.add(".");
        boolean multi = paths.size() > 1;
        for (String p : paths) {
            File f = resolvePath(p);
            if (!f.exists()) {
                terminal.append("ls: " + p + ": No such file or directory\n");
                continue;
            }
            if (multi) terminal.append(p + ":\n");
            if (!f.isDirectory()) {
                terminal.append(f.getName() + "\n");
                continue;
            }
            File[] files = f.listFiles();
            if (files == null) continue;
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File x : sorted) {
                if (!all && x.isHidden()) continue;
                terminal.append(list ? lsLong(x) + "\n" : x.getName() + (x.isDirectory() ? "/" : "") + "\n");
            }
        }
    }

    private String lsLong(File f) {
        char type = f.isDirectory() ? 'd' : f.isFile() ? '-' : 'l';
        String r = f.canRead() ? "r" : "-";
        String w = f.canWrite() ? "w" : "-";
        String x = f.canExecute() ? "x" : "-";
        String perms = String.valueOf(type) + r + w + x + r + w + x + r + w + x;
        String size = f.isDirectory() ? "-" : String.valueOf(f.length());
        return String.format(Locale.US, "%s %10s  %s", perms, size, f.getName() + (f.isDirectory() ? "/" : ""));
    }

    private void cmdCd(String args) {
        String target = args.trim();
        if (target.equals("-")) {
            if (prevDir == null) {
                terminal.append("cd: OLDPWD not set\n");
                return;
            }
            File t = prevDir;
            prevDir = cwd;
            cwd = t;
            terminal.append(cwd.getAbsolutePath() + "\n");
            return;
        }
        File next = target.isEmpty() ? home : resolvePath(target);
        if (!next.exists()) {
            terminal.append("cd: " + target + ": No such file or directory\n");
            return;
        }
        if (!next.isDirectory()) {
            terminal.append("cd: " + target + ": Not a directory\n");
            return;
        }
        prevDir = cwd;
        cwd = next;
    }

    private void cmdEcho(String args) {
        boolean newline = true;
        String s = args;
        if (s.startsWith("-n ") || s.equals("-n")) { newline = false; s = s.equals("-n") ? "" : s.substring(3); }
        terminal.append(s + (newline ? "\n" : ""));
    }

    private void cmdCat(String args) {
        List<String> files = tokenize(args);
        if (files.isEmpty()) { terminal.append("cat: missing operand\n"); return; }
        for (String fn : files) {
            File f = resolvePath(fn);
            if (!f.exists()) { terminal.append("cat: " + fn + ": No such file or directory\n"); continue; }
            if (f.isDirectory()) { terminal.append("cat: " + fn + ": Is a directory\n"); continue; }
            try {
                byte[] data = readAll(f, 1_000_000);
                boolean binary = false;
                for (byte b : data) if (b == 0) { binary = true; break; }
                if (binary) terminal.append("cat: " + fn + ": binary file\n");
                else terminal.append(new String(data, StandardCharsets.UTF_8) + "\n");
            } catch (Exception e) {
                terminal.append("cat: " + fn + ": " + e.getMessage() + "\n");
            }
        }
    }

    private void cmdTouch(String args) {
        List<String> files = tokenize(args);
        if (files.isEmpty()) { terminal.append("touch: missing file operand\n"); return; }
        for (String fn : files) {
            File f = resolvePath(fn);
            try {
                if (!f.exists()) {
                    mkdirsParent(f);
                    f.createNewFile();
                }
                f.setLastModified(System.currentTimeMillis());
            } catch (Exception e) {
                terminal.append("touch: " + fn + ": " + e.getMessage() + "\n");
            }
        }
    }

    private void cmdCp(String args) {
        List<String> toks = tokenize(args);
        boolean recursive = toks.contains("-r");
        toks.remove("-r");
        if (toks.size() < 2) { terminal.append("cp: missing file operand\n"); return; }
        File src = resolvePath(toks.get(0));
        File dst = resolvePath(toks.get(1));
        if (!src.exists()) { terminal.append("cp: " + toks.get(0) + ": No such file or directory\n"); return; }
        if (src.isDirectory() && !recursive) { terminal.append("cp: omitting directory '" + toks.get(0) + "'\n"); return; }
        try {
            if (dst.isDirectory()) dst = new File(dst, src.getName());
            if (src.isDirectory()) copyRecursive(src, dst);
            else copyFile(src, dst);
        } catch (IOException e) {
            terminal.append("cp: " + e.getMessage() + "\n");
        }
    }

    private void cmdMv(String args) {
        List<String> toks = tokenize(args);
        if (toks.size() < 2) { terminal.append("mv: missing file operand\n"); return; }
        File src = resolvePath(toks.get(0));
        File dst = resolvePath(toks.get(1));
        if (!src.exists()) { terminal.append("mv: " + toks.get(0) + ": No such file or directory\n"); return; }
        if (dst.isDirectory()) dst = new File(dst, src.getName());
        try {
            if (!src.renameTo(dst)) {
                if (src.isDirectory()) copyRecursive(src, dst); else copyFile(src, dst);
                deleteRecursive(src);
            }
        } catch (IOException e) {
            terminal.append("mv: " + e.getMessage() + "\n");
        }
    }

    private void cmdMkdir(String args) {
        boolean p = false;
        List<String> dirs = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.equals("-p")) p = true;
            else if (!t.isEmpty()) dirs.add(t);
        }
        if (dirs.isEmpty()) { terminal.append("mkdir: missing operand\n"); return; }
        for (String dn : dirs) {
            File d = resolvePath(dn);
            if (d.exists()) { terminal.append("mkdir: " + dn + ": File exists\n"); continue; }
            if (!(p ? d.mkdirs() : d.mkdir())) {
                terminal.append("mkdir: " + dn + ": Cannot create directory\n");
            }
        }
    }

    private void cmdRm(String args) {
        boolean recursive = false, force = false;
        List<String> targets = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("-") && t.length() > 1) {
                for (char c : t.substring(1).toCharArray()) {
                    if (c == 'r' || c == 'd') recursive = true;
                    if (c == 'f') force = true;
                }
            } else {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) { terminal.append("rm: missing operand\n"); return; }
        for (String tn : targets) {
            File f = resolvePath(tn);
            if (!f.exists()) { if (!force) terminal.append("rm: " + tn + ": No such file or directory\n"); continue; }
            if (f.isDirectory() && !recursive) { terminal.append("rm: " + tn + ": Is a directory\n"); continue; }
            boolean ok = f.isDirectory() ? deleteRecursive(f) : f.delete();
            if (!ok && !force) terminal.append("rm: " + tn + ": Cannot remove\n");
        }
    }

    private void cmdHead(String args) {
        int n = 10;
        String file = null;
        String[] parts = args.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (parts[i].equals("-n") && i + 1 < parts.length) n = parseInt(parts[++i], 10);
            else if (parts[i].startsWith("-n") && parts[i].length() > 2) n = parseInt(parts[i].substring(2), 10);
            else file = parts[i];
        }
        if (file == null) { terminal.append("head: missing file operand\n"); return; }
        File f = resolvePath(file);
        if (!f.exists()) { terminal.append("head: " + file + ": No such file or directory\n"); return; }
        try {
            List<String> lines = readLines(f);
            for (int i = 0; i < Math.min(n, lines.size()); i++) terminal.append(lines.get(i) + "\n");
        } catch (Exception e) {
            terminal.append("head: " + e.getMessage() + "\n");
        }
    }

    private void cmdTail(String args) {
        int n = 10;
        String file = null;
        String[] parts = args.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (parts[i].equals("-n") && i + 1 < parts.length) n = parseInt(parts[++i], 10);
            else if (parts[i].startsWith("-n") && parts[i].length() > 2) n = parseInt(parts[i].substring(2), 10);
            else file = parts[i];
        }
        if (file == null) { terminal.append("tail: missing file operand\n"); return; }
        File f = resolvePath(file);
        if (!f.exists()) { terminal.append("tail: " + file + ": No such file or directory\n"); return; }
        try {
            List<String> lines = readLines(f);
            for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) terminal.append(lines.get(i) + "\n");
        } catch (Exception e) {
            terminal.append("tail: " + e.getMessage() + "\n");
        }
    }

    private void cmdWc(String args) {
        boolean countL = false, countW = false, countC = false;
        boolean any = false;
        List<String> files = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("-") && t.length() > 1) {
                for (char ch : t.substring(1).toCharArray()) {
                    if (ch == 'l') { countL = true; any = true; }
                    if (ch == 'w') { countW = true; any = true; }
                    if (ch == 'c') { countC = true; any = true; }
                }
            } else {
                files.add(t);
            }
        }
        if (!any) { countL = countW = countC = true; }
        if (files.isEmpty()) { terminal.append("wc: missing file operand\n"); return; }
        long tL = 0, tW = 0, tC = 0;
        boolean multi = files.size() > 1;
        for (String fn : files) {
            File f = resolvePath(fn);
            if (!f.exists()) { terminal.append("wc: " + fn + ": No such file or directory\n"); continue; }
            try {
                String content = new String(readAll(f, Integer.MAX_VALUE), StandardCharsets.UTF_8);
                long lines = content.isEmpty() ? 0 : content.split("\n", -1).length - 1;
                long words = countWords(content);
                long chars = content.length();
                tL += lines; tW += words; tC += chars;
                StringBuilder sb = new StringBuilder();
                if (countL) sb.append(lines).append("\t");
                if (countW) sb.append(words).append("\t");
                if (countC) sb.append(chars).append("\t");
                sb.append(fn).append("\n");
                terminal.append(sb.toString());
            } catch (Exception e) {
                terminal.append("wc: " + e.getMessage() + "\n");
            }
        }
        if (multi) {
            StringBuilder sb = new StringBuilder();
            if (countL) sb.append(tL).append("\t");
            if (countW) sb.append(tW).append("\t");
            if (countC) sb.append(tC).append("\t");
            sb.append("total\n");
            terminal.append(sb.toString());
        }
    }

    private void cmdGrep(String args) {
        boolean ignore = false, recursive = false, number = false;
        List<String> rest = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("-") && t.length() > 1 && !t.equals("--")) {
                for (char ch : t.substring(1).toCharArray()) {
                    if (ch == 'i') ignore = true;
                    if (ch == 'r') recursive = true;
                    if (ch == 'n') number = true;
                }
            } else {
                rest.add(t);
            }
        }
        if (rest.isEmpty()) { terminal.append("grep: missing pattern\n"); return; }
        String pattern = rest.remove(0);
        Pattern regex;
        try {
            regex = Pattern.compile(pattern, ignore ? Pattern.CASE_INSENSITIVE : 0);
        } catch (Exception e) {
            regex = Pattern.compile(Pattern.quote(pattern), ignore ? Pattern.CASE_INSENSITIVE : 0);
        }
        List<String> targets = rest.isEmpty() ? Collections.singletonList(".") : rest;
        boolean showName = targets.size() > 1 || recursive;
        for (String t : targets) {
            File f = resolvePath(t);
            if (!f.exists()) { terminal.append("grep: " + t + ": No such file or directory\n"); continue; }
            List<File> scan = new ArrayList<>();
            if (f.isDirectory()) {
                if (!recursive) { terminal.append("grep: " + t + ": Is a directory\n"); continue; }
                collectFiles(f, scan);
            } else {
                scan.add(f);
            }
            for (File file : scan) {
                try {
                    List<String> lines = readLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (regex.matcher(lines.get(i)).find()) {
                            StringBuilder sb = new StringBuilder();
                            if (showName) sb.append(file.getName()).append(":");
                            if (number) sb.append(i + 1).append(":");
                            sb.append(lines.get(i)).append("\n");
                            terminal.append(sb.toString());
                        }
                    }
                } catch (Exception e) {
                    terminal.append("grep: " + file.getName() + ": " + e.getMessage() + "\n");
                }
            }
        }
    }

    private void cmdFind(String args) {
        String start = ".";
        String namePat = null;
        Character type = null;
        List<String> parts = tokenize(args);
        for (int i = 0; i < parts.size(); i++) {
            String t = parts.get(i);
            if (t.equals("-name") && i + 1 < parts.size()) namePat = parts.get(++i);
            else if (t.equals("-type") && i + 1 < parts.size()) type = parts.get(++i).charAt(0);
            else if (!t.startsWith("-")) start = t;
        }
        File root = resolvePath(start);
        if (!root.exists()) { terminal.append("find: " + start + ": No such file or directory\n"); return; }
        walkFind(root, "", namePat, type);
    }

    private void walkFind(File dir, String rel, String namePat, Character type) {
        File[] children = dir.isDirectory() ? dir.listFiles() : null;
        if (children == null) return;
        for (File c : children) {
            String path = rel.isEmpty() ? c.getName() : rel + "/" + c.getName();
            if (nameMatches(namePat, c.getName()) && typeMatches(c, type)) {
                terminal.append(path + (c.isDirectory() ? "/" : "") + "\n");
            }
            if (c.isDirectory()) walkFind(c, path, namePat, type);
        }
    }

    private boolean nameMatches(String pat, String name) {
        if (pat == null) return true;
        String regex = "\\Q" + pat.replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "\\E";
        return name.matches(regex);
    }

    private boolean typeMatches(File f, Character type) {
        if (type == null) return true;
        switch (type) {
            case 'd': return f.isDirectory();
            case 'f': return f.isFile();
            case 'l': return !f.isFile() && !f.isDirectory();
            default: return true;
        }
    }

    private void cmdSort(String args) {
        boolean reverse = false;
        List<String> files = new ArrayList<>();
        for (String t : args.split("\\s+")) {
            if (t.equals("-r")) reverse = true;
            else if (t.startsWith("-") || t.isEmpty()) { /* ignore other flags */ }
            else files.add(t);
        }
        if (files.isEmpty()) { terminal.append("sort: missing file operand\n"); return; }
        List<String> lines = new ArrayList<>();
        for (String fn : files) {
            File f = resolvePath(fn);
            if (!f.exists()) { terminal.append("sort: " + fn + ": No such file or directory\n"); continue; }
            try {
                lines.addAll(readLines(f));
            } catch (Exception e) {
                terminal.append("sort: " + e.getMessage() + "\n");
            }
        }
        Collections.sort(lines);
        if (reverse) Collections.reverse(lines);
        for (String l : lines) terminal.append(l + "\n");
    }

    private void cmdHistory() {
        if (history.isEmpty()) { terminal.append("(empty)\n"); return; }
        for (int i = 0; i < history.size(); i++) {
            terminal.append(String.format(Locale.US, "%4d  %s%n", i + 1, history.get(i)));
        }
    }

    private void cmdId() {
        terminal.append("uid=10000(" + USER + ") gid=10000(" + USER + ") groups=10000,3003\n");
    }

    private void cmdWhich(String args) {
        String name = args.trim();
        if (name.isEmpty()) { terminal.append("which: missing operand\n"); return; }
        if (BUILTINS.contains(name)) { terminal.append(name + " (shell built-in)\n"); return; }
        if (linux.isInstalled()) {
            terminal.append(name + " (Linux environment)\n");
            return;
        }
        File f = resolvePath(name);
        if (f.exists() && !f.isDirectory()) terminal.append(f.getAbsolutePath() + "\n");
        else terminal.append(name + ": not found\n");
    }

    private void cmdUname(String args) {
        String s = args.trim();
        String sys = "Linux";
        String node = HOST;
        String rel = System.getProperty("os.version", "");
        String machine = machineName();
        if (s.isEmpty() || s.contains("a")) {
            terminal.append(sys + " " + node + " " + rel + " " + machine + " GNU/Linux\n");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (s.contains("s")) sb.append(sys).append(" ");
        if (s.contains("n")) sb.append(node).append(" ");
        if (s.contains("r")) sb.append(rel).append(" ");
        if (s.contains("m")) sb.append(machine).append(" ");
        if (s.contains("o")) sb.append("GNU/Linux ");
        terminal.append(sb.toString().trim() + "\n");
    }

    private String machineName() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            String abi = Build.SUPPORTED_ABIS[0];
            switch (abi) {
                case "arm64-v8a": return "aarch64";
                case "x86_64": return "x86_64";
                case "armeabi-v7a": return "armv7l";
                case "x86": return "i686";
                default: return abi;
            }
        }
        return "unknown";
    }

    private void cmdDate(String args) {
        Date d = new Date();
        if (args.contains("-u")) {
            terminal.append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US).format(d) + " UTC\n");
        } else {
            terminal.append(DateFormat.getDateTimeInstance().format(d) + "\n");
        }
    }

    private void cmdEnv() {
        List<String> keys = new ArrayList<>(System.getenv().keySet());
        Collections.sort(keys);
        for (String k : keys) {
            terminal.append(k + "=" + System.getenv(k) + "\n");
        }
    }

    private void cmdSleep(String args) {
        double secs = parseDouble(args.trim(), 1.0);
        if (secs < 0) secs = 0;
        if (secs > 600) secs = 600;
        try {
            Thread.sleep((long) (secs * 1000));
        } catch (InterruptedException ignored) {
        }
    }

    private void cmdLinux(String args) {
        if (args.trim().isEmpty()) {
            linux.startShell();
        } else {
            linux.runCommand(args.trim());
        }
    }

    private void cmdLinuxSetup() {
        terminal.append("Starting Linux setup...\n");
        linux.setup();
    }

    private void cmdLinuxNetCheck() {
        terminal.append("Running network diagnostics inside Linux...\n");
        linux.runCommand(
            "echo '--- resolv.conf ---'; cat /etc/resolv.conf; "
            + "echo; echo '--- sources ---'; cat /etc/apt/sources.list; "
            + "echo; echo '--- DNS lookup ---'; nslookup archive.ubuntu.com 2>&1 | head -8; "
            + "echo; echo '--- HTTP fetch test ---'; "
            + "wget -T 10 -O /dev/null http://archive.ubuntu.com/ubuntu/dists/noble/Release; "
            + "echo \"wget rc=$?\"; "
            + "echo '--- HTTPS fetch test ---'; "
            + "wget -T 10 -O /dev/null https://archive.ubuntu.com/ubuntu/dists/noble/Release; "
            + "echo \"wget rc=$?\"");
    }

    private void runExternal(String command, String args) {
        File f = resolvePath(command);
        if (f.exists() && !f.isDirectory()) {
            runBinary(f, args);
        } else if (linux.isInstalled()) {
            linux.runCommand(args.isEmpty() ? command : command + " " + args);
        } else {
            terminal.append(command + ": command not found. Type 'help'. Install a Linux environment with 'linux-setup'.\n");
        }
    }

    private void runBinary(File bin, String args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            if (!args.isEmpty()) cmd.addAll(tokenize(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) terminal.append(line + "\n");
            int rc = p.waitFor();
            if (rc != 0) terminal.append("[exit code: " + rc + "]\n");
        } catch (Exception e) {
            terminal.append(bin.getName() + ": " + e.getMessage() + "\n");
        }
    }

    // ---- Helpers ----

    private File resolvePath(String path) {
        if (path.equals("~")) return home;
        if (path.startsWith("~/")) return new File(home, path.substring(2));
        if (path.startsWith("/")) return new File(path);
        return new File(cwd, path);
    }

    private void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) collectFiles(c, out);
            else out.add(c);
        }
    }

    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quote = ' ';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    private byte[] readAll(File f, int cap) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            int len = (int) Math.min(f.length(), cap);
            byte[] buf = new byte[len];
            int off = 0, n;
            while (off < len && (n = in.read(buf, off, len - off)) != -1) off += n;
            if (off < len) {
                byte[] t = new byte[off];
                System.arraycopy(buf, 0, t, 0, off);
                return t;
            }
            return buf;
        }
    }

    private long countWords(String s) {
        long words = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) inWord = false;
            else if (!inWord) { inWord = true; words++; }
        }
        return words;
    }

    private void copyFile(File src, File dst) throws IOException {
        mkdirsParent(dst);
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("cannot create directory " + dst);
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyRecursive(c, new File(dst, c.getName()));
            }
        } else {
            copyFile(src, dst);
        }
    }

    private void mkdirsParent(File f) {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
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

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
}
