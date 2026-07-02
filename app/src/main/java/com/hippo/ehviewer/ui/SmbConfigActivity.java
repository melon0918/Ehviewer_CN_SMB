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

package com.hippo.ehviewer.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputEditText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SmbConnectionManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class SmbConfigActivity extends ToolbarActivity {

    private TextInputEditText mHostInput;
    private TextInputEditText mShareInput;
    private TextInputEditText mUsernameInput;
    private TextInputEditText mPasswordInput;
    private Button mConnectBtn;
    private TextView mStatusText;
    private TextView mBreadcrumb;
    private LinearLayout mDirContainer;
    private View mButtonBar;
    private Button mSelectBtn;
    private Button mNewFolderBtn;

    private SmbConnectionManager mConnectionManager;
    private String mCurrentPath = "";
    private final List<String> mEntryNames = new ArrayList<>();
    private final List<Boolean> mEntryIsDir = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_config);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        mConnectionManager = new SmbConnectionManager();

        mHostInput = findViewById(R.id.host);
        mShareInput = findViewById(R.id.share);
        mUsernameInput = findViewById(R.id.username);
        mPasswordInput = findViewById(R.id.password);
        mConnectBtn = findViewById(R.id.connect);
        mStatusText = findViewById(R.id.status);
        mBreadcrumb = findViewById(R.id.breadcrumb);
        mDirContainer = findViewById(R.id.dir_container);
        mButtonBar = findViewById(R.id.button_bar);
        mSelectBtn = findViewById(R.id.select);
        mNewFolderBtn = findViewById(R.id.new_folder);

        // Load saved settings
        String savedHost = Settings.getSmbHost();
        if (savedHost != null) mHostInput.setText(savedHost);
        String savedShare = Settings.getSmbShare();
        if (savedShare != null) mShareInput.setText(savedShare);
        mUsernameInput.setText(Settings.getSmbUsername());
        mPasswordInput.setText(Settings.getSmbPassword());

        mConnectBtn.setOnClickListener(v -> connect());
        mSelectBtn.setOnClickListener(v -> selectCurrentDir());
        mNewFolderBtn.setOnClickListener(v -> showNewFolderDialog());

        // Show directory browser if already connected
        if (Settings.getSmbEnabled() && savedHost != null && savedShare != null) {
            String savedPath = Settings.getSmbPath();
            mCurrentPath = (savedPath == null || savedPath.isEmpty() || "/".equals(savedPath)) ? "" : savedPath;
            connect();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void connect() {
        String host = mHostInput.getText().toString().trim();
        String share = mShareInput.getText().toString().trim();
        String username = mUsernameInput.getText().toString().trim();
        String password = mPasswordInput.getText().toString();

        if (host.isEmpty()) {
            mHostInput.setError(getString(R.string.smb_error_required));
            return;
        }
        if (share.isEmpty()) {
            mShareInput.setError(getString(R.string.smb_error_required));
            return;
        }

        mConnectBtn.setEnabled(false);
        mStatusText.setText(R.string.smb_connecting);
        new ConnectTask(host, share, username, password).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showDirContent() {
        mBreadcrumb.setVisibility(View.VISIBLE);
        mBreadcrumb.setText(mCurrentPath.isEmpty() ? "/" : mCurrentPath);
        mButtonBar.setVisibility(View.VISIBLE);
        mStatusText.setText(R.string.smb_connected);
        loadDir();
    }

    private void loadDir() {
        new ListDirTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void selectCurrentDir() {
        String host = mHostInput.getText().toString().trim();
        String share = mShareInput.getText().toString().trim();
        String username = mUsernameInput.getText().toString().trim();
        String password = mPasswordInput.getText().toString();

        Settings.setSmbHost(host);
        Settings.setSmbShare(share);
        Settings.setSmbUsername(username);
        Settings.setSmbPassword(password);
        String savePath = mCurrentPath.isEmpty() ? "/" : "/" + mCurrentPath;
        Settings.setSmbPath(savePath);
        Settings.setSmbEnabled(true);

        Toast.makeText(this, R.string.smb_dir_selected, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent();
        intent.putExtra("smb_path", mCurrentPath);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void showNewFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.smb_new_folder);
        final TextInputEditText input = new TextInputEditText(this);
        builder.setView(input);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                new CreateFolderTask(name).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void populateDirView(List<String[]> entries) {
        mDirContainer.removeAllViews();
        if (entries == null) return;

        for (final String[] entry : entries) {
            final String name = entry[0];
            final boolean isDir = "dir".equals(entry[1]);

            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(18);
            tv.setTextColor(Color.BLACK);
            tv.setPadding(48, 24, 48, 24);
            tv.setBackgroundColor(Color.WHITE);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Add a divider
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.LTGRAY);

            if (isDir) {
                tv.setOnClickListener(v -> {
                    if ("..".equals(name)) {
                        int lastSlash = mCurrentPath.lastIndexOf('/');
                        mCurrentPath = lastSlash > 0 ? mCurrentPath.substring(0, lastSlash) : "";
                    } else {
                        mCurrentPath = mCurrentPath.isEmpty() ? name : mCurrentPath + "/" + name;
                    }
                    mBreadcrumb.setText(mCurrentPath.isEmpty() ? "/" : mCurrentPath);
                    loadDir();
                });
            }

            mDirContainer.addView(tv);
            mDirContainer.addView(divider);
        }
    }

    private class ConnectTask extends AsyncTask<Void, Void, Boolean> {
        private final String mHost;
        private final String mShare;
        private final String mUsername;
        private final String mPassword;
        private String mError;

        ConnectTask(String host, String share, String username, String password) {
            mHost = host;
            mShare = share;
            mUsername = username;
            mPassword = password;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                mConnectionManager.connect(mHost, mShare, mUsername, mPassword);
                return true;
            } catch (Exception e) {
                mError = e.getMessage();
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            mConnectBtn.setEnabled(true);
            if (success) {
                mCurrentPath = "";
                showDirContent();
            } else {
                mStatusText.setText(mError != null ? mError : getString(R.string.smb_connect_failed));
                Toast.makeText(SmbConfigActivity.this, R.string.smb_connect_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class ListDirTask extends AsyncTask<Void, Void, List<String[]>> {
        private String mError;

        @Override
        protected List<String[]> doInBackground(Void... voids) {
            List<String[]> result = new ArrayList<>();
            try {
                com.hierynomus.smbj.share.DiskShare share = mConnectionManager.getShare();
                if (share == null) {
                    mError = "share is null";
                    return null;
                }
                com.hierynomus.smbj.share.Directory dir = share.openDirectory(
                        mCurrentPath,
                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                        EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                        EnumSet.of(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ,
                                com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_WRITE),
                        com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_DIRECTORY_FILE));
                List<com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation> files = dir.list();
                dir.close();

                if (files == null) {
                    mError = "list returned null";
                    return null;
                }

                long dirAttr = com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();

                if (!mCurrentPath.isEmpty()) {
                    result.add(new String[]{"..", "dir"});
                }

                for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation f : files) {
                    String name = f.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    boolean isDir = (f.getFileAttributes() & dirAttr) != 0;
                    result.add(new String[]{name, isDir ? "dir" : "file"});
                }
                return result;
            } catch (Exception e) {
                mError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e("SmbConfig", "ListDirTask failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<String[]> entries) {
            if (entries != null) {
                populateDirView(entries);
            } else {
                mStatusText.setText(mError != null ? mError : getString(R.string.smb_list_failed));
            }
        }
    }

    private class CreateFolderTask extends AsyncTask<Void, Void, Boolean> {
        private final String mName;

        CreateFolderTask(String name) {
            mName = name;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                com.hierynomus.smbj.share.DiskShare share = mConnectionManager.getShare();
                if (share == null) return false;
                String newPath = mCurrentPath.isEmpty() ? mName : mCurrentPath + "/" + mName;
                share.mkdir(newPath);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                loadDir();
            } else {
                Toast.makeText(SmbConfigActivity.this, R.string.smb_create_folder_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConnectionManager != null) {
            mConnectionManager.disconnect();
        }
    }
}
