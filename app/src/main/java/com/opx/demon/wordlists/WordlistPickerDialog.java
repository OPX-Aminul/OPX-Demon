package com.opx.demon.wordlists;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.MainActivity;
import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WordlistPickerDialog {

    public interface OnPicked {
        void onPicked(Wordlist wordlist);
    }

    private WordlistPickerDialog() {}

    public static void show(Context context, Activity activity, Core core,
                            String title, WordlistCategory[] preferred,
                            String preselectedName, OnPicked callback) {

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_wordlist_picker);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        MaterialTextView titleView = dialog.findViewById(R.id.wlpick_title);
        MaterialTextView subtitle = dialog.findViewById(R.id.wlpick_subtitle);
        TextInputEditText search = dialog.findViewById(R.id.wlpick_search);
        ChipGroup filters = dialog.findViewById(R.id.wlpick_filters);
        RecyclerView list = dialog.findViewById(R.id.wlpick_list);
        LinearLayout empty = dialog.findViewById(R.id.wlpick_empty);
        MaterialTextView emptyText = dialog.findViewById(R.id.wlpick_empty_text);
        MaterialButton manage = dialog.findViewById(R.id.wlpick_btn_manage);
        MaterialButton close = dialog.findViewById(R.id.wlpick_btn_close);

        if (title != null) titleView.setText(title);

        final WordlistStore store = new WordlistStore(core);
        final List<Wordlist> all = new ArrayList<>();
        final List<Wordlist> shown = new ArrayList<>();
        final WordlistCategory[] filter = { null };
        final String[] query = { "" };

        Adapter adapter = new Adapter(context, shown, preselectedName, wl -> {
            callback.onPicked(wl);
            dialog.dismiss();
        });
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        Runnable apply = () -> {
            shown.clear();
            for (Wordlist wl : all) {
                if (filter[0] != null && wl.category != filter[0]) continue;
                if (!query[0].isEmpty()
                        && !wl.getName().toLowerCase(Locale.US).contains(query[0])) continue;
                shown.add(wl);
            }
            adapter.notifyDataSetChanged();
            boolean none = shown.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            emptyText.setText(all.isEmpty()
                    ? context.getString(R.string.wl_pick_empty)
                    : context.getString(R.string.wl_pick_empty_filtered));
            subtitle.setText(context.getString(R.string.wl_pick_subtitle, shown.size(), all.size()));
        };

        List<WordlistCategory> ordered = new ArrayList<>();
        if (preferred != null) {
            for (WordlistCategory c : preferred) if (!ordered.contains(c)) ordered.add(c);
        }
        for (WordlistCategory c : WordlistCategory.values()) if (!ordered.contains(c)) ordered.add(c);

        Chip allChip = buildChip(context, filters, context.getString(R.string.wl_cat_all), 0xFF757575);
        allChip.setOnClickListener(v -> {
            filter[0] = null;
            apply.run();
        });
        for (WordlistCategory c : ordered) {
            Chip chip = buildChip(context, filters, c.title, c.color);
            chip.setOnClickListener(v -> {
                filter[0] = c;
                apply.run();
            });
        }

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query[0] = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                apply.run();
            }
        });

        manage.setOnClickListener(v -> {
            dialog.dismiss();
            new MainActivity.Receiver().changeFragment(R.id.wordlists_item);
        });
        close.setOnClickListener(v -> dialog.dismiss());

        empty.setOnClickListener(v -> {
            if (!all.isEmpty()) return;
            Toast.makeText(context, R.string.wl_pick_seeding, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                WordlistGenerator.Preset preset = WordlistGenerator.presetById(
                        preferredIsPin(preferred) ? "pins250" : "top250");
                Wordlist made = WordlistGenerator.writePreset(store, preset);
                List<Wordlist> reloaded = store.list();
                activity.runOnUiThread(() -> {
                    all.clear();
                    all.addAll(reloaded);
                    apply.run();
                    if (made != null) {
                        Toast.makeText(context,
                                context.getString(R.string.wl_pick_seeded, made.getName()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }, "wl-picker-seed").start();
        });

        new Thread(() -> {
            List<Wordlist> loaded = store.list();
            activity.runOnUiThread(() -> {
                all.clear();
                all.addAll(loaded);
                if (preferred != null && preferred.length > 0) {
                    java.util.Collections.sort(all, (a, b) ->
                            Integer.compare(rank(a, preferred), rank(b, preferred)));
                }
                apply.run();
            });
        }, "wl-picker-load").start();

        dialog.show();
    }

    public static void show(Context context, Activity activity, Core core, String title,
                            WordlistCategory preferred, String preselectedName, OnPicked cb) {
        show(context, activity, core, title, new WordlistCategory[] { preferred }, preselectedName, cb);
    }

    private static boolean preferredIsPin(WordlistCategory[] preferred) {
        return preferred != null && preferred.length > 0 && preferred[0] == WordlistCategory.PIN;
    }

    private static int rank(Wordlist wl, WordlistCategory[] preferred) {
        for (int i = 0; i < preferred.length; i++) {
            if (wl.category == preferred[i]) return i;
        }
        return preferred.length;
    }

    private static Chip buildChip(Context context, ChipGroup group, String text, int color) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setTextSize(11f);
        chip.setChipBackgroundColorResource(R.color.light_contrast);
        chip.setChipStrokeWidth(1f);
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(color));
        chip.setTextColor(color);
        group.addView(chip);
        return chip;
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final Context context;
        private final List<Wordlist> items;
        private final String preselected;
        private final OnPicked callback;

        Adapter(Context context, List<Wordlist> items, String preselected, OnPicked callback) {
            this.context = context;
            this.items = items;
            this.preselected = preselected;
            this.callback = callback;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_wordlist_pick, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Wordlist wl = items.get(position);
            h.name.setText(wl.getName());
            h.meta.setText(wl.describe());
            h.category.setText(wl.category.title);
            h.category.setTextColor(wl.category.color);
            h.icon.setImageResource(wl.category.iconRes);
            h.icon.setColorFilter(wl.category.color);
            boolean selected = preselected != null && preselected.equals(wl.getName());
            h.check.setVisibility(selected ? View.VISIBLE : View.GONE);
            h.card.setStrokeColor(selected ? wl.category.color
                    : androidx.core.content.ContextCompat.getColor(context, R.color.light_lite_contrast));
            h.card.setOnClickListener(v -> callback.onPicked(wl));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final ImageView icon;
            final ImageView check;
            final MaterialTextView name;
            final MaterialTextView meta;
            final MaterialTextView category;

            Holder(@NonNull View v) {
                super(v);
                card = v.findViewById(R.id.wlpick_item_card);
                icon = v.findViewById(R.id.wlpick_item_icon);
                check = v.findViewById(R.id.wlpick_item_check);
                name = v.findViewById(R.id.wlpick_item_name);
                meta = v.findViewById(R.id.wlpick_item_meta);
                category = v.findViewById(R.id.wlpick_item_category);
            }
        }
    }
}
