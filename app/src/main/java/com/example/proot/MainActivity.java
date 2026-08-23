package com.example.proot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import com.example.proot.databinding.ActivityMainBinding;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements Shell.Terminal, TerminalViewClient, TerminalSessionClient {

    private static final String TAG = "PRootTerminal";

    private ActivityMainBinding binding;
    private TerminalView termView;
    private TerminalSession currentSession;

    private LinuxManager linux;
    private final StringBuilder pendingOutput = new StringBuilder();
    private boolean started;

    private boolean controlToggled;
    private boolean altToggled;

    private boolean nativeReady;
    private boolean attachFailed;
    private boolean installing;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logCrash("Uncaught exception on " + t.getName(), e);
        });

        binding = ActivityMainBinding.inflate(getLayoutInflater());

        termView = binding.terminalView;
        termView.setTerminalViewClient(this);
        int fontSize = Math.round(14 * getResources().getDisplayMetrics().scaledDensity);
        termView.setTextSize(fontSize);
        try {
            termView.setTypeface(ResourcesCompat.getFont(this, R.font.jetbrains_mono));
        } catch (Throwable ignored) {
        }

        setContentView(binding.getRoot());

        linux = new LinuxManager(this, this);
        linux.setSetupProgress(new SetupProgress() {
            @Override
            public void onStage(String message) {
                setSetupStage(message);
            }

            @Override
            public void onProgress(long done, long total) {
                setSetupProgress(done, total);
            }
        });

        setupKeyButtons();

        // Wait for the first real layout before starting the PTY: during the first
        // onSizeChanged() pass mTermSession is null so updateSize() returns early and
        // no native call can crash the app before we have verified libtermux.so loads.
        termView.post(() -> {
            try {
                if (!started && !attachFailed) initTerminalAfterLayout();
            } catch (Throwable t) {
                logCrash("initTerminalAfterLayout", t);
            }
        });
    }

    private void initTerminalAfterLayout() {
        try {
            System.loadLibrary("termux");
        } catch (Throwable t) {
            logCrash("Failed loading libtermux.so", t);
            showTerminalError("Native terminal library failed to load:\n" + t);
            return;
        }
        nativeReady = true;
        started = true;
        if (linux.isInstalled()) {
            new Thread(this::runAutoStart, "auto-start").start();
        } else {
            startInstall();
        }
    }

    private void showTerminalError(String message) {
        attachFailed = true;
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.errorView.setText(message);
            binding.errorView.setVisibility(View.VISIBLE);
        });
    }

    // ---- Install screen (loading with progress bar) ----

    private void startInstall() {
        started = true;
        installing = true;
        attachFailed = false;
        pendingOutput.setLength(0);
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.setupRetry.setVisibility(View.GONE);
            binding.terminalView.setVisibility(View.GONE);
            binding.setupView.setVisibility(View.VISIBLE);
            setSetupStage("جاري التحميل...");
            binding.setupProgress.setIndeterminate(true);
            binding.setupPercent.setText("");
        });
        new Thread(this::runAutoStart, "auto-start").start();
    }

    private void setSetupStage(final String message) {
        mainHandler.post(() -> {
            if (binding == null || !installing) return;
            binding.setupStatus.setText(message);
            if (message.startsWith("Downloading")) {
                binding.setupProgress.setIndeterminate(false);
                binding.setupProgress.setProgress(0);
            } else if (!message.equals("Extracting rootfs")) {
                binding.setupProgress.setIndeterminate(true);
            }
        });
    }

    private void setSetupProgress(final long done, final long total) {
        mainHandler.post(() -> {
            if (binding == null || !installing) return;
            binding.setupProgress.setIndeterminate(false);
            if (total > 0) {
                int percent = (int) Math.min(100, done * 100 / total);
                binding.setupProgress.setProgress(percent);
                binding.setupPercent.setText(percent + "%");
            }
        });
    }

    private void showSetupError(final String message) {
        installing = false;
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.setupStatus.setText(message);
            binding.setupProgress.setIndeterminate(false);
            binding.setupRetry.setVisibility(View.VISIBLE);
        });
        new Thread(() -> {
            final String diag = linux.diagnose();
            mainHandler.post(() -> {
                if (binding == null || installing) return;
                binding.setupStatus.setText(message + "\n\n" + diag);
            });
        }, "diagnose").start();
    }

    private void logCrash(String where, Throwable t) {
        try {
            Log.e(TAG, where, t);
            File dir = getFilesDir();
            File out = new File(dir, "crash.log");
            try (FileOutputStream fos = new FileOutputStream(out, true);
                 PrintWriter pw = new PrintWriter(fos)) {
                pw.println("=== " + where + " @ " + System.currentTimeMillis() + " ===");
                t.printStackTrace(pw);
                pw.println();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Auto install / auto run ----

    private void runAutoStart() {
        try {
            if (!linux.isInstalled()) {
                linux.setup();
            }
            if (linux.isInstalled()) {
                linux.prepareForShell();
                if (installing) {
                    showSetupComplete();
                } else {
                    swapToLinux();
                }
            } else {
                if (installing) {
                    String reason = linux.getLastSetupError();
                    showSetupError(reason == null || reason.isEmpty()
                            ? "فشل التثبيت. تحقق من الاتصال وحاول مرة أخرى."
                            : "فشل التثبيت:\n" + reason);
                } else {
                    writeToTerminal("\nLinux setup did not complete.\nTap [Setup] to retry.\n");
                }
            }
        } catch (Throwable e) {
            logCrash("runAutoStart", e);
            if (installing) {
                showSetupError("فشل التثبيت: " + e.getMessage());
            } else {
                writeToTerminal("\n[error] " + e + "\n");
            }
        }
    }

    private void showSetupComplete() {
        installing = false;
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.setupView.setVisibility(View.GONE);
            binding.terminalView.setVisibility(View.VISIBLE);
            swapToLinux();
        });
    }

    private void swapToLinux() {
        mainHandler.post(() -> {
            try {
                if (currentSession != null) currentSession.finishIfRunning();

                File home = getFilesDir();
                List<String> args = new ArrayList<>();
                args.add(linux.getProotPath());
                args.add("-0");
                args.add("-r");
                args.add(linux.getRootfsPath());
                args.add("-b");
                args.add("/dev");
                args.add("-b");
                args.add("/proc");
                args.add("-b");
                args.add("/sys");
                args.add("-b");
                args.add(home.getAbsolutePath() + ":/host");
                args.add("/bin/sh");

                String[] env = {
                        "HOME=/root",
                        "TERM=xterm-256color",
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "PROOT_TMP_DIR=" + linux.getTmpPath()
                };

                currentSession = new TerminalSession(linux.getProotPath(), "/",
                        args.toArray(new String[0]), env, 1000, this);
                termView.attachSession(currentSession);
                termView.requestFocus();
            } catch (Throwable t) {
                logCrash("swapToLinux", t);
                currentSession = null;
                termView.mTermSession = null;
                termView.mEmulator = null;
                showTerminalError("Failed to start Linux shell:\n" + t);
            }
        });
    }

    // ---- Terminal output injection (from LinuxManager/Shell) ----

    private void writeToTerminal(final String text) {
        if (installing) return;
        mainHandler.post(() -> {
            TerminalEmulator em = currentSession == null ? null : currentSession.getEmulator();
            if (em == null) {
                pendingOutput.append(text);
                return;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            em.append(bytes, bytes.length);
            termView.onScreenUpdated();
        });
    }

    private void flushPendingOutput() {
        if (pendingOutput.length() == 0) return;
        TerminalEmulator em = currentSession == null ? null : currentSession.getEmulator();
        if (em == null) return;
        String s = pendingOutput.toString();
        pendingOutput.setLength(0);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        em.append(bytes, bytes.length);
        termView.onScreenUpdated();
    }

    // ---- Extra keys ----

    private void setupKeyButtons() {
        binding.keyEsc.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_ESCAPE, 0));
        binding.keyTab.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_TAB, 0));
        binding.keyUp.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_UP, 0));
        binding.keyDown.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, 0));
        binding.keyLeft.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, 0));
        binding.keyRight.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        binding.keyPipe.setOnClickListener(v -> sendText("|"));
        binding.keySlash.setOnClickListener(v -> sendText("/"));
        binding.keyDash.setOnClickListener(v -> sendText("-"));
        binding.keyCtrl.setOnClickListener(v -> toggleKey(binding.keyCtrl, 0));
        binding.keyAlt.setOnClickListener(v -> toggleKey(binding.keyAlt, 1));

        // The toolbar buttons are the Termux-style replacement of the old header pills.
        binding.toolbarLinux.setOnClickListener(v -> {
            hideToolbar();
            if (linux.isInstalled()) {
                swapToLinux();
            } else {
                writeToTerminal("\nLinux not installed yet. Tap [Setup] to install.\n");
            }
        });
        binding.toolbarSetup.setOnClickListener(v -> {
            hideToolbar();
            if (attachFailed) {
                binding.errorView.setVisibility(View.GONE);
                attachFailed = false;
                initTerminalAfterLayout();
            } else if (linux.isInstalled() && !installing) {
                swapToLinux();
            } else if (!installing) {
                startInstall();
            }
        });
        binding.toolbarClear.setOnClickListener(v -> {
            hideToolbar();
            clear();
        });

        binding.setupRetry.setOnClickListener(v -> startInstall());

        setupToolbarSwipe();
    }

    // ---- Toolbar (Termux style, revealed by swiping down from the top) ----

    private float toolbarDownY = -1;

    private final Runnable hideToolbarRunnable = this::hideToolbar;

    private void setupToolbarSwipe() {
        binding.toolbarStrip.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    toolbarDownY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (toolbarDownY > 0 && event.getRawY() - toolbarDownY > dp(24)) {
                        toolbarDownY = -1;
                        showToolbar();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    toolbarDownY = -1;
                    return true;
            }
            return false;
        });
    }

    private void showToolbar() {
        mainHandler.removeCallbacks(hideToolbarRunnable);
        if (binding.toolbar.getVisibility() == View.VISIBLE) {
            mainHandler.postDelayed(hideToolbarRunnable, 5000);
            return;
        }
        binding.toolbar.setVisibility(View.VISIBLE);
        binding.toolbar.animate().translationY(0).alpha(1f).setDuration(150).start();
        mainHandler.postDelayed(hideToolbarRunnable, 5000);
    }

    private void hideToolbar() {
        mainHandler.removeCallbacks(hideToolbarRunnable);
        if (binding == null || binding.toolbar.getVisibility() != View.VISIBLE) return;
        binding.toolbar.animate().translationY(-binding.toolbar.getHeight())
                .alpha(0f).setDuration(120).withEndAction(() -> {
                    if (binding != null) binding.toolbar.setVisibility(View.GONE);
                }).start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void sendKey(int keyCode, int keyMod) {
        if (termView != null && currentSession != null && currentSession.getEmulator() != null) {
            termView.handleKeyCode(keyCode, keyMod);
        }
    }

    private void sendText(String text) {
        if (currentSession != null) {
            currentSession.write(text);
        }
    }

    private void toggleKey(TextView key, int which) {
        if (which == 0) {
            controlToggled = !controlToggled;
        } else {
            altToggled = !altToggled;
        }
        styleToggle(key, which == 0 ? controlToggled : altToggled);
    }

    private void styleToggle(TextView key, boolean on) {
        key.setAlpha(on ? 1f : 0.45f);
        key.setTextColor(getResources().getColor(on ? R.color.terminal_green : R.color.terminal_fg));
    }

    // ---- Shell.Terminal (used by LinuxManager) ----

    @Override
    public void append(String text) {
        writeToTerminal(text);
    }

    @Override
    public void clear() {
        mainHandler.post(() -> {
            if (currentSession != null) {
                currentSession.write("\u000c");
            }
        });
    }

    @Override
    public void updatePrompt(String prompt) {
        // Prompt is drawn by the shell itself inside the terminal.
    }

    @Override
    public void finishApp() {
        mainHandler.post(this::finish);
    }

    // ---- TerminalSessionClient ----

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (termView != null) termView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (finishedSession == currentSession) {
            writeToTerminal("\r\n[Session ended. Tap Setup to restart.]\n");
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && currentSession != null) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null) currentSession.write(t.toString());
        }
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "stacktrace", e);
    }

    // ---- TerminalViewClient ----

    @Override
    public float onScale(float scale) {
        return Math.max(0.6f, Math.min(3f, scale));
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return true;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        if (controlToggled) {
            controlToggled = false;
            styleToggle(binding.keyCtrl, false);
            if (codePoint >= 'a' && codePoint <= 'z') {
                session.write(new byte[]{(byte) (codePoint - 'a' + 1)}, 0, 1);
                return true;
            }
            if (codePoint >= 'A' && codePoint <= 'Z') {
                session.write(new byte[]{(byte) (codePoint - 'A' + 1)}, 0, 1);
                return true;
            }
        }
        if (altToggled) {
            altToggled = false;
            styleToggle(binding.keyAlt, false);
            session.write("\u001b");
        }
        return false;
    }

    @Override
    public void onEmulatorSet() {
        flushPendingOutput();
    }

    // ---- Lifecycle ----

    private void showKeyboard() {
        if (termView != null) termView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(termView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (termView != null) termView.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentSession != null) currentSession.finishIfRunning();
        currentSession = null;
        binding = null;
        termView = null;
    }
}
