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
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SmbServerConfig;

import java.util.List;

public class SmbServerListActivity extends ToolbarActivity {

    private LinearLayout mServerContainer;
    private TextView mEmptyHint;
    private SwitchCompat mAutoSwitch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_server_list);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        mAutoSwitch = findViewById(R.id.auto_switch);
        mServerContainer = findViewById(R.id.server_container);
        mEmptyHint = findViewById(R.id.empty_hint);
        Button addBtn = findViewById(R.id.add_server);

        mAutoSwitch.setChecked(Settings.getSmbAutoSwitch());
        mAutoSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Settings.setSmbAutoSwitch(isChecked));

        addBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, SmbConfigActivity.class);
            startActivityForResult(intent, REQUEST_ADD_SERVER);
        });

        refreshServerList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            refreshServerList();
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

    private static final int REQUEST_ADD_SERVER = 1;
    private static final int REQUEST_EDIT_SERVER = 2;

    private void refreshServerList() {
        mServerContainer.removeAllViews();
        List<SmbServerConfig> servers = Settings.getSmbServers();

        if (servers.isEmpty()) {
            mEmptyHint.setVisibility(View.VISIBLE);
            return;
        }
        mEmptyHint.setVisibility(View.GONE);

        int activeIndex = Settings.getSmbActiveIndex();

        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            SmbServerConfig config = servers.get(i);
            boolean isActive = (i == activeIndex);

            // Server item container
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Top row: server info + active badge
            LinearLayout infoRow = new LinearLayout(this);
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setGravity(Gravity.CENTER_VERTICAL);
            infoRow.setPadding(8, 12, 8, 4);

            // Active indicator + server info (click to edit)
            TextView info = new TextView(this);
            String prefix = isActive ? "> " : "  ";
            info.setText(prefix + config.host + "/" + config.share);
            info.setTextSize(16);
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            info.setOnClickListener(v -> {
                Intent intent = new Intent(this, SmbConfigActivity.class);
                intent.putExtra(SmbConfigActivity.EXTRA_SERVER_INDEX, index);
                startActivityForResult(intent, REQUEST_EDIT_SERVER);
            });

            infoRow.addView(info);
            item.addView(infoRow);

            // Bottom row: action buttons
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);
            actionRow.setPadding(8, 0, 8, 8);

            if (!isActive) {
                Button activateBtn = new Button(this);
                activateBtn.setText(R.string.smb_active_server);
                activateBtn.setMinWidth(0);
                activateBtn.setMinimumWidth(0);
                activateBtn.setPadding(16, 4, 16, 4);
                activateBtn.setOnClickListener(v -> {
                    Settings.setSmbActiveIndex(index);
                    com.hippo.unifile.SmbFile.disconnectShared();
                    refreshServerList();
                });
                actionRow.addView(activateBtn);
            }

            Button editBtn = new Button(this);
            editBtn.setText(R.string.smb_edit_server);
            editBtn.setMinWidth(0);
            editBtn.setMinimumWidth(0);
            editBtn.setPadding(16, 4, 16, 4);
            editBtn.setOnClickListener(v -> {
                Intent intent = new Intent(this, SmbConfigActivity.class);
                intent.putExtra(SmbConfigActivity.EXTRA_SERVER_INDEX, index);
                startActivityForResult(intent, REQUEST_EDIT_SERVER);
            });

            Button deleteBtn = new Button(this);
            deleteBtn.setText(R.string.smb_delete_server);
            deleteBtn.setMinWidth(0);
            deleteBtn.setMinimumWidth(0);
            deleteBtn.setPadding(16, 4, 16, 4);
            deleteBtn.setOnClickListener(v -> showDeleteConfirmDialog(index));

            actionRow.addView(editBtn);
            actionRow.addView(deleteBtn);
            item.addView(actionRow);

            // Divider
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.LTGRAY);
            item.addView(divider);

            mServerContainer.addView(item);
        }
    }

    private void showDeleteConfirmDialog(int index) {
        List<SmbServerConfig> servers = Settings.getSmbServers();
        if (index < 0 || index >= servers.size()) return;

        String serverName = servers.get(index).host + "/" + servers.get(index).share;
        new AlertDialog.Builder(this)
                .setTitle(R.string.smb_delete_server)
                .setMessage(getString(R.string.smb_delete_server_confirm, serverName))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteServer(index))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteServer(int deleteIndex) {
        List<SmbServerConfig> servers = Settings.getSmbServers();
        if (deleteIndex < 0 || deleteIndex >= servers.size()) return;

        servers.remove(deleteIndex);

        // Re-index passwords: shift entries down
        for (int i = deleteIndex; i < servers.size(); i++) {
            String pw = Settings.getSmbPassword(i + 1);
            Settings.setSmbPassword(i, pw);
        }
        Settings.setSmbPassword(servers.size(), "");

        Settings.setSmbServers(servers);

        // Adjust active index if needed
        int activeIndex = Settings.getSmbActiveIndex();
        if (servers.isEmpty()) {
            Settings.setSmbEnabled(false);
            com.hippo.unifile.SmbFile.disconnectShared();
        } else if (activeIndex >= servers.size()) {
            Settings.setSmbActiveIndex(0);
            com.hippo.unifile.SmbFile.disconnectShared();
        } else if (activeIndex == deleteIndex) {
            // Deleted the active server; move to index 0 (if available)
            if (!servers.isEmpty()) {
                Settings.setSmbActiveIndex(0);
                com.hippo.unifile.SmbFile.disconnectShared();
            }
        }

        refreshServerList();
    }
}
