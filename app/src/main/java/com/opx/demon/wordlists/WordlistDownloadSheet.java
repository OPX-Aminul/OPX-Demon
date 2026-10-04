package com.opx.demon.wordlists;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WordlistDownloadSheet {

    private WordlistDownloadSheet() {}

    public static void show(Context context, Activity activity, Core core, Runnable onChanged) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_wordlist_download);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        MaterialTextView status = dialog.findViewById(R.id.wldl_status);
        LinearLayout catalog = dialog.findViewById(R.id.wldl_catalog);
        TextInputEditText urlField = dialog.findViewById(R.id.wldl_url);
        MaterialButton fetch = dialog.findViewById(R.id.wldl_btn_fetch);
        LinearProgressIndicator progress = dialog.findViewById(R.id.wldl_progress);
        MaterialButton cancel = dialog.findViewById(R.id.wldl_btn_cancel);
        MaterialButton close = dialog.findViewById(R.id.wldl_btn_close);

        final WordlistStore store = new WordlistStore(core);
        final WordlistDownloader downloader = new WordlistDownloader(store);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final boolean[] busy = { false };

        for (WordlistCatalog.Entry entry : WordlistCatalog.entries()) {
            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_wordlist_offer, catalog, false);
            ImageView icon = row.findViewById(R.id.wloffer_icon);
            MaterialTextView title = row.findViewById(R.id.wloffer_title);
            MaterialTextView desc = row.findViewById(R.id.wloffer_desc);
            MaterialTextView size = row.findViewById(R.id.wloffer_size);
            ImageView action = row.findViewById(R.id.wloffer_action);
            LinearProgressIndicator rowProgress = row.findViewById(R.id.wloffer_progress);

            icon.setImageResource(entry.category.iconRes);
            icon.setColorFilter(entry.category.color);
            title.setText(entry.title);
            desc.setText(entry.description);
            size.setText(entry.describeSize());
            action.setImageResource(R.drawable.download);
            action.setColorFilter(entry.category.color);

            boolean have = store.exists(entry.fileName);
            if (have) {
                action.setImageResource(R.drawable.done);
                action.setColorFilter(0xFF2E7D32);
            }

            row.setOnClickListener(v -> {
                if (busy[0]) {
                    Toast.makeText(context, R.string.wl_dl_busy, Toast.LENGTH_SHORT).show();
                    return;
                }
                busy[0] = true;
                cancelled.set(false);
                cancel.setVisibility(View.VISIBLE);
                rowProgress.setVisibility(View.VISIBLE);
                rowProgress.setIndeterminate(true);
                status.setText(context.getString(R.string.wl_dl_downloading, entry.title));

                new Thread(() -> {
                    Wordlist made = null;
                    String error = null;
                    try {
                        made = downloader.download(entry, new WordlistDownloader.Progress() {
                            @Override
                            public void onProgress(long downloaded, long total) {
                                activity.runOnUiThread(() -> {
                                    if (total > 0) {
                                        rowProgress.setIndeterminate(false);
                                        rowProgress.setMax(100);
                                        rowProgress.setProgress(
                                                (int) Math.min(100, downloaded * 100 / total), true);
                                    }
                                    size.setText(Wordlist.humanBytes(downloaded));
                                });
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelled.get();
                            }
                        });
                    } catch (IOException e) {
                        error = e.getMessage();
                    }

                    final Wordlist result = made;
                    final String failure = error;
                    activity.runOnUiThread(() -> {
                        busy[0] = false;
                        cancel.setVisibility(View.GONE);
                        rowProgress.setVisibility(View.GONE);
                        if (result == null) {
                            size.setText(entry.describeSize());
                            status.setText(failure == null
                                    ? context.getString(R.string.wl_dl_cancelled)
                                    : context.getString(R.string.wl_dl_failed, failure));
                            return;
                        }
                        action.setImageResource(R.drawable.done);
                        action.setColorFilter(0xFF2E7D32);
                        size.setText(result.describeSize());
                        status.setText(context.getString(R.string.wl_dl_done, result.getName()));
                        if (onChanged != null) onChanged.run();
                    });
                }, "wordlist-download").start();
            });

            catalog.addView(row);
        }

        fetch.setOnClickListener(v -> {
            if (busy[0]) {
                Toast.makeText(context, R.string.wl_dl_busy, Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence cs = urlField.getText();
            String url = cs == null ? "" : cs.toString().trim();
            if (!url.startsWith("https://")) {
                Toast.makeText(context, R.string.wl_dl_https_only, Toast.LENGTH_SHORT).show();
                return;
            }
            busy[0] = true;
            cancelled.set(false);
            cancel.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            status.setText(context.getString(R.string.wl_dl_downloading,
                    WordlistDownloader.nameFromUrl(url)));

            new Thread(() -> {
                Wordlist made = null;
                String error = null;
                try {
                    made = downloader.download(url, null, null, new WordlistDownloader.Progress() {
                        @Override
                        public void onProgress(long downloaded, long total) {
                            activity.runOnUiThread(() -> {
                                if (total > 0) {
                                    progress.setIndeterminate(false);
                                    progress.setMax(100);
                                    progress.setProgress(
                                            (int) Math.min(100, downloaded * 100 / total), true);
                                }
                                status.setText(String.format(Locale.US, "%s · %s",
                                        context.getString(R.string.wl_dl_downloading_short),
                                        Wordlist.humanBytes(downloaded)));
                            });
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled.get();
                        }
                    });
                } catch (IOException e) {
                    error = e.getMessage();
                }

                final Wordlist result = made;
                final String failure = error;
                activity.runOnUiThread(() -> {
                    busy[0] = false;
                    cancel.setVisibility(View.GONE);
                    progress.setVisibility(View.GONE);
                    if (result == null) {
                        status.setText(failure == null
                                ? context.getString(R.string.wl_dl_cancelled)
                                : context.getString(R.string.wl_dl_failed, failure));
                        return;
                    }
                    urlField.setText("");
                    status.setText(context.getString(R.string.wl_dl_done, result.getName()));
                    if (onChanged != null) onChanged.run();
                });
            }, "wordlist-download-url").start();
        });

        cancel.setOnClickListener(v -> cancelled.set(true));
        close.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
        });

        dialog.show();
    }
}
