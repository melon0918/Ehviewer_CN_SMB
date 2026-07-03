/*
 * Copyright 2024 The Ehviewer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.unifile;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskShare;
import com.hippo.ehviewer.client.SmbConnectionManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SmbFile extends UniFile {

    private static final String TAG = SmbFile.class.getSimpleName();

    private static final long CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private static final Object sLock = new Object();
    private static SmbConnectionManager sSharedManager;
    private static String sConnectHost;
    private static String sConnectShare;
    private static String sConnectUsername;
    private static String sConnectPassword;
    private static File sTempDir;
    private static final Handler sCleanupHandler = new Handler(Looper.getMainLooper());

    /**
     * Set the directory for SMB temporary files (e.g. context.getCacheDir()).
     * If not set, system default temp directory is used.
     */
    public static void setTempDir(@NonNull File dir) {
        sTempDir = dir;
    }

    /**
     * Clean up leaked SMB temp files (files starting with "smb_").
     */
    public static void cleanTempFiles() {
        if (sTempDir == null) return;
        File[] files = sTempDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("smb_")) {
                f.delete();
            }
        }
    }

    private final String mHost;
    private final String mShare;
    private final String mPath;
    private final String mUsername;
    private final String mPassword;

    public SmbFile(@Nullable UniFile parent, @NonNull String host, @NonNull String share,
            @NonNull String path, @NonNull String username, @NonNull String password) {
        super(parent);
        mHost = host;
        mShare = share;
        mPath = normalizePath(path);
        mUsername = username;
        mPassword = password;
    }

    @NonNull
    private static String normalizePath(@NonNull String path) {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Nullable
    private static String getTypeForName(@NonNull String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    /** Relax StrictMode for the current thread to permit SMB network I/O. */
    private static StrictMode.ThreadPolicy relaxStrictMode() {
        StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(old).permitNetwork().build());
        return old;
    }

    @NonNull
    private DiskShare getShare() throws IOException {
        if (sSharedManager != null && sSharedManager.isConnected()
                && mHost.equals(sConnectHost) && mShare.equals(sConnectShare)
                && mUsername.equals(sConnectUsername) && mPassword.equals(sConnectPassword)) {
            DiskShare share = sSharedManager.getShare();
            if (share != null) {
                return share;
            }
        }
        // Swap to new connection; delay cleanup of old to avoid killing in-flight I/O
        final SmbConnectionManager oldManager = sSharedManager;
        sSharedManager = new SmbConnectionManager();
        DiskShare share = sSharedManager.connectFast(mHost, mShare, mUsername, mPassword);
        sConnectHost = mHost;
        sConnectShare = mShare;
        sConnectUsername = mUsername;
        sConnectPassword = mPassword;
        if (oldManager != null) {
            sCleanupHandler.postDelayed(() -> {
                synchronized (sLock) {
                    oldManager.disconnect();
                }
            }, 15000);
        }
        return share;
    }

    /**
     * Disconnect the shared SMB connection. Call when SMB is disabled or on app shutdown.
     */
    public static void disconnectShared() {
        synchronized (sLock) {
            if (sSharedManager != null) {
                sSharedManager.disconnect();
                sSharedManager = null;
            }
            sConnectHost = null;
            sConnectShare = null;
            sConnectUsername = null;
            sConnectPassword = null;
        }
    }

    @Nullable
    private String getParentPath() {
        if ("/".equals(mPath)) {
            return null;
        }
        int lastSlash = mPath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return mPath.substring(0, lastSlash);
    }

    @NonNull
    private String getChildPath(@NonNull String displayName) {
        if ("/".equals(mPath)) {
            return "/" + displayName;
        }
        return mPath + "/" + displayName;
    }

    @Override
    @Nullable
    public UniFile createFile(@NonNull String displayName) {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                final String childPath = getChildPath(displayName);
                try {
                    DiskShare share = getShare();
                    com.hierynomus.smbj.share.File file = share.openFile(childPath,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_SUPERSEDE,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    file.close();
                    return new SmbFile(this, mHost, mShare, childPath, mUsername, mPassword);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to createFile " + displayName + ": " + e.getMessage());
                    return null;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    @Nullable
    public UniFile createDirectory(@NonNull String displayName) {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                final String childPath = getChildPath(displayName);
                try {
                    DiskShare share = getShare();
                    share.mkdir(childPath);
                    return new SmbFile(this, mHost, mShare, childPath, mUsername, mPassword);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to createDirectory " + displayName + ": " + e.getMessage());
                    return null;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public Uri getUri() {
        return Uri.parse("smb://" + mHost + "/" + mShare + mPath);
    }

    @Nullable
    @Override
    public String getName() {
        if ("/".equals(mPath)) {
            return mShare;
        }
        final int lastSlash = mPath.lastIndexOf('/');
        return mPath.substring(lastSlash + 1);
    }

    @Nullable
    @Override
    public String getType() {
        if (isDirectory()) {
            return null;
        }
        final String name = getName();
        return name != null ? getTypeForName(name) : null;
    }

    @Override
    public boolean isDirectory() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    return getShare().folderExists(mPath);
                } catch (Exception e) {
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public boolean isFile() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    return getShare().fileExists(mPath);
                } catch (Exception e) {
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public long lastModified() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    FileBasicInformation info = getShare().getFileInformation(mPath, FileBasicInformation.class);
                    return info.getLastWriteTime().toEpochMillis();
                } catch (Exception e) {
                    return -1;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public long length() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    FileStandardInformation info = getShare().getFileInformation(mPath, FileStandardInformation.class);
                    return info.getEndOfFile();
                } catch (Exception e) {
                    return -1;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public boolean canRead() {
        return exists();
    }

    @Override
    public boolean canWrite() {
        return exists();
    }

    @Override
    public boolean ensureDir() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    if (getShare().folderExists(mPath)) {
                        return true;
                    }
                } catch (Exception e) {
                    return false;
                }
                final String parentPath = getParentPath();
                if (parentPath != null) {
                    final SmbFile parent = new SmbFile(getParentFile(), mHost, mShare, parentPath, mUsername, mPassword);
                    if (!parent.ensureDir()) {
                        return false;
                    }
                }
                try {
                    getShare().mkdir(mPath);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create directory " + mPath + ": " + e.getMessage());
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public boolean ensureFile() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    if (getShare().fileExists(mPath)) {
                        return true;
                    }
                } catch (Exception e) {
                    return false;
                }
                final String parentPath = getParentPath();
                if (parentPath != null) {
                    final SmbFile parent = new SmbFile(getParentFile(), mHost, mShare, parentPath, mUsername, mPassword);
                    parent.ensureDir();
                }
                try {
                    com.hierynomus.smbj.share.File file = getShare().openFile(mPath,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_SUPERSEDE,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    file.close();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public UniFile subFile(@NonNull String displayName) {
        return new SmbFile(this, mHost, mShare, getChildPath(displayName), mUsername, mPassword);
    }

    @Override
    public boolean delete() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    DiskShare share = getShare();
                    if (share.fileExists(mPath)) {
                        share.rm(mPath);
                        return true;
                    }
                    if (share.folderExists(mPath)) {
                        deleteContents(share, mPath);
                        share.rmdir(mPath, true);
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete " + mPath + ": " + e.getMessage());
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    private static void deleteContents(@NonNull DiskShare share, @NonNull String path) throws IOException {
        final List<FileIdBothDirectoryInformation> entries = share.list(path);
        if (entries == null) {
            return;
        }
        final long dirAttr = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();
        for (FileIdBothDirectoryInformation entry : entries) {
            final String name = entry.getFileName();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            final String childPath = path + "/" + name;
            if ((entry.getFileAttributes() & dirAttr) != 0) {
                deleteContents(share, childPath);
                try {
                    share.rmdir(childPath, true);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to delete directory " + childPath + ": " + e.getMessage());
                }
            } else {
                try {
                    share.rm(childPath);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to delete file " + childPath + ": " + e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean exists() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    DiskShare share = getShare();
                    return share.fileExists(mPath) || share.folderExists(mPath);
                } catch (Exception e) {
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Nullable
    @Override
    public UniFile[] listFiles() {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    final List<FileIdBothDirectoryInformation> entries = getShare().list(mPath);
                    if (entries == null) {
                        return null;
                    }
                    final List<UniFile> results = new ArrayList<>();
                    for (FileIdBothDirectoryInformation entry : entries) {
                        final String name = entry.getFileName();
                        if (".".equals(name) || "..".equals(name)) {
                            continue;
                        }
                        results.add(new SmbFile(this, mHost, mShare, getChildPath(name), mUsername, mPassword));
                    }
                    return results.toArray(new UniFile[0]);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to listFiles " + mPath + ": " + e.getMessage());
                    return null;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Nullable
    @Override
    public UniFile[] listFiles(@Nullable FilenameFilter filter) {
        if (filter == null) {
            return listFiles();
        }
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    final List<FileIdBothDirectoryInformation> entries = getShare().list(mPath);
                    if (entries == null) {
                        return null;
                    }
                    final List<UniFile> results = new ArrayList<>();
                    for (FileIdBothDirectoryInformation entry : entries) {
                        final String name = entry.getFileName();
                        if (".".equals(name) || "..".equals(name)) {
                            continue;
                        }
                        final SmbFile child = new SmbFile(this, mHost, mShare, getChildPath(name), mUsername, mPassword);
                        if (filter.accept(child, name)) {
                            results.add(child);
                        }
                    }
                    return results.toArray(new UniFile[0]);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to listFiles " + mPath + ": " + e.getMessage());
                    return null;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Nullable
    @Override
    public UniFile findFile(@NonNull String displayName) {
        final String childPath = getChildPath(displayName);
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    DiskShare share = getShare();
                    if (share.fileExists(childPath) || share.folderExists(childPath)) {
                        return new SmbFile(this, mHost, mShare, childPath, mUsername, mPassword);
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @Override
    public boolean renameTo(@NonNull String displayName) {
        final String parentPath = getParentPath();
        if (parentPath == null) {
            return false;
        }
        final String newPath = parentPath + "/" + displayName;
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                try {
                    DiskShare share = getShare();
                    com.hierynomus.smbj.share.File file = share.openFile(mPath,
                            EnumSet.of(AccessMask.GENERIC_ALL),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    file.rename(newPath);
                    file.close();
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to rename " + mPath + " to " + newPath + ": " + e.getMessage());
                    return false;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public OutputStream openOutputStream() throws IOException {
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            final DiskShare share;
            final com.hierynomus.smbj.share.File file;
            synchronized (sLock) {
                share = getShare();
                file = share.openFile(mPath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_SUPERSEDE,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            }
            final OutputStream delegate = file.getOutputStream();
            return new SmbFileOutputStream(delegate, file);
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public OutputStream openOutputStream(boolean append) throws IOException {
        if (!append) {
            return openOutputStream();
        }
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            final com.hierynomus.smbj.share.File file;
            synchronized (sLock) {
                DiskShare share = getShare();
                file = share.openFile(mPath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                        SMB2CreateDisposition.FILE_OPEN_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            }
            final OutputStream delegate = file.getOutputStream(true);
            return new SmbFileOutputStream(delegate, file);
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public InputStream openInputStream() throws IOException {
        final com.hierynomus.smbj.share.File file;
        final long fileSize;
        StrictMode.ThreadPolicy sp = relaxStrictMode();
        try {
            synchronized (sLock) {
                DiskShare share = getShare();
                file = share.openFile(mPath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                fileSize = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
            }
            // SMB streams cannot be cast to FileInputStream (required by Image.decode native)
            // Download to a temp file and return a FileInputStream
            File tempFile = sTempDir != null
                    ? File.createTempFile("smb_", ".img", sTempDir)
                    : File.createTempFile("smb_", ".img");
            try (InputStream smbIn = file.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                long remaining = fileSize;
                while (remaining > 0) {
                    int len = smbIn.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (len < 0) break;
                    fos.write(buf, 0, len);
                    remaining -= len;
                }
            }
            file.close();
            return new FileInputStream(tempFile) {
                @Override
                public void close() throws IOException {
                    super.close();
                    tempFile.delete();
                }
            };
        } finally {
            StrictMode.setThreadPolicy(sp);
        }
    }

    @NonNull
    @Override
    public UniRandomAccessFile createRandomAccessFile(@NonNull String mode) throws IOException {
        throw new IOException("Random access not supported for SMB files");
    }

    private static class SmbFileOutputStream extends OutputStream {
        private final OutputStream mDelegate;
        private final com.hierynomus.smbj.share.File mFile;
        private boolean mClosed;

        SmbFileOutputStream(@NonNull OutputStream delegate,
                            @NonNull com.hierynomus.smbj.share.File file) {
            mDelegate = delegate;
            mFile = file;
        }

        @Override
        public void write(int b) throws IOException { mDelegate.write(b); }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException { mDelegate.write(b, off, len); }

        @Override
        public void flush() throws IOException { mDelegate.flush(); }

        @Override
        public void close() throws IOException {
            if (!mClosed) {
                mClosed = true;
                mDelegate.close();
                StrictMode.ThreadPolicy sp = relaxStrictMode();
                try {
                    mFile.close();
                } finally {
                    StrictMode.setThreadPolicy(sp);
                }
            }
        }
    }

    private static class SmbFileInputStream extends InputStream {
        private final InputStream mDelegate;
        private final com.hierynomus.smbj.share.File mFile;
        private boolean mClosed;

        SmbFileInputStream(@NonNull InputStream delegate,
                           @NonNull com.hierynomus.smbj.share.File file) {
            mDelegate = delegate;
            mFile = file;
        }

        @Override
        public int read() throws IOException { return mDelegate.read(); }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException { return mDelegate.read(b, off, len); }

        @Override
        public int available() throws IOException { return mDelegate.available(); }

        @Override
        public void close() throws IOException {
            if (!mClosed) {
                mClosed = true;
                mDelegate.close();
                StrictMode.ThreadPolicy sp = relaxStrictMode();
                try {
                    mFile.close();
                } finally {
                    StrictMode.setThreadPolicy(sp);
                }
            }
        }
    }
}
