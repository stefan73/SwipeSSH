package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

public final class TerminalSession extends TerminalOutput {

    public interface SessionOutputWriter {
        void write(byte[] data, int offset, int count);
        void onResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
        void onSessionFinished();
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;
    TerminalSessionClient mClient;
    public String mSessionName;

    private final Integer mTranscriptRows;
    private final SessionOutputWriter mOutputWriter;
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private final byte[] mUtf8InputBuffer = new byte[5];
    private final ByteArrayOutputStream mPendingOutput = new ByteArrayOutputStream();

    private boolean mRunning = true;
    private int mExitStatus = 0;

    public TerminalSession(String sessionName, Integer transcriptRows, TerminalSessionClient client, SessionOutputWriter outputWriter) {
        this.mSessionName = sessionName;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mOutputWriter = outputWriter;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) {
            mEmulator.updateTerminalSessionClient(client);
        }
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            flushPendingOutput();
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }

        if (mOutputWriter != null) {
            mOutputWriter.onResize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public String getTitle() {
        return mEmulator == null ? null : mEmulator.getTitle();
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mRunning || mOutputWriter == null) {
            return;
        }
        mOutputWriter.write(data, offset, count);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) {
            mClient.onTextChanged(this);
        }
    }

    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    public void finishIfRunning() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        if (mOutputWriter != null) {
            mOutputWriter.onSessionFinished();
        }
        if (mClient != null) {
            mClient.onSessionFinished(this);
        }
    }

    public void appendIncoming(byte[] data, int length) {
        if (length <= 0) {
            return;
        }
        final byte[] copy = Arrays.copyOf(data, length);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendIncomingOnMainThread(copy);
        } else {
            mMainThreadHandler.post(() -> appendIncomingOnMainThread(copy));
        }
    }

    public void notifySessionFinished(String message, int exitStatus) {
        mExitStatus = exitStatus;
        mRunning = false;
        if (message != null && !message.isEmpty()) {
            appendIncoming(message.getBytes(), message.getBytes().length);
        }
        if (mClient != null) {
            mClient.onSessionFinished(this);
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) {
            mClient.onTitleChanged(this);
        }
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) {
            mClient.onCopyTextToClipboard(this, text);
        }
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) {
            mClient.onPasteTextFromClipboard(this);
        }
    }

    @Override
    public void onBell() {
        if (mClient != null) {
            mClient.onBell(this);
        }
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) {
            mClient.onColorsChanged(this);
        }
    }

    public int getPid() {
        return 0;
    }

    public String getCwd() {
        return null;
    }

    private void appendIncomingOnMainThread(byte[] data) {
        if (mEmulator == null) {
            mPendingOutput.write(data, 0, data.length);
            return;
        }

        mEmulator.append(data, data.length);
        notifyScreenUpdate();
    }

    private void flushPendingOutput() {
        byte[] pending = mPendingOutput.toByteArray();
        if (pending.length == 0 || mEmulator == null) {
            return;
        }
        mPendingOutput.reset();
        mEmulator.append(pending, pending.length);
        notifyScreenUpdate();
    }
}
