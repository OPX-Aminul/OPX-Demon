package com.opx.demon.wordlists;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WordlistGenerateSheet {

    private WordlistGenerateSheet() {}

    private enum Tab { PRESETS, CHARSET, MASK, NUMERIC, MUTATE }

    public static void show(Context context, Activity activity, Core core, Runnable onChanged) {
        build(context, activity, core, null, onChanged);
    }

    public static void showMutate(Context context, Activity activity, Core core,
                                  Wordlist source, Runnable onChanged) {
        build(context, activity, core, source, onChanged);
    }

    private static void build(Context context, Activity activity, Core core,
                              Wordlist mutateSource, Runnable onChanged) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_wordlist_generate);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        MaterialTextView title = dialog.findViewById(R.id.wlgen_title);
        MaterialTextView status = dialog.findViewById(R.id.wlgen_status);
        ChipGroup modes = dialog.findViewById(R.id.wlgen_modes);
        View modeScroll = dialog.findViewById(R.id.wlgen_mode_scroll);

        LinearLayout blockPresets = dialog.findViewById(R.id.wlgen_block_presets);
        LinearLayout blockCharset = dialog.findViewById(R.id.wlgen_block_charset);
        LinearLayout blockMask = dialog.findViewById(R.id.wlgen_block_mask);
        LinearLayout blockNumeric = dialog.findViewById(R.id.wlgen_block_numeric);
        LinearLayout blockMutate = dialog.findViewById(R.id.wlgen_block_mutate);
        LinearLayout blockOutput = dialog.findViewById(R.id.wlgen_block_output);

        ChipGroup charsetChips = dialog.findViewById(R.id.wlgen_charset_chips);
        TextInputEditText charsetCustom = dialog.findViewById(R.id.wlgen_charset_custom);
        TextInputEditText minLen = dialog.findViewById(R.id.wlgen_min_len);
        TextInputEditText maxLen = dialog.findViewById(R.id.wlgen_max_len);
        TextInputEditText maskField = dialog.findViewById(R.id.wlgen_mask);
        TextInputEditText fromField = dialog.findViewById(R.id.wlgen_from);
        TextInputEditText toField = dialog.findViewById(R.id.wlgen_to);
        TextInputEditText padField = dialog.findViewById(R.id.wlgen_pad);
        ChipGroup mutationChips = dialog.findViewById(R.id.wlgen_mutations);
        TextInputEditText prefixField = dialog.findViewById(R.id.wlgen_prefix);
        TextInputEditText suffixField = dialog.findViewById(R.id.wlgen_suffix);

        TextInputEditText nameField = dialog.findViewById(R.id.wlgen_name);
        ChipGroup categoryChips = dialog.findViewById(R.id.wlgen_categories);
        Chip wpaChip = dialog.findViewById(R.id.wlgen_chip_wpa);
        MaterialTextView estimate = dialog.findViewById(R.id.wlgen_estimate);
        LinearProgressIndicator progress = dialog.findViewById(R.id.wlgen_progress);
        MaterialButton run = dialog.findViewById(R.id.wlgen_btn_run);
        MaterialButton cancel = dialog.findViewById(R.id.wlgen_btn_cancel);
        MaterialButton close = dialog.findViewById(R.id.wlgen_btn_close);

        maskField.setText("?d?d?d?d");

        final WordlistStore store = new WordlistStore(core);
        final Tab[] tab = { mutateSource != null ? Tab.MUTATE : Tab.PRESETS };
        final WordlistCategory[] category = { WordlistCategory.PASSWORD };
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final boolean[] running = { false };

        final boolean[] useDigits = { true }, useLower = { false }, useUpper = { false }, useSpecial = { false };
        Chip digitsChip = toggle(context, charsetChips, context.getString(R.string.wl_gen_digits), true);
        Chip lowerChip = toggle(context, charsetChips, context.getString(R.string.wl_gen_lower), false);
        Chip upperChip = toggle(context, charsetChips, context.getString(R.string.wl_gen_upper), false);
        Chip specialChip = toggle(context, charsetChips, context.getString(R.string.wl_gen_special), false);

        Chip mutCap = toggle(context, mutationChips, context.getString(R.string.wl_mut_capitalize), true);
        Chip mutUpper = toggle(context, mutationChips, context.getString(R.string.wl_mut_upper), false);
        Chip mutLeet = toggle(context, mutationChips, context.getString(R.string.wl_mut_leet), false);
        Chip mutRev = toggle(context, mutationChips, context.getString(R.string.wl_mut_reverse), false);
        Chip mutDigits = toggle(context, mutationChips, context.getString(R.string.wl_mut_digits), false);
        Chip mutYears = toggle(context, mutationChips, context.getString(R.string.wl_mut_years), false);

        for (WordlistCategory c : WordlistCategory.values()) {
            Chip chip = new Chip(context);
            chip.setText(c.title);
            chip.setCheckable(true);
            chip.setTextSize(11f);
            chip.setChipMinHeight(dp(context, 30));
            chip.setChipStrokeWidth(1f);
            chip.setChipStrokeColor(ColorStateList.valueOf(c.color));
            chip.setChipBackgroundColorResource(R.color.light_contrast);
            chip.setTextColor(c.color);
            chip.setChecked(c == WordlistCategory.PASSWORD);
            chip.setOnClickListener(v -> category[0] = c);
            categoryChips.addView(chip);
        }

        final java.util.concurrent.Callable<WordlistGenerator.Spec> specOf = () -> {
            WordlistGenerator.Spec spec = new WordlistGenerator.Spec();
            spec.category = category[0];
            spec.wpaLengthOnly = wpaChip.isChecked();
            spec.name = text(nameField).isEmpty() ? defaultName(tab[0]) : text(nameField);
            switch (tab[0]) {
                case CHARSET: {
                    spec.mode = WordlistGenerator.Mode.CHARSET;
                    String custom = text(charsetCustom);
                    if (!custom.isEmpty()) {
                        spec.charset = dedupeChars(custom);
                    } else {
                        StringBuilder sb = new StringBuilder();
                        if (useDigits[0]) sb.append(WordlistGenerator.DIGITS);
                        if (useLower[0]) sb.append(WordlistGenerator.LOWER);
                        if (useUpper[0]) sb.append(WordlistGenerator.UPPER);
                        if (useSpecial[0]) sb.append(WordlistGenerator.SPECIAL);
                        spec.charset = sb.length() == 0 ? WordlistGenerator.DIGITS : sb.toString();
                    }
                    spec.minLength = Math.max(1, intOf(minLen, 4));
                    spec.maxLength = Math.max(spec.minLength, intOf(maxLen, 4));
                    break;
                }
                case MASK:
                    spec.mode = WordlistGenerator.Mode.MASK;
                    spec.mask = text(maskField).isEmpty() ? "?d?d?d?d" : text(maskField);
                    break;
                case NUMERIC:
                    spec.mode = WordlistGenerator.Mode.NUMERIC;
                    spec.from = longOf(fromField, 0);
                    spec.to = longOf(toField, 9999);
                    spec.pad = intOf(padField, 4);
                    break;
                case MUTATE:
                    spec.mode = WordlistGenerator.Mode.MUTATE;
                    spec.source = mutateSource;
                    spec.mutCapitalize = mutCap.isChecked();
                    spec.mutUpper = mutUpper.isChecked();
                    spec.mutLeet = mutLeet.isChecked();
                    spec.mutReverse = mutRev.isChecked();
                    spec.mutAppendDigits = mutDigits.isChecked();
                    spec.mutAppendYears = mutYears.isChecked();
                    spec.prefix = text(prefixField);
                    spec.suffix = text(suffixField);
                    break;
                default:
                    break;
            }
            return spec;
        };

        final Runnable refreshEstimate = () -> {
            if (tab[0] == Tab.PRESETS) return;
            try {
                long n = WordlistGenerator.estimate(specOf.call());
                estimate.setText(n < 0
                        ? context.getString(R.string.wl_gen_estimate_unknown)
                        : context.getString(R.string.wl_gen_estimate, String.format(Locale.US, "%,d", n)));
                boolean capped = n >= WordlistGenerator.DEFAULT_LIMIT;
                estimate.setTextColor(capped ? 0xFFEF6C00 : 0xFF757575);
                if (capped) {
                    estimate.setText(context.getString(R.string.wl_gen_estimate_capped,
                            String.format(Locale.US, "%,d", WordlistGenerator.DEFAULT_LIMIT)));
                }
            } catch (Exception e) {
                estimate.setText("");
            }
        };

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshEstimate.run(); }
        };
        charsetCustom.addTextChangedListener(watcher);
        minLen.addTextChangedListener(watcher);
        maxLen.addTextChangedListener(watcher);
        maskField.addTextChangedListener(watcher);
        fromField.addTextChangedListener(watcher);
        toField.addTextChangedListener(watcher);
        padField.addTextChangedListener(watcher);

        View.OnClickListener charsetToggle = v -> {
            useDigits[0] = digitsChip.isChecked();
            useLower[0] = lowerChip.isChecked();
            useUpper[0] = upperChip.isChecked();
            useSpecial[0] = specialChip.isChecked();
            refreshEstimate.run();
        };
        digitsChip.setOnClickListener(charsetToggle);
        lowerChip.setOnClickListener(charsetToggle);
        upperChip.setOnClickListener(charsetToggle);
        specialChip.setOnClickListener(charsetToggle);

        final Runnable showTab = () -> {
            blockPresets.setVisibility(tab[0] == Tab.PRESETS ? View.VISIBLE : View.GONE);
            blockCharset.setVisibility(tab[0] == Tab.CHARSET ? View.VISIBLE : View.GONE);
            blockMask.setVisibility(tab[0] == Tab.MASK ? View.VISIBLE : View.GONE);
            blockNumeric.setVisibility(tab[0] == Tab.NUMERIC ? View.VISIBLE : View.GONE);
            blockMutate.setVisibility(tab[0] == Tab.MUTATE ? View.VISIBLE : View.GONE);
            boolean rule = tab[0] != Tab.PRESETS;
            blockOutput.setVisibility(rule ? View.VISIBLE : View.GONE);
            run.setVisibility(rule ? View.VISIBLE : View.GONE);
            refreshEstimate.run();
        };

        if (mutateSource != null) {
            title.setText(R.string.wl_mut_title);
            status.setText(context.getString(R.string.wl_mut_subtitle, mutateSource.getName()));
            modeScroll.setVisibility(View.GONE);
            category[0] = mutateSource.category;
            nameField.setText(mutateSource.getDisplayName() + "-mutated.txt");
        } else {
            addTab(context, modes, context.getString(R.string.wl_gen_tab_presets), true, () -> {
                tab[0] = Tab.PRESETS;
                showTab.run();
            });
            addTab(context, modes, context.getString(R.string.wl_gen_tab_charset), false, () -> {
                tab[0] = Tab.CHARSET;
                showTab.run();
            });
            addTab(context, modes, context.getString(R.string.wl_gen_tab_mask), false, () -> {
                tab[0] = Tab.MASK;
                showTab.run();
            });
            addTab(context, modes, context.getString(R.string.wl_gen_tab_numeric), false, () -> {
                tab[0] = Tab.NUMERIC;
                showTab.run();
            });
        }

        for (WordlistGenerator.Preset preset : WordlistGenerator.presets()) {
            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_wordlist_offer, blockPresets, false);
            ImageView icon = row.findViewById(R.id.wloffer_icon);
            MaterialTextView rowTitle = row.findViewById(R.id.wloffer_title);
            MaterialTextView rowDesc = row.findViewById(R.id.wloffer_desc);
            MaterialTextView rowSize = row.findViewById(R.id.wloffer_size);
            ImageView action = row.findViewById(R.id.wloffer_action);

            icon.setImageResource(preset.category.iconRes);
            icon.setColorFilter(preset.category.color);
            rowTitle.setText(preset.title);
            rowDesc.setText(preset.description);
            rowSize.setText(String.format(Locale.US, "%,d", preset.approxLines));
            action.setColorFilter(preset.category.color);

            row.setOnClickListener(v -> {
                action.setEnabled(false);
                new Thread(() -> {
                    Wordlist made = WordlistGenerator.writePreset(store, preset);
                    activity.runOnUiThread(() -> {
                        action.setEnabled(true);
                        if (made == null) {
                            Toast.makeText(context, R.string.wl_gen_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        action.setImageResource(R.drawable.done);
                        action.setColorFilter(0xFF2E7D32);
                        Toast.makeText(context,
                                context.getString(R.string.wl_gen_written, made.getName()),
                                Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    });
                }, "wordlist-preset").start();
            });
            blockPresets.addView(row);
        }

        showTab.run();

        run.setOnClickListener(v -> {
            if (running[0]) return;
            WordlistGenerator.Spec spec;
            try {
                spec = specOf.call();
            } catch (Exception e) {
                return;
            }
            if (spec.mode == WordlistGenerator.Mode.MUTATE && spec.source == null) return;

            running[0] = true;
            cancelled.set(false);
            run.setEnabled(false);
            run.setAlpha(0.6f);
            cancel.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);

            new Thread(() -> {
                Wordlist made = WordlistGenerator.generate(store, spec, new WordlistGenerator.Progress() {
                    @Override
                    public void onProgress(long produced, long estimatedTotal) {
                        activity.runOnUiThread(() -> {
                            if (estimatedTotal > 0) {
                                progress.setIndeterminate(false);
                                progress.setMax(100);
                                progress.setProgress((int) Math.min(100,
                                        produced * 100 / Math.max(1, estimatedTotal)), true);
                            }
                            status.setText(context.getString(R.string.wl_gen_progress,
                                    String.format(Locale.US, "%,d", produced)));
                        });
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
                    if (made == null) {
                        status.setText(R.string.wl_gen_cancelled);
                        return;
                    }
                    status.setText(context.getString(R.string.wl_gen_written, made.getName()));
                    Toast.makeText(context, context.getString(R.string.wl_gen_written, made.getName()),
                            Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                });
            }, "wordlist-generate").start();
        });

        cancel.setOnClickListener(v -> cancelled.set(true));
        close.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
        });

        dialog.show();
    }

    private static String defaultName(Tab tab) {
        switch (tab) {
            case CHARSET: return "charset.txt";
            case MASK: return "mask.txt";
            case NUMERIC: return "numeric.txt";
            case MUTATE: return "mutated.txt";
            default: return "generated.txt";
        }
    }

    private static String dedupeChars(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (sb.indexOf(String.valueOf(c)) < 0) sb.append(c);
        }
        return sb.toString();
    }

    private static Chip toggle(Context context, ChipGroup group, String text, boolean checked) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setTextSize(11f);
        chip.setChipMinHeight(dp(context, 30));
        group.addView(chip);
        return chip;
    }

    private static void addTab(Context context, ChipGroup group, String text,
                               boolean checked, Runnable onSelected) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setTextSize(11f);
        chip.setChipMinHeight(dp(context, 30));
        chip.setOnClickListener(v -> onSelected.run());
        group.addView(chip);
    }

    private static float dp(Context context, int value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private static String text(TextInputEditText field) {
        CharSequence cs = field.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    private static int intOf(TextInputEditText field, int fallback) {
        try {
            return Integer.parseInt(text(field));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(TextInputEditText field, long fallback) {
        try {
            return Long.parseLong(text(field));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
