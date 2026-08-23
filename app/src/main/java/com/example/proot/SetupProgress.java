package com.example.proot;

/** Reports setup progress (stages and byte-level download/extraction progress). */
public interface SetupProgress {

    /** Called when the current stage changes, e.g. "Downloading rootfs". */
    void onStage(String message);

    /** Called with bytes completed / total bytes. total <= 0 means indeterminate. */
    void onProgress(long done, long total);
}
