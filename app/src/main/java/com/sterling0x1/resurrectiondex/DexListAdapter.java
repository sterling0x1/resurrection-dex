package com.sterling0x1.resurrectiondex;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class DexListAdapter extends BaseAdapter {
    private final Context context;
    private List<PokemonEntry> entries = new ArrayList<>();

    DexListAdapter(Context context) {
        this.context = context;
    }

    void setEntries(List<PokemonEntry> entries) {
        this.entries = entries == null ? new ArrayList<>() : entries;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return entries.size(); }
    @Override public PokemonEntry getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return entries.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout root;
        TextView name;
        TextView types;
        if (convertView instanceof LinearLayout && convertView.getTag() instanceof TextView[]) {
            root = (LinearLayout) convertView;
            TextView[] views = (TextView[]) root.getTag();
            name = views[0];
            types = views[1];
        } else {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            int h = dp(8);
            root.setPadding(dp(10), h, dp(8), h);

            name = new TextView(context);
            name.setTextColor(Color.WHITE);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            root.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            types = new TextView(context);
            types.setTextColor(Color.rgb(180, 186, 198));
            types.setTextSize(11);
            root.addView(types, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.setTag(new TextView[]{name, types});
        }

        PokemonEntry item = getItem(position);
        name.setText(item.displayId() + "  " + item.name);
        types.setText(item.typeLine());
        root.setBackgroundColor(position % 2 == 0 ? Color.rgb(27, 30, 37) : Color.rgb(32, 35, 43));
        return root;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
