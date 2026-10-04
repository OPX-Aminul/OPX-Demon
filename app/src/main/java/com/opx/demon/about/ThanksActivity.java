package com.opx.demon.about;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.opx.demon.R;

public class ThanksActivity extends AppCompatActivity {

    private ThanksBackgroundView background;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_thanks);

        background = findViewById(R.id.thanks_bg);

        MaterialToolbar toolbar = findViewById(R.id.thanks_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        applyWindowInsets(toolbar);

        MaterialButton close = findViewById(R.id.thanks_close);
        close.setOnClickListener(v -> finish());

        fillPeople();
        playEntrance();
    }

    private void fillPeople() {
        LinearLayout container = findViewById(R.id.thanks_people);
        LayoutInflater inflater = LayoutInflater.from(this);
        int accent = ContextCompat.getColor(this, R.color.opxdemon_accent);

        for (String name : getResources().getStringArray(R.array.thanks_people)) {
            View row = inflater.inflate(R.layout.thanks_person_row, container, false);
            TextView label = row.findViewById(R.id.thanks_person_name);
            label.setText(name);

            View dot = row.findViewById(R.id.thanks_person_dot);
            if (dot.getBackground() != null) {
                dot.getBackground().mutate().setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            }
            container.addView(row);
        }
    }

    private void playEntrance() {
        int[] ids = {R.id.thanks_title, R.id.thanks_subtitle, R.id.thanks_section,
                R.id.thanks_people, R.id.thanks_community, R.id.thanks_close};
        float shift = 28f * getResources().getDisplayMetrics().density;

        long delay = 700L;
        for (int id : ids) {
            View block = findViewById(id);
            if (block == null) continue;
            block.setAlpha(0f);
            block.setTranslationY(shift);
            block.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(520L)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            delay += 90L;
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (background != null) background.replayIntro();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void applyWindowInsets(View toolbar) {
        View scroller = findViewById(R.id.thanks_scroll);
        final int scrollTop = scroller.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    ViewGroup.MarginLayoutParams lp =
                            (ViewGroup.MarginLayoutParams) toolbar.getLayoutParams();
                    lp.topMargin = bars.top;
                    toolbar.setLayoutParams(lp);
                    scroller.setPadding(scroller.getPaddingLeft(), scrollTop + bars.top,
                            scroller.getPaddingRight(), bars.bottom);
                    return windowInsets;
                });
    }
}
