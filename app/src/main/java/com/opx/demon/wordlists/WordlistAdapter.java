package com.opx.demon.wordlists;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textview.MaterialTextView;
import com.opx.demon.R;

import java.util.List;

public class WordlistAdapter extends RecyclerView.Adapter<WordlistAdapter.Holder> {

    public interface Listener {
        void onOpen(Wordlist wordlist);
        void onMenu(Wordlist wordlist, View anchor);
    }

    private final Context context;
    private final List<Wordlist> items;
    private final Listener listener;

    public WordlistAdapter(Context context, List<Wordlist> items, Listener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_wordlist, parent, false);
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

        if (wl.note == null || wl.note.isEmpty()) {
            h.note.setVisibility(View.GONE);
        } else {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText(wl.note);
        }

        h.card.setOnClickListener(v -> listener.onOpen(wl));
        h.menu.setOnClickListener(v -> listener.onMenu(wl, v));
        h.card.setOnLongClickListener(v -> {
            listener.onMenu(wl, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final ImageView menu;
        final MaterialTextView name;
        final MaterialTextView meta;
        final MaterialTextView category;
        final MaterialTextView note;

        Holder(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.wl_item_card);
            icon = v.findViewById(R.id.wl_item_icon);
            menu = v.findViewById(R.id.wl_item_menu);
            name = v.findViewById(R.id.wl_item_name);
            meta = v.findViewById(R.id.wl_item_meta);
            category = v.findViewById(R.id.wl_item_category);
            note = v.findViewById(R.id.wl_item_note);
        }
    }
}
