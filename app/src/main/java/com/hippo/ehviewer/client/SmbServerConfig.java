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

import com.alibaba.fastjson.JSONObject;

/**
 * Represents a single SMB server configuration.
 * <p>
 * Non-sensitive fields (host, share, path, username) are serialized
 * as JSON for storage in SharedPreferences. Password is stored separately
 * in EncryptedSharedPreferences, keyed by server index.
 */
public class SmbServerConfig {

    public String host;
    public String share;
    public String path = "/";
    public String username = "";
    // Password is NOT included in JSON serialization.
    // It lives in EncryptedSharedPreferences (smb_password_N).
    public String password = "";

    public SmbServerConfig() {
    }

    public SmbServerConfig(String host, String share, String path, String username, String password) {
        this.host = host;
        this.share = share;
        this.path = path;
        this.username = username;
        this.password = password;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("host", host);
        obj.put("share", share);
        obj.put("path", path);
        obj.put("username", username);
        return obj;
    }

    public static SmbServerConfig fromJson(JSONObject obj) {
        SmbServerConfig config = new SmbServerConfig();
        config.host = obj.getString("host");
        config.share = obj.getString("share");
        if (obj.containsKey("path")) {
            config.path = obj.getString("path");
        }
        if (obj.containsKey("username")) {
            config.username = obj.getString("username");
        }
        return config;
    }
}
