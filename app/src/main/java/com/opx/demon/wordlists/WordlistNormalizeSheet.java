package com.opx.demon.wordlists;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WordlistNormalizeSheet {

    private WordlistNormalizeSheet() {}

    public static void show(Context context, Activity activity, Core core,
                            Wordlist source, Runnable onChanged) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_wordlist_normalize);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        MaterialTextView status = dialog.findViewById(R.id.wlnorm_status);
        ChipGroup options = dialog.findViewById(R.id.wlnorm_options);
        ChipGroup sortGroup = dialog.findViewById(R.id.wlnorm_sort);
        TextInputEditText minField = dialog.findViewById(R.id.wlnorm_min);
        TextInputEditText maxField = dialog.findViewById(R.id.wlnorm_max);
        TextInputLayout outputLayout = dialog.findViewById(R.id.wlnorm_output_layout);
        TextInputEditText outputField = dialog.findViewById(R.id.wlnorm_output);
        LinearProgressIndicator progress = dialog.findViewById(R.id.wlnorm_progress);
        MaterialButton run = dialog.findViewById(R.id.wlnorm_btn_run);
        MaterialButton cancel = dialog.findViewById(R.id.wlnorm_btn_cancel);
        MaterialButton close = dialog.findViewById(R.id.wlnorm_btn_close);

        status.setText(source.getName() + " · " + source.describe());
        outputField.setText(source.getDisplayName() + "-clean.txt");

        Chip trim = chip(context, options, context.getString(R.string.wl_norm_trim), true);
        Chip empty = chip(context, options, context.getString(R.string.wl_norm_empty), true);
        Chip comments = chip(context, options, context.getString(R.string.wl_norm_comments), true);
        Chip control = chip(context, options, context.getString(R.string.wl_norm_control), true);
        Chip dedupe = chip(context, options, context.getString(R.string.wl_norm_dedupe), true);
        Chip lower = chip(context, options, context.getString(R.string.wl_norm_lower), false);
        Chip ascii = chip(context, options, context.getString(R.string.wl_norm_ascii), false);
        Chip wpa = chip(context, options, context.getString(R.string.wl_norm_wpa),
                source.category == WordlistCategory.WIFI);
        Chip inPlace = chip(context, options, context.getString(R.string.wl_norm_inplace), false);

        Chip sortNone = chip(context, sortGroup, context.getString(R.string.wl_norm_sort_none), true);
        Chip sortAlpha = chip(context, sortGroup, context.getString(R.string.wl_norm_sort_alpha), false);
        Chip sortLength = chip(context, sortGroup, context.getString(R.string.wl_norm_sort_length), false);

        inPlace.setOnClickListener(v ->
                outputLayout.setVisibility(inPlace.isChecked() ? View.GONE : View.VISIBLE));

        final WordlistStore store = new WordlistStore(core);
        final WordlistNormalizer normalizer = new WordlistNormalizer(store);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final boolean[] running = { false };

        run.setOnClickListener(v -> {
            if (running[0]) return;

            WordlistNormalizer.Options opts = new WordlistNormalizer.Options();
            opts.trim = trim.isChecked();
            opts.dropEmpty = empty.isChecked();
            opts.dropComments = comments.isChecked();
            opts.stripControl = control.isChecked();
            opts.dedupe = dedupe.isChecked();
            opts.toLowercase = lower.isChecked();
            opts.asciiOnly = ascii.isChecked();
            opts.wpaOnly = wpa.isChecked();
            opts.inPlace = inPlace.isChecked();
            opts.minLength = intOf(minField, 0);
            opts.maxLength = intOf(maxField, 0);
            opts.outputName = textOf(outputField);
            opts.sort = sortLength.isChecked() ? WordlistNormalizer.Sort.BY_LENGTH
                    : sortAlpha.isChecked() ? WordlistNormalizer.Sort.ALPHABETICAL
                    : WordlistNormalizer.Sort.NONE;

            running[0] = true;
            cancelled.set(false);
            run.setEnabled(false);
            run.setAlpha(0.6f);
            cancel.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);

            new Thread(() -> {
                WordlistNormalizer.Result result = normalizer.run(source, opts,
                        new WordlistNormalizer.Progress() {
                            @Override
                            public void onProgress(long linesRead, long linesKept) {
                                activity.runOnUiThread(() -> status.setText(
                                        context.getString(R.string.wl_norm_progress,
                                                String.format(Locale.US, "%,d", linesRead))));
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelled.get();
                            }
                        });

                activity.runOnUiThread(() -> {
                    running[0] = false;
                    run.setEnabled(true);
                    run.setAlpha(1f);
                    cancel.setVisibility(View.GONE);
                    progress.setVisibility(View.GONE);

                    if (result.cancelled) {
                        status.setText(R.string.wl_norm_cancelled);
                        return;
                    }
                    status.setText(describe(context, result));
                    if (result.dedupeTruncated) {
                        Toast.makeText(context, R.string.wl_norm_dedupe_truncated,
                                Toast.LENGTH_LONG).show();
                    }
                    if (onChanged != null) onChanged.run();
                });
            }, "wordlist-normalize").start();
        });

        cancel.setOnClickListener(v -> cancelled.set(true));
        close.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
        });

        dialog.show();
    }

    private static String describe(Context context, WordlistNormalizer.Result r) {
        StringBuilder sb = new StringBuilder(r.summary());
        StringBuilder why = new StringBuilder();
        appendReason(why, r.removedDuplicate, context.getString(R.string.wl_norm_reason_dupes));
        appendReason(why, r.removedEmpty, context.getString(R.string.wl_norm_reason_empty));
        appendReason(why, r.removedComment, context.getString(R.string.wl_norm_reason_comments));
        appendReason(why, r.removedLength, context.getString(R.string.wl_norm_reason_length));
        appendReason(why, r.removedNonAscii, context.getString(R.string.wl_norm_reason_nonascii));
        if (why.length() > 0) sb.append(" (").append(why).append(')');
        return sb.toString();
    }

    private static void appendReason(StringBuilder sb, long count, String label) {
        if (count <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(String.format(Locale.US, "%,d", count)).append(' ').append(label);
    }

    private static Chip chip(Context context, ChipGroup group, String text, boolean checked) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setTextSize(11f);
        chip.setChipMinHeight(30 * context.getResources().getDisplayMetrics().density);
        group.addView(chip);
        return chip;
    }

    private static String textOf(TextInputEditText field) {
        CharSequence cs = field.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    private static int intOf(TextInputEditText field, int fallback) {
        try {
            return Integer.parseInt(textOf(field));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
