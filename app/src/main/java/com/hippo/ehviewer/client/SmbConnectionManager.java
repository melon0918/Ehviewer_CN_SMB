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

package com.hippo.ehviewer.client;

import android.util.Log;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.share.DiskShare;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Manages SMB connection lifecycle.
 * <p>
 * Provides single-connection management for SMB server access.
 * All methods are synchronized as DiskShare is not thread-safe.
 */
public class SmbConnectionManager {

    private static final String TAG = SmbConnectionManager.class.getSimpleName();

    private static final int MAX_RETRIES = 3;
    private static final long CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private SMBClient mClient;
    private com.hierynomus.smbj.connection.Connection mConnection;
    private com.hierynomus.smbj.session.Session mSession;
    private DiskShare mShare;

    /**
     * Connect to an SMB share.
     *
     * @param host     Server hostname or IP
     * @param share    Share name
     * @param username Username (can be empty for guest)
     * @param password Password (can be empty for guest)
     * @return The connected DiskShare
     * @throws IOException on connection failure after all retries
     */
    public synchronized DiskShare connect(String host, String share, String username, String password)
            throws IOException {
        disconnect();

        IOException lastException;
        long backoff = 1000;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                SmbConfig config = SmbConfig.builder()
                        .withTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .withSoTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build();
                mClient = new SMBClient(config);
                mConnection = mClient.connect(host, 445);
                AuthenticationContext auth = new AuthenticationContext(username, password.toCharArray(), "");
                mSession = mConnection.authenticate(auth);
                mShare = (DiskShare) mSession.connectShare(share);
                Log.i(TAG, "Connected to " + host + "/" + share);
                return mShare;
            } catch (IOException e) {
                lastException = e;
                Log.w(TAG, "Connection attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                disconnect();
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                    backoff *= 2;
                } else {
                    throw lastException;
                }
            }
        }

        throw new IOException("Unexpected: all retries exhausted without result");
    }

    /**
     * Disconnect and release all resources.
     */
    public synchronized void disconnect() {
        closeQuietly(mShare);
        mShare = null;
        closeQuietly(mSession);
        mSession = null;
        closeQuietly(mConnection);
        mConnection = null;
        closeQuietly(mClient);
        mClient = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close: " + e.getMessage());
            }
        }
    }

    /**
     * Check if connected to the SMB server.
     */
    public synchronized boolean isConnected() {
        return mShare != null && mShare.isConnected();
    }

    /**
     * Get the current DiskShare.
     *
     * @return the current DiskShare, or null if not connected
     */
    public synchronized DiskShare getShare() {
        return mShare;
    }
}
