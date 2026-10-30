package dev.flexy.dynamicisland.xposed;

import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ShadeLayoutCallbacks {
    private static final String OWNER = "com.samsung.android.app.facewidget.FaceWidgetNotificationController";
    private static final ThreadLocal<ArrayDeque<ViewTreeObserver>> DISPATCH =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<Class<?>, Boolean> DECORATORS = new ConcurrentHashMap<>();
    private static int repaired;

    private ShadeLayoutCallbacks() { }

    static void install(ClassLoader cl) {
        if (Refl.findClass(OWNER, cl) == null) return;
        Refl.hookAll(ViewTreeObserver.class, "dispatchOnGlobalLayout", new SafeHook("shade layout dispatch") {
            @Override protected void before(MethodHookParam p) {
                enter((ViewTreeObserver) p.thisObject);
            }
            @Override protected void after(MethodHookParam p) {
                leave();
            }
        });
        Refl.hookAll(ViewTreeObserver.class, "removeOnGlobalLayoutListener",
                SafeHook.before("shade decorator removal", p -> {
                    if (p.args.length != 1 || !(p.args[0] instanceof ViewTreeObserver.OnGlobalLayoutListener)) return;
                    ViewTreeObserver.OnGlobalLayoutListener listener =
                            (ViewTreeObserver.OnGlobalLayoutListener) p.args[0];
                    if (!isDecorator(listener.getClass())) return;
                    if (removeFromDispatch((ViewTreeObserver) p.thisObject, listener)) {
                        p.setResult(null);
                        int count = ++repaired;
                        if (count == 1 || count % 32 == 0) XLog.i("shade decorator callbacks repaired=" + count);
                    }
                }));
    }

    private static boolean isDecorator(Class<?> type) {
        return DECORATORS.computeIfAbsent(type, cls -> {
            for (Field field : cls.getDeclaredFields()) {
                if (OWNER.equals(field.getType().getName())) return true;
            }
            return false;
        });
    }

    static void enter(ViewTreeObserver observer) {
        DISPATCH.get().push(observer);
    }

    static void leave() {
        DISPATCH.get().pop();
    }

    static boolean removeFromDispatch(ViewTreeObserver requested,
                                     ViewTreeObserver.OnGlobalLayoutListener listener) {
        ViewTreeObserver active = DISPATCH.get().peek();
        if (active == null || active == requested || !active.isAlive()) return false;
        active.removeOnGlobalLayoutListener(listener);
        return true;
    }
}
