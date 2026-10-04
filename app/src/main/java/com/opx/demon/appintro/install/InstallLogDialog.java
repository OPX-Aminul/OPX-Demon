package com.opx.demon.appintro.install;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.opx.demon.R;

public final class InstallLogDialog {

    private final Dialog dialog;
    private final RecyclerView list;
    private final TextView state;
    private final LogAdapter adapter;

    private InstallLogDialog(Dialog dialog, RecyclerView list, TextView state, LogAdapter adapter) {
        this.dialog = dialog;
        this.list = list;
        this.state = state;
        this.adapter = adapter;
    }

    public static InstallLogDialog show(Context context, LogAdapter adapter) {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_install_log, null);

        RecyclerView list = root.findViewById(R.id.install_log_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        TextView state = root.findViewById(R.id.install_log_state);

        Dialog d = new Dialog(context);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        MaterialButton close = root.findViewById(R.id.install_log_close);
        close.setOnClickListener(v -> d.dismiss());

        MaterialButton copy = root.findViewById(R.id.install_log_copy);
        copy.setOnClickListener(v -> {
            ClipboardManager cb =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb == null) return;
            cb.setPrimaryClip(ClipData.newPlainText("Stryker install log", adapter.asText()));
            Toast.makeText(context, R.string.hc_copied, Toast.LENGTH_SHORT).show();
        });

        d.show();

        InstallLogDialog handle = new InstallLogDialog(d, list, state, adapter);
        handle.scrollToEnd();
        return handle;
    }

    public void setState(CharSequence text) {
        if (state != null) state.setText(text);
    }

    public void scrollToEnd() {
        if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1);
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        list.setAdapter(null);
        if (dialog.isShowing()) dialog.dismiss();
    }
}
