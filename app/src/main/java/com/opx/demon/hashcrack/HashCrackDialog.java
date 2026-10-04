package com.opx.demon.hashcrack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;
import com.opx.demon.utils.Core;
import com.opx.demon.wordlists.Wordlist;
import com.opx.demon.wordlists.WordlistCategory;
import com.opx.demon.wordlists.WordlistPickerDialog;
import com.opx.demon.wordlists.WordlistStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HashCrackDialog {

    private HashCrackDialog() {}

    public static void show(Context context, Activity activity, Core core, String prefilledHash) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_hash_crack);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        ShimmerFrameLayout shimmer = dialog.findViewById(R.id.hc_shimmer);
        MaterialTextView status = dialog.findViewById(R.id.hc_status);
        TextInputEditText hashesField = dialog.findViewById(R.id.hc_hashes);
        ChipGroup typeGroup = dialog.findViewById(R.id.hc_types);
        MaterialCardView wordlistCard = dialog.findViewById(R.id.hc_wordlist_card);
        ImageView wordlistIcon = dialog.findViewById(R.id.hc_wordlist_icon);
        MaterialTextView wordlistName = dialog.findViewById(R.id.hc_wordlist_name);
        MaterialTextView wordlistMeta = dialog.findViewById(R.id.hc_wordlist_meta);
        TextInputEditText saltField = dialog.findViewById(R.id.hc_salt);
        ChipGroup saltModeGroup = dialog.findViewById(R.id.hc_salt_mode);
        LinearProgressIndicator progress = dialog.findViewById(R.id.hc_progress);
        MaterialCardView resultsCard = dialog.findViewById(R.id.hc_results_card);
        MaterialTextView results = dialog.findViewById(R.id.hc_results);
        MaterialButton copy = dialog.findViewById(R.id.hc_copy);
        MaterialButton run = dialog.findViewById(R.id.hc_btn_run);
        MaterialButton stop = dialog.findViewById(R.id.hc_btn_stop);
        MaterialButton close = dialog.findViewById(R.id.hc_btn_close);

        shimmer.hideShimmer();
        if (prefilledHash != null) hashesField.setText(prefilledHash);

        final WordlistStore store = new WordlistStore(core);
        final Wordlist[] selected = { null };
        final HashType[] type = { null };
        final HashCracker[] cracker = { null };
        final StringBuilder foundText = new StringBuilder();

        Chip saltNone = saltChip(context, saltModeGroup, context.getString(R.string.hc_salt_none), true);
        Chip saltPrefix = saltChip(context, saltModeGroup, context.getString(R.string.hc_salt_prefix), false);
        saltChip(context, saltModeGroup, context.getString(R.string.hc_salt_suffix), false);

        final Runnable refreshTypes = () -> {
            List<String> hashes = HashCracker.parseHashes(text(hashesField));
            typeGroup.removeAllViews();
            type[0] = null;

            if (hashes.isEmpty()) {
                status.setText(R.string.hc_subtitle);
                return;
            }
            List<HashType> candidates = HashType.candidatesFor(hashes.get(0));
            if (candidates.isEmpty()) {
                status.setText(context.getString(R.string.hc_unknown_length, hashes.get(0).length()));
                return;
            }
            for (int i = 0; i < candidates.size(); i++) {
                HashType t = candidates.get(i);
                Chip chip = new Chip(context);
                chip.setText(t.title);
                chip.setCheckable(true);
                chip.setTextSize(11f);
                chip.setChipMinHeight(30 * context.getResources().getDisplayMetrics().density);
                chip.setChecked(i == 0);
                chip.setOnClickListener(v -> type[0] = t);
                typeGroup.addView(chip);
            }
            type[0] = candidates.get(0);
            status.setText(context.getString(R.string.hc_loaded, hashes.size()));
        };

        hashesField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshTypes.run(); }
        });
        refreshTypes.run();

        wordlistCard.setOnClickListener(v -> WordlistPickerDialog.show(context, activity, core,
                context.getString(R.string.hc_pick_wordlist),
                new WordlistCategory[] { WordlistCategory.PASSWORD, WordlistCategory.OTHER },
                selected[0] == null ? null : selected[0].getName(),
                wl -> {
                    selected[0] = wl;
                    wordlistName.setText(wl.getName());
                    wordlistMeta.setText(wl.describe());
                    wordlistIcon.setImageResource(wl.category.iconRes);
                    wordlistIcon.setColorFilter(wl.category.color);
                }));

        run.setOnClickListener(v -> {
            final List<String> hashes = HashCracker.parseHashes(text(hashesField));
            if (hashes.isEmpty()) {
                Toast.makeText(context, R.string.hc_need_hashes, Toast.LENGTH_SHORT).show();
                return;
            }
            if (type[0] == null) {
                Toast.makeText(context, R.string.hc_need_type, Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected[0] == null) {
                Toast.makeText(context, R.string.hc_need_wordlist, Toast.LENGTH_SHORT).show();
                return;
            }

            HashCracker.SaltMode saltMode = saltNone.isChecked() ? HashCracker.SaltMode.NONE
                    : saltPrefix.isChecked() ? HashCracker.SaltMode.PREFIX
                    : HashCracker.SaltMode.SUFFIX;

            foundText.setLength(0);
            results.setText("");
            resultsCard.setVisibility(View.GONE);
            shimmer.showShimmer(true);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            run.setEnabled(false);
            run.setAlpha(0.6f);
            stop.setVisibility(View.VISIBLE);

            final long total = selected[0].hasLineCount() ? selected[0].lines : -1;
            cracker[0] = new HashCracker();
            cracker[0].start(hashes, type[0], selected[0], text(saltField), saltMode, total,
                    new HashCracker.Listener() {
                        @Override
                        public void onProgress(long tried, long totalLines, long rate) {
                            activity.runOnUiThread(() -> {
                                if (totalLines > 0) {
                                    progress.setIndeterminate(false);
                                    progress.setMax(100);
                                    progress.setProgress(
                                            (int) Math.min(100, tried * 100 / totalLines), true);
                                }
                                status.setText(context.getString(R.string.hc_progress,
                                        String.format(Locale.US, "%,d", tried),
                                        HashCracker.describeRate(rate)));
                            });
                        }

                        @Override
                        public void onFound(String hash, String password) {
                            activity.runOnUiThread(() -> {
                                foundText.append(shorten(hash)).append("  ->  ").append(password).append('\n');
                                results.setText(foundText.toString());
                                resultsCard.setVisibility(View.VISIBLE);
                            });
                        }

                        @Override
                        public void onFinished(HashCracker.Result result) {
                            activity.runOnUiThread(() -> {
                                shimmer.hideShimmer();
                                progress.setVisibility(View.GONE);
                                run.setEnabled(true);
                                run.setAlpha(1f);
                                stop.setVisibility(View.GONE);

                                if (result.error != null) {
                                    status.setText(context.getString(R.string.hc_error, result.error));
                                    return;
                                }
                                int cracked = result.cracked.size();
                                if (cracked == 0) {
                                    status.setText(context.getString(
                                            result.cancelled ? R.string.hc_stopped : R.string.hc_none,
                                            String.format(Locale.US, "%,d", result.tried),
                                            HashCracker.describeRate(result.rate())));
                                } else {
                                    status.setText(context.getString(R.string.hc_found,
                                            cracked, hashes.size(),
                                            String.format(Locale.US, "%,d", result.tried),
                                            HashCracker.describeRate(result.rate())));
                                }
                            });
                        }
                    });
        });

        stop.setOnClickListener(v -> {
            if (cracker[0] != null) cracker[0].cancel();
        });

        copy.setOnClickListener(v -> {
            if (foundText.length() == 0) return;
            core.copyToClipBoard(foundText.toString());
            Toast.makeText(context, R.string.hc_copied, Toast.LENGTH_SHORT).show();
        });

        close.setOnClickListener(v -> {
            if (cracker[0] != null) cracker[0].cancel();
            dialog.dismiss();
        });

        new Thread(() -> {
            List<Wordlist> lists = store.list(WordlistCategory.PASSWORD);
            if (lists.isEmpty()) lists = new ArrayList<>(store.list());
            if (lists.isEmpty()) return;
            Wordlist smallest = lists.get(0);
            for (Wordlist wl : lists) {
                if (wl.sizeBytes < smallest.sizeBytes) smallest = wl;
            }
            final Wordlist pick = smallest;
            activity.runOnUiThread(() -> {
                if (selected[0] != null) return;
                selected[0] = pick;
                wordlistName.setText(pick.getName());
                wordlistMeta.setText(pick.describe());
                wordlistIcon.setImageResource(pick.category.iconRes);
                wordlistIcon.setColorFilter(pick.category.color);
            });
        }, "hashcrack-default-list").start();

        dialog.show();
    }

    private static String shorten(String hash) {
        return hash.length() <= 20 ? hash : hash.substring(0, 8) + "…" + hash.substring(hash.length() - 8);
    }

    private static Chip saltChip(Context context, ChipGroup group, String text, boolean checked) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setTextSize(10f);
        chip.setChipMinHeight(28 * context.getResources().getDisplayMetrics().density);
        group.addView(chip);
        return chip;
    }

    private static String text(TextInputEditText field) {
        CharSequence cs = field.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    public static String format(Map<String, String> cracked) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cracked.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
