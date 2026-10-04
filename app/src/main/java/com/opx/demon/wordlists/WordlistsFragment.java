package com.opx.demon.wordlists;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.MainActivity;
import com.opx.demon.R;
import com.opx.demon.hashcrack.HashCrackDialog;
import com.opx.demon.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WordlistsFragment extends Fragment implements WordlistAdapter.Listener {

    private static final String PREF_LAST_FILTER = "wordlists_last_filter";

    private final MainActivity.Receiver receiver = new MainActivity.Receiver();

    private Activity activity;
    private Context context;
    private Core core;
    private WordlistStore store;

    private final List<Wordlist> all = new ArrayList<>();
    private final List<Wordlist> shown = new ArrayList<>();
    private WordlistAdapter adapter;

    private MaterialTextView subtitle;
    private RecyclerView list;
    private LinearLayout empty;
    private ChipGroup filters;
    private TextInputEditText search;

    private WordlistCategory filter = null;
    private String query = "";

    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::importFrom);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wordlists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        store = new WordlistStore(core);

        receiver.setTitle(getString(R.string.wl_title));

        subtitle = view.findViewById(R.id.wl_subtitle);
        list = view.findViewById(R.id.wl_list);
        empty = view.findViewById(R.id.wl_empty);
        filters = view.findViewById(R.id.wl_filters);
        search = view.findViewById(R.id.wl_search);

        adapter = new WordlistAdapter(context, shown, this);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        buildFilterChips();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                applyFilter();
            }
        });

        MaterialCardView generate = view.findViewById(R.id.wl_action_generate);
        MaterialCardView download = view.findViewById(R.id.wl_action_download);
        MaterialCardView importCard = view.findViewById(R.id.wl_action_import);
        MaterialCardView hash = view.findViewById(R.id.wl_action_hash);
        ImageView refresh = view.findViewById(R.id.wl_refresh);
        MaterialButton seed = view.findViewById(R.id.wl_empty_seed);

        generate.setOnClickListener(v ->
                WordlistGenerateSheet.show(context, activity, core, this::reload));
        download.setOnClickListener(v ->
                WordlistDownloadSheet.show(context, activity, core, this::reload));
        importCard.setOnClickListener(v -> importLauncher.launch(new String[] { "*/*" }));
        hash.setOnClickListener(v -> HashCrackDialog.show(context, activity, core, null));
        refresh.setOnClickListener(v -> reload());
        seed.setOnClickListener(v -> seedStarterLists());

        reload();
    }

    private void reload() {
        new Thread(() -> {
            List<Wordlist> loaded = store.list();
            String folder = store.describeFolder();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                all.clear();
                all.addAll(loaded);
                subtitle.setText(folder);
                applyFilter();
            });
        }, "wordlists-load").start();
    }

    private void applyFilter() {
        shown.clear();
        for (Wordlist wl : all) {
            if (filter != null && wl.category != filter) continue;
            if (!query.isEmpty() && !wl.getName().toLowerCase(Locale.US).contains(query)) continue;
            shown.add(wl);
        }
        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private void buildFilterChips() {
        String saved = core.getString(PREF_LAST_FILTER);
        filters.removeAllViews();

        Chip allChip = chip(getString(R.string.wl_cat_all), 0xFF757575);
        allChip.setChecked(saved == null || saved.isEmpty());
        allChip.setOnClickListener(v -> {
            filter = null;
            core.putString(PREF_LAST_FILTER, "");
            applyFilter();
        });

        for (WordlistCategory c : WordlistCategory.values()) {
            Chip chip = chip(c.title, c.color);
            if (c.id.equals(saved)) {
                chip.setChecked(true);
                filter = c;
            }
            chip.setOnClickListener(v -> {
                filter = c;
                core.putString(PREF_LAST_FILTER, c.id);
                applyFilter();
            });
        }
    }

    private Chip chip(String text, int color) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setTextSize(11f);
        chip.setChipBackgroundColorResource(R.color.light_contrast);
        chip.setChipStrokeWidth(1f);
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(color));
        chip.setTextColor(color);
        filters.addView(chip);
        return chip;
    }

    @Override
    public void onOpen(Wordlist wordlist) {
        WordlistPreviewDialog.show(context, activity, core, wordlist, this::reload);
    }

    @Override
    public void onMenu(Wordlist wordlist, View anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(0, 1, 0, R.string.wl_menu_preview);
        menu.getMenu().add(0, 2, 1, R.string.wl_menu_count);
        menu.getMenu().add(0, 3, 2, R.string.wl_menu_rename);
        menu.getMenu().add(0, 4, 3, R.string.wl_menu_category);
        menu.getMenu().add(0, 5, 4, R.string.wl_menu_normalize);
        menu.getMenu().add(0, 6, 5, R.string.wl_menu_mutate);
        menu.getMenu().add(0, 7, 6, R.string.wl_menu_duplicate);
        menu.getMenu().add(0, 8, 7, R.string.wl_menu_share);
        menu.getMenu().add(0, 9, 8, R.string.wl_menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: onOpen(wordlist); return true;
                case 2: countLines(wordlist); return true;
                case 3: renameDialog(wordlist); return true;
                case 4: categoryDialog(wordlist); return true;
                case 5: WordlistNormalizeSheet.show(context, activity, core, wordlist, this::reload); return true;
                case 6: WordlistGenerateSheet.showMutate(context, activity, core, wordlist, this::reload); return true;
                case 7: duplicate(wordlist); return true;
                case 8: share(wordlist); return true;
                case 9: deleteDialog(wordlist); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void countLines(Wordlist wordlist) {
        toast(getString(R.string.wl_counting));
        new Thread(() -> {
            long n = store.countLines(wordlist);
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (n < 0) {
                    toast(getString(R.string.wl_count_failed));
                } else {
                    toast(String.format(Locale.US, "%,d", n) + " " + getString(R.string.wl_lines));
                    applyFilter();
                }
            });
        }, "wordlist-count").start();
    }

    private void renameDialog(Wordlist wordlist) {
        TextInputLayout layout = new TextInputLayout(context);
        TextInputEditText input = new TextInputEditText(context);
        input.setText(wordlist.getDisplayName());
        input.setSingleLine(true);
        layout.addView(input);
        layout.setPadding(48, 24, 48, 0);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wl_menu_rename)
                .setView(layout)
                .setPositiveButton(R.string.wl_save, (d, w) -> {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    if (name.trim().isEmpty()) return;
                    new Thread(() -> {
                        boolean ok = store.rename(wordlist, name);
                        if (activity == null) return;
                        activity.runOnUiThread(() -> {
                            if (!ok) toast(getString(R.string.wl_rename_failed));
                            reload();
                        });
                    }, "wordlist-rename").start();
                })
                .setNegativeButton(R.string.wl_cancel, null)
                .show();
    }

    private void categoryDialog(Wordlist wordlist) {
        WordlistCategory[] values = WordlistCategory.values();
        String[] titles = new String[values.length];
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            titles[i] = values[i].title;
            if (values[i] == wordlist.category) selected = i;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wl_menu_category)
                .setSingleChoiceItems(titles, selected, (d, which) -> {
                    store.setCategory(wordlist, values[which]);
                    d.dismiss();
                    applyFilter();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.wl_cancel, null)
                .show();
    }

    private void duplicate(Wordlist wordlist) {
        new Thread(() -> {
            Wordlist copy = store.duplicate(wordlist);
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                toast(copy == null ? getString(R.string.wl_copy_failed)
                        : getString(R.string.wl_copied, copy.getName()));
                reload();
            });
        }, "wordlist-duplicate").start();
    }

    private void share(Wordlist wordlist) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".provider", wordlist.file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.wl_menu_share)));
        } catch (Exception e) {
            toast(getString(R.string.wl_share_failed));
        }
    }

    private void deleteDialog(Wordlist wordlist) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wl_delete_title)
                .setMessage(getString(R.string.wl_delete_body, wordlist.getName()))
                .setPositiveButton(R.string.wl_menu_delete, (d, w) -> new Thread(() -> {
                    boolean gone = store.delete(wordlist);
                    if (activity == null) return;
                    activity.runOnUiThread(() -> {
                        if (!gone) toast(getString(R.string.wl_delete_failed));
                        reload();
                    });
                }, "wordlist-delete").start())
                .setNegativeButton(R.string.wl_cancel, null)
                .show();
    }

    private void importFrom(Uri uri) {
        if (uri == null) return;
        toast(getString(R.string.wl_importing));
        new Thread(() -> {
            String name = nameFromUri(uri);
            Wordlist wl = store.importFrom(uri, name, null);
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                toast(wl == null ? getString(R.string.wl_import_failed)
                        : getString(R.string.wl_imported, wl.getName()));
                reload();
            });
        }, "wordlist-import").start();
    }

    private String nameFromUri(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return "imported.txt";
        int slash = last.lastIndexOf('/');
        if (slash >= 0 && slash < last.length() - 1) last = last.substring(slash + 1);
        int colon = last.lastIndexOf(':');
        if (colon >= 0 && colon < last.length() - 1) last = last.substring(colon + 1);
        return last;
    }

    private void seedStarterLists() {
        toast(getString(R.string.wl_seeding));
        new Thread(() -> {
            for (String id : new String[] { "top10", "top250", "pins20", "pins250", "wifi", "users" }) {
                WordlistGenerator.Preset preset = WordlistGenerator.presetById(id);
                if (preset != null && !store.exists(preset.fileName)) {
                    WordlistGenerator.writePreset(store, preset);
                }
            }
            if (activity == null) return;
            activity.runOnUiThread(this::reload);
        }, "wordlist-seed").start();
    }

    private void toast(String message) {
        if (context != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
