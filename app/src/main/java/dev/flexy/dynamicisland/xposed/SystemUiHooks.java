package dev.flexy.dynamicisland.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.content.res.Resources;
import android.view.View;

import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import dev.flexy.dynamicisland.island.IslandController;
import dev.flexy.dynamicisland.island.IslandSettings;
import dev.flexy.dynamicisland.island.OaSource;

final class SystemUiHooks {
    private static final String SUI = "com.android.systemui";
    private static final String OA = "com.android.systemui.statusbar.phone.ongoingactivity.";
    private static final String CHIPS = "com.android.systemui.statusbar.chips.";
    private static final String SAMSUNG_MARKER = "com.android.systemui.battery.SamsungBatteryMeterDrawable";

    private static volatile Object oaController;
    private static volatile Object callController;
    private static volatile Object castController;
    private static volatile Object castBundle;
    private static volatile int barState;
    private static volatile boolean dozing;
    private static volatile int windowState;

    private static volatile View statusBarView;

    private static int idCapsule;
    private static int idCapsuleContainer;
    private static int idChipsContainer;
    private static int idCastChip;
    private static int idCallChip;
    private static int idChipPrimary;
    private static int idChipSecondary;

    private SystemUiHooks() {
    }

    static void install(ClassLoader cl) {
        hookInit(cl);
        hookStatusBarView(cl);
        hookAospChips(cl);
        hookCapsuleHiding(cl);
        hookDataChanges(cl);
        hookCalls(cl);
        hookCast(cl);
        hookSignals(cl);
        hookAndroid16Chips(cl);
        if (isSamsung(cl)) hookSamsungShade(cl);
        HideDuplicated.install(cl);
        TorchNotificationDelay.install(cl);
        XLog.i("SystemUI hooks installed");
    }

    /** One UI SystemUI ships classes AOSP does not have; the shade styling needs all of them. */
    private static boolean isSamsung(ClassLoader cl) {
        return Refl.findClass(SAMSUNG_MARKER, cl) != null;
    }

    private static void hookSamsungShade(ClassLoader cl) {
        ShadePerf.install(cl);
        ShadeLayoutCallbacks.install(cl);
        ShadeLiveCards.install(cl);
        ShadeCardTheme.install(cl);
        ShadeMediaPlayer.install(cl);
    }

    private static IslandController c() {
        return IslandController.get();
    }

    private static void refresh() {
        IslandController c = c();
        if (c != null) c.requestRefresh();
    }

    /** Keeps the stock chips in sync with the master and chip switches. */
    static void applyStockChipVisibility() {
        try {
            OaSource.applyStockChipVisibility(statusBarView);
        } catch (Throwable t) {
            XLog.e("stock chip visibility failed", t);
        }
    }

    private static void hookInit(ClassLoader cl) {
        XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate",
                SafeHook.before("app onCreate", p -> {
                    if (!(p.args[0] instanceof Application)) return;
                    Application app = (Application) p.args[0];
                    if (!SUI.equals(app.getPackageName())) return;
                    resolveIds(app.getResources());
                    IslandController c = IslandController.init(app, cl);
                    if (oaController != null) c.setOngoingActivityController(oaController);
                    if (callController != null) c.setCallController(callController);
                    if (castController != null) c.setCastController(castController);
                    if (castBundle != null) c.setCastBundle(castBundle);
                    c.setBarState(barState);
                    c.setDozing(dozing);
                    c.setStatusBarWindowState(windowState);
                }));
    }

    private static void resolveIds(Resources res) {
        idCapsule = res.getIdentifier("ongoing_activity_capsule", "id", SUI);
        idCapsuleContainer = res.getIdentifier("samsung_ongoing_activity_chip_container", "id", SUI);
        idChipsContainer = res.getIdentifier("samsung_status_bar_chips_container", "id", SUI);
        idCastChip = res.getIdentifier("samsung_ongoing_cast_chip", "id", SUI);
        idCallChip = res.getIdentifier("ongoing_call_chip", "id", SUI);
        idChipPrimary = res.getIdentifier("ongoing_activity_chip_primary", "id", SUI);
        idChipSecondary = res.getIdentifier("ongoing_activity_chip_secondary", "id", SUI);
    }

    private static void hookStatusBarView(ClassLoader cl) {
        String ctrl = "com.android.systemui.statusbar.phone.PhoneStatusBarViewController";
        Refl.hookAll(cl, ctrl, "onViewAttached", SafeHook.after("onViewAttached", p -> {
            Object v = Refl.get(p.thisObject, "mView");
            if (!(v instanceof View)) return;
            statusBarView = (View) v;
            applyStockChipVisibility();
            IslandController c = c();
            if (c != null) c.onStatusBarViewAttached((View) v);
        }));
        Refl.hookAll(cl, ctrl, "onViewDetached", SafeHook.after("onViewDetached", p -> {
            Object v = Refl.get(p.thisObject, "mView");
            if (!(v instanceof View)) return;
            if (statusBarView == v) statusBarView = null;
            IslandController c = c();
            if (c != null) c.onStatusBarViewDetached((View) v);
        }));
    }

    /**
     * AOSP 16 status bar chips: every chip (ongoing activity, promoted notification, call, screen
     * record, cast, share) is rendered through {@code OngoingActivityChipBinder.bind}, so this is
     * the single place that both feeds the island and hides the stock chip.
     */
    private static void hookAospChips(ClassLoader cl) {
        Refl.hookAll(cl, CHIPS + "ui.binder.OngoingActivityChipBinder", "bind",
                SafeHook.after("AOSP chip bind", p -> {
                    if (p.args.length >= 2) OaSource.onAospChip(p.args[0], p.args[1]);
                }));
    }

    private static void hookCapsuleHiding(ClassLoader cl) {
        Refl.hookAll(cl, "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl",
                "adjustVisibility", SafeHook.before("adjustVisibility", p -> {
                    if (p.args.length < 1 || !(p.args[0] instanceof View)) return;
                    View v = (View) p.args[0];
                    int id = v.getId();
                    if (id != View.NO_ID && (id == idCapsuleContainer || id == idChipsContainer
                            || id == idCastChip || id == idCallChip
                            || id == idChipPrimary || id == idChipSecondary)) {
                        boolean hide = IslandSettings.hidingChips();
                        v.animate().cancel();
                        v.setVisibility(hide ? View.GONE : View.VISIBLE);
                        if (hide) p.setResult(null);
                    }
                }));

        Refl.hookAll(cl, "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment", "animateShow",
                SafeHook.before("animateShow", p -> {
                    if (p.args.length < 1 || !(p.args[0] instanceof View)) return;
                    View v = (View) p.args[0];
                    int id = v.getId();
                    if (id != View.NO_ID && (id == idCastChip || id == idCallChip
                            || id == idChipPrimary || id == idChipSecondary)) {
                        boolean hide = IslandSettings.hidingChips();
                        v.animate().cancel();
                        v.setVisibility(hide ? View.GONE : View.VISIBLE);
                        if (hide) p.setResult(null);
                    }
                }));

        Refl.hookAll(cl, OA + "OngoingActivityController", "updateParentViewVisibility",
                SafeHook.before("updateParentViewVisibility", p -> {
                    Object ctrl = p.thisObject;
                    Object parent = Refl.get(ctrl, "mParentView");
                    if (parent instanceof View && IslandSettings.hidingChips()) {
                        ((View) parent).animate().cancel();
                        ((View) parent).setVisibility(View.GONE);
                    }
                    Object flow = Refl.get(ctrl, "showingOAChip");
                    if (flow != null) Refl.call(flow, "updateState", null, Boolean.FALSE);
                    p.setResult(null);
                    refresh();
                }));

        Refl.hookAll(cl, OA + "OngoingActivityController", "isChipExpandCondition",
                SafeHook.before("isChipExpandCondition", p -> p.setResult(Boolean.FALSE)));

        String anim = OA + "ChipAnimationController";
        Refl.hookAll(cl, anim, "handleOnGoingActivityChipAnimation",
                SafeHook.before("handleOnGoingActivityChipAnimation", p -> p.setResult(null)));
        Refl.hookAll(cl, anim, "clockViewNudgeAnimation",
                SafeHook.before("clockViewNudgeAnimation", p -> p.setResult(null)));
        Refl.hookAll(cl, anim, "animateChipShow", SafeHook.before("animateChipShow", p -> {
            if (p.args.length > 0 && p.args[0] instanceof View && ((View) p.args[0]).getId() == idCapsule) {
                ((View) p.args[0]).setVisibility(View.GONE);
                p.setResult(null);
            }
        }));
        Refl.hookAll(cl, anim, "handleOnGoingCallChipAnimation",
                SafeHook.before("handleOnGoingCallChipAnimation", p -> p.setResult(null)));

        Refl.hookAll(cl, OA + "OngoingActivityController", "sendChipInfoForAppExitAnimation",
                SafeHook.before("sendChipInfoForAppExitAnimation", p -> {
                    oaController = p.thisObject;
                    IslandController c = c();
                    if (c == null) return;
                    c.onExitTargetsRequested();
                    p.setResult(null);
                }));
    }

    /**
     * AOSP 16 call chip plus the older chip entry points. Call controller fields are identical on
     * AOSP, so the island can read them directly for the app name, icon, chronometer and intent.
     */
    private static void hookAndroid16Chips(ClassLoader cl) {
        String call = "com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController";
        Refl.hookConstructors(cl, call, SafeHook.after("call ctor (AOSP)", p -> {
            callController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCallController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("call change (AOSP)", p -> refresh());
        for (String m : new String[]{"sendStateChangeEvent", "updateChip", "removeChipInfo"}) {
            Refl.hookAll(cl, call, m, refreshHook);
        }
    }

    private static void hookDataChanges(ClassLoader cl) {
        Refl.hookConstructors(cl, OA + "OngoingActivityController", SafeHook.after("OA ctor", p -> {
            oaController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setOngoingActivityController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("OA change", p -> refresh());
        String[] ctrlMethods = {"onAsyncInflationFinished", "OaDataListChanged", "add$1", "update", "update$1$1",
                "remove", "updateMediaChipData", "removeOngoingActivityData", "killGhost"};
        for (String m : ctrlMethods) Refl.hookAll(cl, OA + "OngoingActivityController", m, refreshHook);
        String[] helperMethods = {"removeOngoingActivityByKey", "updateOngoingList", "notifyUpdateItemNowbarObservers",
                "notifyRemoveItemNowbarObservers", "setBaseActivityComponentName", "notifyRankingStateChanged"};
        for (String m : helperMethods) Refl.hookAll(cl, OA + "OngoingActivityDataHelper", m, refreshHook);
    }

    private static void hookCalls(ClassLoader cl) {
        String call = "com.android.systemui.statusbar.phone.ongoingcall.SamsungOngoingCallController";
        Refl.hookConstructors(cl, call, SafeHook.after("call ctor", p -> {
            if (callController != null) return;
            callController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCallController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("call change", p -> refresh());
        for (String m : new String[]{"sendStateChangeEvent", "updateChip", "removeChipInfo"}) {
            Refl.hookAll(cl, call, m, refreshHook);
        }
    }

    private static void hookCast(ClassLoader cl) {
        String cast = "com.android.systemui.statusbar.phone.ongoingcast.";
        Refl.hookConstructors(cl, cast + "SamsungOngoingCastViewModel$SamsungCastChipBundle",
                SafeHook.after("cast bundle", p -> {
                    castBundle = p.thisObject;
                    IslandController c = c();
                    if (c != null) c.setCastBundle(p.thisObject);
                }));
        Refl.hookConstructors(cl, cast + "SamsungOngoingCastController", SafeHook.after("cast ctor", p -> {
            castController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCastController(p.thisObject);
        }));
    }

    private static volatile boolean headsUpTracking;

    private static void hookSignals(ClassLoader cl) {
        Refl.hookConstructors(cl, "com.android.systemui.shade.ShadeExpansionStateManager",
                SafeHook.after("shade ctor", p -> {
                    Class<?> listener = Refl.findClass("com.android.systemui.shade.ShadeExpansionListener", cl);
                    if (listener == null) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        if ("onPanelExpansionChanged".equals(m.getName()) && a != null && a.length == 1) {
                            Object f = Refl.get(a[0], "fraction");
                            IslandController c = c();
                            if (!(f instanceof Float) || c == null) return null;
                            float fraction = (Float) f;
                            if (!Refl.getBool(a[0], "expanded", true) && !Refl.getBool(a[0], "tracking", false)) fraction = 0f;
                            c.setShadeExpansion(fraction);
                        }
                        return null;
                    });
                    Refl.call(p.thisObject, "addExpansionListener", proxy);
                }));
        Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardViewMediator", "onStartedGoingToSleep",
                SafeHook.after("started going to sleep", p -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    IslandController c = c();
                    if (c != null) c.onStartedGoingToSleep();
                })));
        Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardViewMediator", "onStartedWakingUp",
                SafeHook.after("started waking up", p -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    IslandController c = c();
                    if (c != null) c.onStartedWakingUp();
                })));
        Refl.hookConstructors(cl, "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
                SafeHook.after("heads-up manager", p -> {
                    java.lang.ref.WeakReference<Object> hum = new java.lang.ref.WeakReference<>(p.thisObject);
                    dev.flexy.dynamicisland.island.IslandView.headsUpProbe = () -> {
                        Object h = hum.get();
                        return headsUpTracking || (h != null && Boolean.TRUE.equals(Refl.call(h, "hasNotifications")));
                    };
                }));
        Refl.hookAll(cl, "com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper", "setTrackingHeadsUp",
                SafeHook.after("heads-up tracking", p -> {
                    if (p.args.length == 2 && p.args[1] instanceof Boolean) headsUpTracking = (Boolean) p.args[1];
                }));
        Refl.hookAll(cl, "com.android.systemui.shade.NotificationPanelViewController$ShadeHeadsUpChangedListener",
                "onHeadsUpPinnedModeChanged", SafeHook.after("heads-up pinned", p -> {
                    IslandController c = c();
                    if (c != null && p.args.length == 1 && p.args[0] instanceof Boolean) c.setHeadsUpPinned((Boolean) p.args[0]);
                }));
        Refl.hookAll(cl, "com.android.systemui.shade.ShadeExpansionStateManager", "updateStateInternal",
                SafeHook.after("shade state", p -> {
                    if (p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                    IslandController c = c();
                    if (c == null) return;
                    int state = (Integer) p.args[0];
                    c.setPanelState(state);
                    if (state == 0) c.setShadeExpansion(0f);
                }));

        Refl.hookConstructors(cl, "com.android.systemui.statusbar.StatusBarStateControllerImpl",
                SafeHook.after("state ctor", p -> {
                    Class<?> listener = Refl.findClass(
                            "com.android.systemui.plugins.statusbar.StatusBarStateController$StateListener", cl);
                    if (listener == null) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        IslandController c = c();
                        if ("onStateChanged".equals(m.getName()) && a != null && a.length == 1 && a[0] instanceof Integer) {
                            barState = (Integer) a[0];
                            if (c != null) c.setBarState(barState);
                        } else if ("onDozingChanged".equals(m.getName()) && a != null && a.length == 1) {
                            dozing = Boolean.TRUE.equals(a[0]);
                            if (c != null) c.setDozing(dozing);
                        }
                        return null;
                    });
                    Refl.call(p.thisObject, "addCallback", proxy);
                    Object state = Refl.get(p.thisObject, "mState");
                    if (state instanceof Integer) barState = (Integer) state;
                }));

        Refl.hookConstructors(cl, "com.android.systemui.statusbar.window.StatusBarWindowStateController",
                SafeHook.after("window state ctor", p -> {
                    if (!(p.args.length > 0 && Integer.valueOf(0).equals(p.args[0]))) return;
                    Class<?> listener = Refl.findClass("com.android.systemui.statusbar.window.StatusBarWindowStateListener", cl);
                    Object set = Refl.get(p.thisObject, "listeners");
                    if (listener == null || !(set instanceof Set)) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        if ("onStatusBarWindowStateChanged".equals(m.getName()) && a != null && a.length == 1
                                && a[0] instanceof Integer) {
                            windowState = (Integer) a[0];
                            IslandController c = c();
                            if (c != null) c.setStatusBarWindowState(windowState);
                        }
                        return null;
                    });
                    @SuppressWarnings("unchecked")
                    Set<Object> s = (Set<Object>) set;
                    s.add(proxy);
                }));
    }
}
