package dev.flexy.dynamicisland.island;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.XLog;

@SuppressLint("ViewConstructor")
final class BlurLayer extends FrameLayout implements IslandView.BlurSink {
    private static final int RADIUS = 120;

    private static Constructor<?> builderCtor;
    private static Method setRadius;
    private static Method setCorner;
    private static Method setColor;
    private static Method build;
    private static Method semSetBlurInfo;
    private static Method semSetCorner;
    private static Boolean supported;

    private final WindowManager wm;
    private boolean aospBlur;
    private final Map<Object, Slot> slots = new IdentityHashMap<>();
    private final Set<Object> placed = new HashSet<>();
    private final java.util.ArrayDeque<Slot> free = new java.util.ArrayDeque<>();
    private static final int POOL = 6;
    private static final float PARKED_ALPHA = 0.004f;
    private IslandView.CardPainter painter;

    BlurLayer(Context context) {
        super(context);
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        aospBlur = !isSupported() && aospCapable();
        setClipChildren(false);
        setWillNotDraw(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getChildCount() == 0 && (isSupported() || aospBlur)) {
            for (int i = 0; i < POOL; i++) {
                Slot s = new Slot(getContext());
                addView(s, new LayoutParams(0, 0));
                s.park();
                free.add(s);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }

    /** Samsung exposes SemBlurInfo; AOSP blurs through a background blur drawable instead. */
    static boolean isSupported() {
        if (supported == null) {
            try {
                Class<?> b = Class.forName("android.view.SemBlurInfo$Builder");
                Class<?> info = Class.forName("android.view.SemBlurInfo");
                builderCtor = b.getConstructor(int.class);
                setRadius = b.getMethod("setRadius", int.class);
                setCorner = b.getMethod("setBackgroundCornerRadius", float.class);
                setColor = b.getMethod("setBackgroundColor", int.class);
                build = b.getMethod("build");
                semSetBlurInfo = View.class.getMethod("semSetBlurInfo", info);
                try {
                    semSetCorner = View.class.getMethod("semSetBackgroundBlurCornerRadius", float.class);
                } catch (Throwable ignored) {
                    semSetCorner = null;
                }
                supported = true;
            } catch (Throwable t) {
                supported = false;
            }
        }
        return supported;
    }

    private static boolean aospCapable() {
        return systemBoolean("ro.surface_flinger.supports_background_blur", false)
                && !systemBoolean("persist.sysui.disableBlur", false);
    }

    private static boolean systemBoolean(String key, boolean def) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getDeclaredMethod("getBoolean", String.class, boolean.class);
            get.setAccessible(true);
            Object v = get.invoke(null, key, def);
            return v instanceof Boolean ? (Boolean) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private boolean crossWindowBlurEnabled() {
        try {
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void disableAospBlur() {
        if (!aospBlur) return;
        aospBlur = false;
        for (Slot slot : slots.values()) slot.clearBlur();
        for (Slot slot : free) slot.clearBlur();
    }

    @Override
    public boolean available() {
        return isSupported() || (aospBlur && crossWindowBlurEnabled());
    }

    @Override
    public void setPainter(IslandView.CardPainter p) {
        painter = p;
    }

    @Override
    public void beginFrame() {
        placed.clear();
    }

    private static final long SUSPEND_DELAY_MS = 400;
    private final Runnable suspend = () -> {
        if (placed.isEmpty() && getVisibility() == VISIBLE) setVisibility(GONE);
    };

    @Override
    public void place(Object owner, RectF r, float corner, float alpha, int radius) {
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        removeCallbacks(suspend);
        Slot s = slots.get(owner);
        if (s == null) {
            s = free.poll();
            if (s == null) {
                s = new Slot(getContext());
                addView(s, new LayoutParams(0, 0));
            }
            s.owner = owner;
            slots.put(owner, s);
        }
        placed.add(owner);
        s.update(r, corner, alpha, radius > 0 ? radius : RADIUS);
    }

    @Override
    public void endFrame() {
        for (Iterator<Map.Entry<Object, Slot>> it = slots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Slot> e = it.next();
            if (placed.contains(e.getKey())) continue;
            e.getValue().hide();
        }
        if (placed.isEmpty() && getVisibility() == VISIBLE) {
            removeCallbacks(suspend);
            postDelayed(suspend, SUSPEND_DELAY_MS);
        }
    }

    private static java.lang.reflect.Method drawableCorner;

    @Override
    public void release(Object owner) {
        Slot s = slots.remove(owner);
        if (s != null) {
            s.owner = null;
            s.park();
            free.add(s);
        }
    }

    private final class Slot extends View {
        private Object owner;
        private boolean blurSet;
        private float corner = -1f;
        private int radius = RADIUS;
        private int appliedRadius = -1;
        private Drawable aospDrawable;

        Slot(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ensureAospBlur();
        }

        void update(RectF r, float c, float alpha, int blurRadius) {
            radius = blurRadius;
            if (r.width() < 2 || r.height() < 2) {
                hide();
                return;
            }
            setLeftTopRightBottom(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            setAlpha(Math.max(PARKED_ALPHA, alpha));
            if (isSupported()) applyBlur(c);
            else if (aospBlur) applyAospBlur(c);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            invalidate();
        }

        void park() {
            setLeftTopRightBottom(0, 0, 2, 2);
            setAlpha(PARKED_ALPHA);
            if (isSupported()) applyBlur(1f);
            else if (aospBlur) applyAospBlur(1f);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            invalidate();
        }

        private void ensureAospBlur() {
            if (aospDrawable != null || !aospBlur) return;
            try {
                Object viewRoot = dev.flexy.dynamicisland.xposed.Refl.call(this, "getViewRootImpl");
                Object created = viewRoot == null ? null
                        : dev.flexy.dynamicisland.xposed.Refl.call(viewRoot, "createBackgroundBlurDrawable");
                if (!(created instanceof Drawable)) {
                    disableAospBlur();
                    return;
                }
                Drawable d = (Drawable) created;
                dev.flexy.dynamicisland.xposed.Refl.call(d, "setBlurRadius", RADIUS);
                dev.flexy.dynamicisland.xposed.Refl.call(d, "setCornerRadius", 1f);
                dev.flexy.dynamicisland.xposed.Refl.call(d, "setColor", 0x14000000);
                setBackground(d);
                aospDrawable = d;
            } catch (Throwable t) {
                XLog.e("AOSP blur setup failed", t);
                disableAospBlur();
            }
        }

        private void applyAospBlur(float c) {
            ensureAospBlur();
            if (aospDrawable == null) return;
            if (Math.abs(c - corner) < 1f) return;
            try {
                dev.flexy.dynamicisland.xposed.Refl.call(aospDrawable, "setCornerRadius", c);
                corner = c;
            } catch (Throwable t) {
                XLog.e("AOSP blur corner failed", t);
                disableAospBlur();
            }
        }

        void clearBlur() {
            if (aospDrawable == null) return;
            aospDrawable.setAlpha(0);
            setBackground(null);
            aospDrawable = null;
            blurSet = false;
            corner = -1f;
        }

        private void applyBlur(float c) {
            boolean same = blurSet && appliedRadius == radius;
            if (Math.abs(c - corner) < 1f && same) return;
            if (same && setDrawableCorner(c)) {
                corner = c;
                return;
            }
            try {
                if (true) {
                    Object b = builderCtor.newInstance(0);
                    setRadius.invoke(b, radius);
                    setCorner.invoke(b, c);
                    setColor.invoke(b, radius == RADIUS ? 0x14000000 : 0);
                    semSetBlurInfo.invoke(this, build.invoke(b));
                    blurSet = true;
                    appliedRadius = radius;
                } else {
                    semSetCorner.invoke(this, c);
                }
                corner = c;
            } catch (Throwable t) {
                XLog.e("blur update failed", t);
                supported = false;
            }
        }

        private boolean setDrawableCorner(float c) {
            android.graphics.drawable.Drawable bg = getBackground();
            if (bg == null || !bg.getClass().getName().endsWith("BackgroundBlurDrawable")) return false;
            try {
                if (drawableCorner == null) drawableCorner = bg.getClass().getMethod("setCornerRadius", float.class);
                drawableCorner.invoke(bg, c);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (painter != null && owner != null) painter.paintCard(owner, canvas, getLeft(), getTop());
        }

        void hide() {
            park();
        }
    }
}
