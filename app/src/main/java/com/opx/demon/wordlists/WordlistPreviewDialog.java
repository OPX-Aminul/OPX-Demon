package com.opx.demon.wordlists;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.util.List;
import java.util.Locale;

public final class WordlistPreviewDialog {

    private static final int PREVIEW_LINES = 200;

    private WordlistPreviewDialog() {}

    public static void show(Context context, Activity activity, Core core,
                            Wordlist wordlist, Runnable onChanged) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_wordlist_preview);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        ImageView icon = dialog.findViewById(R.id.wlprev_icon);
        MaterialTextView name = dialog.findViewById(R.id.wlprev_name);
        MaterialTextView meta = dialog.findViewById(R.id.wlprev_meta);
        MaterialTextView text = dialog.findViewById(R.id.wlprev_text);
        MaterialTextView footer = dialog.findViewById(R.id.wlprev_footer);
        MaterialButton count = dialog.findViewById(R.id.wlprev_btn_count);
        MaterialButton normalize = dialog.findViewById(R.id.wlprev_btn_normalize);
        MaterialButton close = dialog.findViewById(R.id.wlprev_btn_close);

        icon.setImageResource(wordlist.category.iconRes);
        icon.setColorFilter(wordlist.category.color);
        name.setText(wordlist.getName());
        meta.setText(wordlist.category.title + " · " + wordlist.describe());
        text.setText(context.getString(R.string.wl_loading));

        final WordlistStore store = new WordlistStore(core);

        new Thread(() -> {
            List<String> lines = store.preview(wordlist, PREVIEW_LINES);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append(line).append('\n');
            String body = sb.length() == 0 ? context.getString(R.string.wl_preview_empty) : sb.toString();
            activity.runOnUiThread(() -> {
                text.setText(body);
                footer.setText(context.getString(R.string.wl_preview_footer, lines.size()));
            });
        }, "wordlist-preview").start();

        count.setOnClickListener(v -> {
            count.setEnabled(false);
            new Thread(() -> {
                long n = store.countLines(wordlist);
                activity.runOnUiThread(() -> {
                    count.setEnabled(true);
                    if (n < 0) {
                        Toast.makeText(context, R.string.wl_count_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    meta.setText(wordlist.category.title + " · " + wordlist.describe());
                    footer.setText(String.format(Locale.US, "%,d", n) + " "
                            + context.getString(R.string.wl_lines));
                    if (onChanged != null) onChanged.run();
                });
            }, "wordlist-preview-count").start();
        });

        normalize.setOnClickListener(v -> {
            dialog.dismiss();
            WordlistNormalizeSheet.show(context, activity, core, wordlist, onChanged);
        });

        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
