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

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * UriHandler for smb:// protocol URIs.
 * <p>
 * Parses smb:// URIs in the format: smb://host/share/path
 * Optionally with authentication: smb://username:password@host/share/path
 */
public class SmbUriHandler implements UriHandler {

    private static final String TAG = SmbUriHandler.class.getSimpleName();
    private static final String SMB_SCHEME = "smb";

    @Nullable
    @Override
    public UniFile fromUri(@NonNull Context context, @NonNull Uri uri) {
        if (!SMB_SCHEME.equals(uri.getScheme())) {
            return null;
        }

        final String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            Log.w(TAG, "Invalid smb URI: no host");
            return null;
        }

        final String userInfo = uri.getUserInfo();
        final String username;
        final String password;
        if (userInfo != null) {
            final int colonIndex = userInfo.indexOf(':');
            if (colonIndex >= 0) {
                username = Uri.decode(userInfo.substring(0, colonIndex));
                password = Uri.decode(userInfo.substring(colonIndex + 1));
            } else {
                username = Uri.decode(userInfo);
                password = "";
            }
        } else {
            username = "";
            password = "";
        }

        // Path segments: first is share name, rest is path within share
        final String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            Log.w(TAG, "Invalid smb URI: no share name");
            return null;
        }

        // The path is like /share/rest/of/path, first segment is share name
        final String normalizedPath = path.replace('\\', '/');
        final int secondSlash = normalizedPath.indexOf('/', 1);
        final String shareName;
        final String sharePath;
        if (secondSlash >= 0) {
            shareName = normalizedPath.substring(1, secondSlash);
            sharePath = normalizedPath.substring(secondSlash);
        } else {
            shareName = normalizedPath.substring(1);
            sharePath = "/";
        }

        if (shareName.isEmpty()) {
            Log.w(TAG, "Invalid smb URI: empty share name");
            return null;
        }

        return new SmbFile(null, host, shareName, sharePath, username, password);
    }
}
