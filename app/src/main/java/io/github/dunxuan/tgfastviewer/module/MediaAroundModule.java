package io.github.dunxuan.tgfastviewer.module;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Entry point. Everything here is libxposed API 102: hooks carry stable ids so a hot reload can
 * find them again, the discovery thread is module-owned and therefore stopped before the old
 * generation retires, and the target class loader is handed to the new generation through
 * {@code setSavedInstanceState} because package callbacks are not replayed after a reload.
 */
public final class MediaAroundModule extends XposedModule {

    static final String TAG = "TgFastViewer";
    private static final String SCOPE = "org.telegram.messenger";

    private static volatile MediaAroundModule self;
    private static volatile ClassLoader targetLoader;

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile Thread discovery;
    private volatile boolean retiring;

    public MediaAroundModule() {
        self = this;
    }

    static MediaAroundModule module() {
        return self;
    }

    static void logInfo(String message) {
        MediaAroundModule instance = self;
        if (instance != null) {
            instance.log(Log.INFO, TAG, message);
        }
    }

    static void logError(String message, Throwable throwable) {
        MediaAroundModule instance = self;
        if (instance != null) {
            instance.log(Log.ERROR, TAG, message, throwable);
        }
    }

    static void installLoadMediaHook(Tg tg) {
        MediaAroundModule instance = self;
        if (instance == null || tg == null || tg.loadMedia == null) {
            return;
        }
        instance.remember(instance.hook(tg.loadMedia).setId("loadMedia").intercept(Around::onLoadMedia));
        logInfo("media loading hooked: " + tg.controllerType.getName());
        try {
            instance.remember(instance.hook(tg.processLoadedMedia).setId("deliver").intercept(Around::onProcessLoadedMedia));
            logInfo("delivery hooked: " + tg.processLoadedMedia.getParameterCount() + " params");
        } catch (Throwable t) {
            logError("processLoadedMedia hook failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (retiring || !SCOPE.equals(param.getPackageName())) {
            return;
        }
        install(param.getDefaultClassLoader(), param.isFirstPackage() ? "first load" : "load");
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        retiring = true;
        stopDiscovery();
        Tg current = Tg.INSTANCE;
        if (current != null) {
            current.cancelScan();
        }
        Tg.INSTANCE = null;
        param.setSavedInstanceState(targetLoader);
        log(Log.INFO, TAG, "hot reload: old generation retiring, hooks=" + handles.size());
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        List<XposedInterface.HookHandle> old = param.getOldHookHandles();
        for (XposedInterface.HookHandle handle : old) {
            try {
                handle.unhook();
            } catch (Throwable t) {
                logError("unhook failed: " + safeId(handle), t);
            }
        }
        handles.clear();
        retiring = false;
        Object saved = param.getSavedInstanceState();
        ClassLoader loader = saved instanceof ClassLoader ? (ClassLoader) saved : targetLoader;
        log(Log.INFO, TAG, "hot reloaded: dropped " + old.size() + " hooks, loader=" + (loader != null));
        if (loader != null) {
            install(loader, "hot reload");
        }
    }

    private void install(ClassLoader loader, String reason) {
        if (loader == null) {
            log(Log.ERROR, TAG, "install: no class loader");
            return;
        }
        targetLoader = loader;
        Tg tg = Tg.create(loader);
        if (tg == null) {
            log(Log.ERROR, TAG, "install: " + reason + " cannot bind target classes");
            return;
        }
        Tg.INSTANCE = tg;
        final Tg installed = tg;
        try {
            hookViewer(installed);
            hookNetwork(installed);
            log(Log.INFO, TAG, "hooks ready (" + reason + ")");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook install failed (" + reason + ")", t);
        }
        startDiscovery(installed);
    }

    private void startDiscovery(Tg tg) {
        Thread thread = new Thread(() -> {
            try {
                tg.discoverByScan();
            } catch (Throwable t) {
                logError("scan thread failed", t);
            }
        }, "TgFastViewer-discover");
        thread.setDaemon(true);
        discovery = thread;
        thread.start();
    }

    private void stopDiscovery() {
        Thread thread = discovery;
        discovery = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void remember(XposedInterface.HookHandle handle) {
        if (handle != null) {
            handles.add(handle);
        }
    }

    private static String safeId(XposedInterface.HookHandle handle) {
        try {
            return handle.getId();
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private void hookViewer(Tg tg) throws NoSuchMethodException {
        Method target = null;
        for (Method method : tg.photoViewer.getDeclaredMethods()) {
            if (method.getName().equals("didReceivedNotification") && method.getParameterCount() == 3) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("didReceivedNotification");
        }
        target.setAccessible(true);
        remember(hook(target).setId("viewer").intercept(chain -> {
            Object viewer = chain.getThisObject();
            int account = Ref.asInt(chain.getArg(1), -1);
            Object rawArgs = chain.getArg(2);
            Object[] args = rawArgs instanceof Object[] ? (Object[]) rawArgs : null;
            if (viewer != null && args != null) {
                Viewer.onNotification(viewer, account, args);
            }
            return chain.proceed();
        }));
    }

    private void hookNetwork(Tg tg) {
        int index = 0;
        for (Method method : tg.sendRequestFamily) {
            final int slot = index++;
            remember(hook(method).setId("network" + slot).intercept(chain -> {
                Object request = chain.getArg(0);
                Tg current = Tg.INSTANCE;
                if (current != null && request != null) {
                    current.observeRequest(request);
                    Class<?> type = request.getClass();
                    if (type == current.searchType
                            || (current.searchCountersType != null && type == current.searchCountersType)) {
                        current.discover();
                    }
                }
                return chain.proceed();
            }));
        }
        logInfo("network hooks: " + index);
    }
}
