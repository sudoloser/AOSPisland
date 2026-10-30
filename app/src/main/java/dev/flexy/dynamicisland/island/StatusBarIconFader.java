package dev.flexy.dynamicisland.island;

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class StatusBarIconFader {
    private static final String[] LEAF_IDS = {
            "clock", "battery", "networkSpeed", "knox_status_bar_custom_text_container",
            "user_switcher_container", "carrier_logo_container", "operator_name_frame",
    };
    private static final String[] GROUP_IDS = {
            "notificationIcons", "notification_icon_area", "statusIcons", "system_icons",
    };

    private final float density;
    private final Map<View, Fade> fades = new WeakHashMap<>();
    private final Map<View, DateFade> dateFades = new WeakHashMap<>();
    private final List<View> candidates = new ArrayList<>();
    private final int[] loc = new int[2];
    private final RectF island = new RectF();
    private final RectF iconRect = new RectF();
    private View statusBar;
    private View contents;
    private boolean islandActive;
    private boolean dirty = true;
    private boolean mobileLogged;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        dirty = true;
        apply();
    };

    StatusBarIconFader(float density) {
        this.density = density;
    }

    void attach(View phoneStatusBarView) {
        if (statusBar == phoneStatusBarView) return;
        detach();
        statusBar = phoneStatusBarView;
        mobileLogged = false;
        contents = find(phoneStatusBarView, "status_bar_contents");
        if (contents == null) contents = phoneStatusBarView;
        contents.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        dirty = true;
    }

    void detach() {
        if (contents != null) {
            try {
                contents.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            } catch (Throwable ignored) {
            }
        }
        resetAll();
        statusBar = null;
        contents = null;
        candidates.clear();
    }

    private boolean keepDate;

    void setKeepDate(boolean keep) {
        if (keepDate == keep) return;
        keepDate = keep;
        dirty = true;
    }

    void update(RectF coverage, boolean active) {
        if (!active && !islandActive && !dirty) return;
        islandActive = active;
        if (active) island.set(coverage);
        apply();
    }

    void resetAll() {
        for (Map.Entry<View, Fade> e : fades.entrySet()) {
            e.getValue().cancel();
            e.getKey().setTransitionAlpha(1f);
        }
        fades.clear();
        for (DateFade d : dateFades.values()) d.reset();
        dateFades.clear();
    }

    private void apply() {
        if (statusBar == null) return;
        if (dirty) collect();
        float margin = 6f * density;
        float hysteresis = 4f * density;
        for (int i = 0; i < candidates.size(); i++) {
            View v = candidates.get(i);
            Fade fade = fades.get(v);
            boolean hidden = fade != null && fade.target == 0f;
            boolean cover = false;
            boolean coverDate = false;
            float dateX = -1f;
            if (islandActive && v.isAttachedToWindow() && v.getWidth() > 0 && v.getVisibility() == View.VISIBLE) {
                v.getLocationOnScreen(loc);
                iconRect.set(loc[0], loc[1], loc[0] + v.getWidth() * v.getScaleX(), loc[1] + v.getHeight() * v.getScaleY());
                float m = hidden ? margin + hysteresis : margin;
                if ("clock".equals(idName(v))) m = hidden ? 3f * density : 2f * density;
                dateX = dateStart(v);
                boolean vertical = iconRect.bottom > island.top && iconRect.top < island.bottom;
                if (dateX > 0f) {
                    float split = iconRect.left + dateX * v.getScaleX();
                    DateFade df = dateFades.get(v);
                    float dm = df != null && df.target == 0f ? margin + hysteresis : margin;
                    coverDate = !keepDate && vertical && iconRect.right > island.left - dm && split < island.right + dm;
                    cover = vertical && split > island.left - m && iconRect.left < island.right + m;
                } else {
                    cover = vertical && iconRect.right > island.left - m && iconRect.left < island.right + m;
                }
            }
            if (dateX > 0f || dateFades.containsKey(v)) {
                DateFade df = dateFades.get(v);
                if (df == null) {
                    df = new DateFade((TextView) v);
                    dateFades.put(v, df);
                }
                if (dateX > 0f) df.split = dateX;
                df.animateTo(coverDate && !cover ? 0f : 1f);
            }
            float target = cover ? 0f : 1f;
            if (fade == null) {
                if (target == 1f && v.getTransitionAlpha() == 1f) continue;
                fade = new Fade(v);
                fades.put(v, fade);
            }
            fade.animateTo(target);
        }
    }

    private void collect() {
        dirty = false;
        List<View> fresh = new ArrayList<>();
        for (String id : LEAF_IDS) {
            View v = find(statusBar, id);
            if (v != null) fresh.add(v);
        }
        for (String id : GROUP_IDS) {
            View g = find(statusBar, id);
            if (!(g instanceof ViewGroup)) continue;
            ViewGroup vg = (ViewGroup) g;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (idName(child) != null && ("statusIcons".equals(idName(child)) || "battery".equals(idName(child)))) continue;
                if (isMobileGroup(child)) {
                    int before = fresh.size();
                    collectMobileLeaves(child, fresh);
                    if (!mobileLogged) {
                        mobileLogged = true;
                        StringBuilder names = new StringBuilder();
                        for (int j = before; j < fresh.size(); j++) names.append(idName(fresh.get(j))).append(' ');
                        dev.flexy.dynamicisland.xposed.XLog.i("independent mobile icons: " + names);
                    }
                    continue;
                }
                if ("system_icons".equals(id) && child instanceof ViewGroup && idName(child) != null) continue;
                if (!fresh.contains(child)) fresh.add(child);
            }
        }
        for (View old : candidates) {
            if (!fresh.contains(old)) {
                Fade f = fades.remove(old);
                if (f != null) f.cancel();
                old.setTransitionAlpha(1f);
            }
        }
        candidates.clear();
        candidates.addAll(fresh);
    }

    private static boolean isMobileGroup(View view) {
        String name = view.getClass().getSimpleName();
        String id = idName(view);
        return view instanceof ViewGroup && (name.contains("StatusBarMobileView")
                || "mobile_group".equals(id) || "mobile_combo".equals(id)
                || (find(view, "mobile_signal") != null && find(view, "mobile_type") != null));
    }

    private static void collectMobileLeaves(View view, List<View> out) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectMobileLeaves(group.getChildAt(i), out);
        } else if (!(view instanceof android.widget.Space) && !out.contains(view)) {
            out.add(view);
        }
    }

    static float timeEnd(View clock) {
        float x = dateStart(clock);
        return x > 0f ? x * clock.getScaleX() : -1f;
    }

    private static float dateStart(View v) {
        if (!(v instanceof TextView) || !"clock".equals(idName(v))) return -1f;
        TextView t = (TextView) v;
        CharSequence text = t.getText();
        android.text.Layout layout = t.getLayout();
        if (text == null || layout == null || t.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) return -1f;
        String str = text.toString();
        int gap = str.indexOf("  ");
        if (gap <= 0 || str.trim().length() <= gap) return -1f;
        float x = layout.getPrimaryHorizontal(gap) + t.getCompoundPaddingLeft() - t.getScrollX();
        return x > 0f && x < t.getWidth() ? x : -1f;
    }

    private static final Map<String, Integer> IDS = new java.util.HashMap<>();

    private static View find(View root, String idName) {
        Integer id = IDS.get(idName);
        if (id == null) {
            id = root.getResources().getIdentifier(idName, "id", "com.android.systemui");
            IDS.put(idName, id);
        }
        return id == 0 ? null : root.findViewById(id);
    }

    private static final Map<Integer, String> NAMES = new java.util.HashMap<>();

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return null;
        if (NAMES.containsKey(id)) return NAMES.get(id);
        String name;
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            name = null;
        }
        NAMES.put(id, name);
        return name;
    }

    private static final class DateFade extends android.graphics.drawable.Drawable {
        final TextView view;
        final android.graphics.Paint paint = new android.graphics.Paint();
        float split;
        float alpha = 1f;
        float target = 1f;
        ValueAnimator anim;
        boolean installed;

        DateFade(TextView view) {
            this.view = view;
            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        }

        void animateTo(float t) {
            if (t == target) return;
            target = t;
            if (anim != null) anim.cancel();
            if (alpha == t) return;
            install();
            anim = ValueAnimator.ofFloat(alpha, t);
            anim.setDuration(t == 0f ? 130 : 200);
            anim.setStartDelay(t == 0f ? 0 : 40);
            anim.setInterpolator(t == 0f ? Fade.OUT : Fade.IN);
            anim.addUpdateListener(a -> {
                alpha = (float) a.getAnimatedValue();
                view.invalidate();
                if (alpha == 1f && target == 1f) uninstall();
            });
            anim.start();
        }

        private void install() {
            if (installed) return;
            installed = true;
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            view.setForeground(this);
        }

        private void uninstall() {
            if (!installed) return;
            installed = false;
            if (view.getForeground() == this) view.setForeground(null);
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }

        void reset() {
            if (anim != null) anim.cancel();
            alpha = 1f;
            target = 1f;
            uninstall();
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            if (alpha >= 1f) return;
            paint.setAlpha(Math.round(255 * (1f - alpha)));
            c.drawRect(split, 0, view.getWidth(), view.getHeight(), paint);
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final class Fade {
        private static final PathInterpolator OUT = new PathInterpolator(0.4f, 0f, 1f, 1f);
        private static final PathInterpolator IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
        final View view;
        float target = 1f;
        ValueAnimator anim;

        Fade(View view) {
            this.view = view;
        }

        void animateTo(float t) {
            if (t == target && (anim != null || view.getTransitionAlpha() == t)) return;
            target = t;
            cancel();
            float from = view.getTransitionAlpha();
            if (from == t) return;
            anim = ValueAnimator.ofFloat(from, t);
            anim.setDuration(t == 0f ? 130 : 200);
            anim.setStartDelay(t == 0f ? 0 : 40);
            anim.setInterpolator(t == 0f ? OUT : IN);
            anim.addUpdateListener(a -> view.setTransitionAlpha((float) a.getAnimatedValue()));
            anim.start();
        }

        void cancel() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }
    }
}
