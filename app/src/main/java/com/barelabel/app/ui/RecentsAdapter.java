package com.barelabel.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.barelabel.app.R;
import com.barelabel.app.model.ScannedProduct;
import com.barelabel.app.util.StringNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal "Recent scans" strip on the Search tab: newest first, verdict
 * pill per card, tap re-runs that product's search.
 */
public class RecentsAdapter extends RecyclerView.Adapter<RecentsAdapter.Holder> {

    public interface Listener {
        void onRecentClicked(ScannedProduct product);
    }

    private static final int C_GREEN_800 = 0xFF166534;
    private static final int C_RED_800 = 0xFF991B1B;

    private final List<ScannedProduct> items = new ArrayList<>();
    private final Listener listener;

    public RecentsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<ScannedProduct> products) {
        items.clear();
        if (products != null) {
            items.addAll(products);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final ScannedProduct p = items.get(position);
        holder.name.setText(StringNormalizer.toTitleCase(
                p.name == null ? "" : p.name));
        holder.brand.setText(StringNormalizer.toTitleCase(
                p.brand == null ? "" : p.brand));
        boolean clean = p.clean;
        holder.verdict.setText(clean ? "✓ CLEAN" : "⚠ DIRTY");
        holder.verdict.setTextColor(clean ? C_GREEN_800 : C_RED_800);
        holder.verdict.setBackgroundResource(clean
                ? R.drawable.chip_clean_background
                : R.drawable.chip_dirty_background);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRecentClicked(p);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView brand;
        final TextView verdict;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.recentName);
            brand = itemView.findViewById(R.id.recentBrand);
            verdict = itemView.findViewById(R.id.recentVerdict);
        }
    }
}
