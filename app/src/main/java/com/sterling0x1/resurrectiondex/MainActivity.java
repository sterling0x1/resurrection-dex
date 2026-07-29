package com.sterling0x1.resurrectiondex;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 1001;

    private final DexRepository repository = new DexRepository();
    private final TypeChart typeChart = new TypeChart();
    private final List<PokemonEntry> allEntries = new ArrayList<>();
    private final List<PokemonEntry> visibleEntries = new ArrayList<>();

    private DexListAdapter adapter;
    private ListView listView;
    private EditText search;
    private LinearLayout detail;
    private TextView sourceLabel;
    private SpriteRepository spriteRepository;
    private PokemonEntry selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        spriteRepository = new SpriteRepository(this);
        setContentView(buildUi());
        hideSystemBars();
        loadActiveData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 22));

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(Color.rgb(22, 24, 30));
        body.addView(left, new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.MATCH_PARENT));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search Pokémon…");
        search.setHintTextColor(Color.rgb(140, 146, 158));
        search.setTextColor(Color.WHITE);
        search.setTextSize(14);
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setBackground(rounded(Color.rgb(38, 42, 51), dp(8), Color.rgb(75, 80, 92), 1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        );
        searchParams.setMargins(dp(8), dp(8), dp(8), dp(6));
        left.addView(search, searchParams);

        listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setFastScrollEnabled(true);
        adapter = new DexListAdapter(this);
        listView.setAdapter(adapter);
        left.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        View separator = new View(this);
        separator.setBackgroundColor(Color.rgb(65, 69, 80));
        body.addView(separator, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(16), dp(12), dp(16), dp(18));
        scroll.addView(detail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        body.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        listView.setOnItemClickListener((parent, view, position, id) -> select(visibleEntries.get(position)));
        return root;
    }

    private View buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.rgb(198, 45, 45));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("RESURRECTION DEX");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBlock.addView(title);

        sourceLabel = new TextView(this);
        sourceLabel.setText("Loading…");
        sourceLabel.setTextColor(Color.rgb(255, 220, 220));
        sourceLabel.setTextSize(10);
        titleBlock.addView(sourceLabel);
        toolbar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button importButton = new Button(this);
        importButton.setAllCaps(false);
        importButton.setText("Import CSV / JSON");
        importButton.setTextSize(12);
        importButton.setTextColor(Color.WHITE);
        importButton.setBackground(rounded(Color.rgb(130, 26, 26), dp(7), Color.rgb(255, 150, 150), 1));
        importButton.setOnClickListener(v -> openImporter());
        toolbar.addView(importButton, new LinearLayout.LayoutParams(dp(150), dp(36)));
        return toolbar;
    }

    private void loadActiveData() {
        try {
            applyLoad(repository.loadActive(this));
        } catch (IOException | JSONException e) {
            sourceLabel.setText("Data error");
            showEmpty("Could not load the Pokédex data pack.\n\n" + e.getMessage());
        }
    }

    private void applyLoad(DexRepository.LoadResult result) {
        allEntries.clear();
        allEntries.addAll(result.entries);
        String spriteSummary = spriteRepository.count() > 0
                ? " • " + spriteRepository.count() + " sprites"
                : "";
        sourceLabel.setText(result.sourceName + " • " + allEntries.size()
                + " entries" + spriteSummary);
        search.setText("");
        applyFilter("");
        if (!visibleEntries.isEmpty()) {
            listView.post(() -> {
                listView.setSelection(0);
                select(visibleEntries.get(0));
            });
        } else {
            showEmpty("No Pokémon were found in this data pack.");
        }
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleEntries.clear();
        for (PokemonEntry entry : allEntries) {
            if (needle.isEmpty()
                    || entry.name.toLowerCase(Locale.ROOT).contains(needle)
                    || String.valueOf(entry.id).contains(needle)
                    || entry.typeLine().toLowerCase(Locale.ROOT).contains(needle)) {
                visibleEntries.add(entry);
            }
        }
        adapter.setEntries(new ArrayList<>(visibleEntries));
        if (!visibleEntries.isEmpty() && (selected == null || !visibleEntries.contains(selected))) {
            select(visibleEntries.get(0));
        }
    }

    private void select(PokemonEntry entry) {
        selected = entry;
        int index = visibleEntries.indexOf(entry);
        if (index >= 0) listView.setItemChecked(index, true);
        renderDetail(entry);
    }

    private void renderDetail(PokemonEntry entry) {
        detail.removeAllViews();

        detail.addView(buildIdentityHeader(entry));

        TypeChart.Matchups matchups = typeChart.defend(entry.types);
        addSection("WEAK TO", joinOrDash(matchups.weak));
        addSection("RESISTS", joinOrDash(matchups.resist));
        if (!matchups.immune.isEmpty()) addSection("IMMUNE", String.join("   ", matchups.immune));

        if (entry.stats.isKnown()) {
            addHeading("BASE STATS");
            addStat("HP", entry.stats.hp);
            addStat("ATK", entry.stats.attack);
            addStat("DEF", entry.stats.defense);
            addStat("SP. ATK", entry.stats.spAttack);
            addStat("SP. DEF", entry.stats.spDefense);
            addStat("SPEED", entry.stats.speed);
        }

        List<String> abilityLines = new ArrayList<>(entry.abilities);
        if (!entry.hiddenAbility.isEmpty()) abilityLines.add("Hidden: " + entry.hiddenAbility);
        if (!abilityLines.isEmpty()) addSection("ABILITIES", String.join("  •  ", abilityLines));
        if (!entry.evolutions.isEmpty()) addSection("EVOLUTION", String.join("\n", entry.evolutions));
        if (!entry.moves.isEmpty()) addSection("MOVE SAMPLE", String.join("  •  ", entry.moves));
        if (!entry.description.isEmpty()) addSection("NOTES", entry.description);

        if (!entry.hasRichData()) {
            TextView note = text(
                    "Basic CSV profile loaded. Types and defensive matchups are complete. " +
                            "Stats, abilities, evolutions, encounters and learnsets will appear when a full Resurrection JSON pack is imported.",
                    12, Color.rgb(170, 176, 188), Typeface.NORMAL
            );
            note.setPadding(0, dp(16), 0, 0);
            detail.addView(note);
        }
    }


    private View buildIdentityHeader(PokemonEntry entry) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);

        TextView id = text(entry.displayId(), 13, Color.rgb(170, 176, 188), Typeface.BOLD);
        identity.addView(id);

        TextView name = text(entry.name.toUpperCase(Locale.ROOT), 27, Color.WHITE, Typeface.BOLD);
        identity.addView(name);

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setPadding(0, dp(5), 0, dp(8));
        for (String type : entry.types) badges.addView(typeBadge(type));
        identity.addView(badges);

        header.addView(identity, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        FrameLayout spriteFrame = new FrameLayout(this);
        spriteFrame.setPadding(dp(6), dp(6), dp(6), dp(6));
        spriteFrame.setBackground(rounded(
                Color.rgb(27, 30, 37),
                dp(10),
                Color.rgb(68, 73, 86),
                1
        ));

        Drawable sprite = spriteRepository.frontFor(entry);
        if (sprite != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(sprite);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            spriteFrame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));
        } else {
            TextView missing = text("NO\nSPRITE", 10, Color.rgb(115, 121, 134), Typeface.BOLD);
            missing.setGravity(Gravity.CENTER);
            spriteFrame.addView(missing, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));
        }

        LinearLayout.LayoutParams spriteParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        spriteParams.setMargins(dp(12), 0, 0, dp(4));
        header.addView(spriteFrame, spriteParams);
        return header;
    }

    private void addHeading(String value) {
        TextView heading = text(value, 13, Color.rgb(235, 105, 105), Typeface.BOLD);
        heading.setPadding(0, dp(13), 0, dp(4));
        detail.addView(heading);
    }

    private void addSection(String heading, String body) {
        addHeading(heading);
        TextView value = text(body, 13, Color.rgb(226, 229, 235), Typeface.NORMAL);
        value.setLineSpacing(0, 1.08f);
        detail.addView(value);
    }

    private void addStat(String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView name = text(label, 11, Color.rgb(185, 190, 200), Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(dp(58), dp(22)));

        TextView number = text(String.valueOf(value), 12, Color.WHITE, Typeface.BOLD);
        number.setGravity(Gravity.CENTER);
        row.addView(number, new LinearLayout.LayoutParams(dp(38), dp(22)));

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(255);
        bar.setProgress(Math.max(0, value));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(statColor(value)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 62)));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(12), 1f));
        detail.addView(row);
    }

    private View typeBadge(String type) {
        TextView badge = text(type.toUpperCase(Locale.ROOT), 11, Color.WHITE, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(rounded(TypeColors.color(type), dp(12), Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        params.setMargins(0, 0, dp(6), 0);
        badge.setLayoutParams(params);
        return badge;
    }

    private void showEmpty(String message) {
        detail.removeAllViews();
        TextView empty = text(message, 15, Color.rgb(210, 214, 222), Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        detail.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
    }

    private void openImporter() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/csv", "text/comma-separated-values", "text/plain", "application/json"
        });
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            applyLoad(repository.importDocument(this, uri));
            Toast.makeText(this, "Pokédex imported and saved for offline use.", Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP:
                moveSelection(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                moveSelection(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_SEARCH:
                search.requestFocus();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (search.getText().length() > 0) {
                    search.setText("");
                    listView.requestFocus();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                int position = listView.getSelectedItemPosition();
                if (position >= 0 && position < visibleEntries.size()) {
                    select(visibleEntries.get(position));
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void moveSelection(int delta) {
        if (visibleEntries.isEmpty()) return;
        int current = selected == null ? 0 : visibleEntries.indexOf(selected);
        if (current < 0) current = 0;
        int next = Math.max(0, Math.min(visibleEntries.size() - 1, current + delta));
        listView.setSelection(next);
        select(visibleEntries.get(next));
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private int statColor(int value) {
        if (value >= 120) return Color.rgb(75, 190, 110);
        if (value >= 90) return Color.rgb(145, 190, 75);
        if (value >= 60) return Color.rgb(225, 175, 60);
        return Color.rgb(220, 85, 75);
    }

    private static String joinOrDash(List<String> values) {
        return values.isEmpty() ? "—" : String.join("   ", values);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
