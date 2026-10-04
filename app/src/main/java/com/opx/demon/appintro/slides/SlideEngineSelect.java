package com.opx.demon.appintro.slides;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.opx.demon.R;
import com.opx.demon.appintro.AppIntroActivity;
import com.opx.demon.engine.DeviceCapabilities;
import com.opx.demon.engine.EngineType;
import com.opx.demon.utils.Core;

import java.util.List;

public class SlideEngineSelect extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private MaterialCardView cardRootless;
    private MaterialCardView cardUml;
    private MaterialCardView cardChroot;
    private ImageView checkRootless;
    private ImageView checkUml;
    private ImageView checkChroot;

    private EngineType selected = EngineType.CHROOT;
    private boolean rootlessSupported;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_engine, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        cardRootless = view.findViewById(R.id.card_rootless);
        cardUml = view.findViewById(R.id.card_uml);
        cardChroot = view.findViewById(R.id.card_chroot);
        checkRootless = view.findViewById(R.id.check_rootless);
        checkUml = view.findViewById(R.id.check_uml);
        checkChroot = view.findViewById(R.id.check_chroot);
        android.widget.TextView rootlessNote = view.findViewById(R.id.rootless_note);
        android.widget.TextView umlNote = view.findViewById(R.id.uml_note);
        MaterialButton continueBtn = view.findViewById(R.id.login);

        rootlessSupported = EngineType.rootlessSupported(context);

        if (rootlessSupported) {
            selected = EngineType.ROOTLESS;
            cardRootless.setOnClickListener(v -> select(EngineType.ROOTLESS));
            cardUml.setOnClickListener(v -> select(EngineType.ROOTLESS_UML));
        } else {
            rootlessNote.setVisibility(View.VISIBLE);
            umlNote.setVisibility(View.VISIBLE);
            cardRootless.setAlpha(0.5f);
            cardUml.setAlpha(0.5f);
            selected = EngineType.CHROOT;
        }
        cardChroot.setOnClickListener(v -> select(EngineType.CHROOT));

        // What this phone can actually run, decided by DeviceCapabilities:
        // it launched the UML kernel and checked ABI, RAM, storage and network
        // before it got here. Without this the user picks blind and only finds
        // out minutes into a boot attempt.
        EngineType recommended = DeviceCapabilities.recommended(core);
        if (recommended != null && (rootlessSupported || recommended == EngineType.CHROOT)) {
            selected = recommended;
            showRecommendation(recommended, rootlessNote, umlNote);
        }
        if (umlRuledOut()) {
            // Probed and refused: stop offering it rather than letting the user
            // pick an engine this phone has already been shown it cannot run.
            umlNote.setVisibility(View.VISIBLE);
            umlNote.setText(R.string.engine_uml_blocked);
            cardUml.setAlpha(0.4f);
            cardUml.setOnClickListener(null);
            if (selected == EngineType.ROOTLESS_UML) selected = EngineType.ROOTLESS;
        }

        applySelectionUi();

        continueBtn.setOnClickListener(v -> {
            EngineType.persist(core, selected);
            ((AppIntroActivity) activity).applyEngineFlow(selected);
            mPager.post(() -> core.moveNext(mPager));
        });
        return view;
    }

    private void select(EngineType type) {
        if (type == EngineType.ROOTLESS_UML && umlRuledOut()) return;
        if ((type == EngineType.ROOTLESS || type == EngineType.ROOTLESS_UML)
                && !rootlessSupported) return;
        selected = type;
        applySelectionUi();
    }

    /**
     * True when a completed probe tried UML on this phone and the kernel did
     * not start. An empty plan means the probe never ran, which is NOT a
     * verdict — the option must stay available in that case.
     */
    private boolean umlRuledOut() {
        List<EngineType> detected = DeviceCapabilities.plan(core);
        return !detected.isEmpty()
                && !detected.contains(EngineType.CHROOT)
                && !detected.contains(EngineType.ROOTLESS_UML);
    }

    /** Marks the probed-recommended engine in the note under its card. */
    private void showRecommendation(EngineType recommended,
                                     android.widget.TextView rootlessNote,
                                     android.widget.TextView umlNote) {
        String text = context.getString(R.string.engine_recommended,
                context.getString(labelFor(recommended)));
        android.widget.TextView target =
                recommended == EngineType.ROOTLESS_UML ? umlNote : rootlessNote;
        target.setVisibility(View.VISIBLE);
        target.setText(text);
    }

    private static int labelFor(EngineType type) {
        if (type == EngineType.ROOTLESS_UML) return R.string.engine_uml;
        if (type == EngineType.ROOTLESS) return R.string.engine_vm;
        return R.string.engine_chroot;
    }

    private void applySelectionUi() {
        boolean rootless = selected == EngineType.ROOTLESS;
        boolean uml = selected == EngineType.ROOTLESS_UML;
        boolean vm = rootless || uml;
        checkRootless.setVisibility(rootless ? View.VISIBLE : View.INVISIBLE);
        checkUml.setVisibility(uml ? View.VISIBLE : View.INVISIBLE);
        checkChroot.setVisibility(vm ? View.INVISIBLE : View.VISIBLE);
        int accent = ContextCompat.getColor(context, R.color.opxdemon_accent);
        int idle = ContextCompat.getColor(context, R.color.light_lite_contrast);
        styleCard(cardRootless, rootless, accent, idle);
        styleCard(cardUml, uml, accent, idle);
        styleCard(cardChroot, !vm, accent, idle);
    }

    private void styleCard(MaterialCardView card, boolean selectedCard, int accent, int idle) {
        card.setStrokeColor(selectedCard ? accent : idle);
        float density = getResources().getDisplayMetrics().density;
        card.setStrokeWidth((int) (density * (selectedCard ? 2 : 1)));
        float scale = selectedCard ? 1f : 0.97f;
        card.animate().scaleX(scale).scaleY(scale)
                .setDuration(getResources().getInteger(R.integer.motion_short))
                .start();
    }
}
