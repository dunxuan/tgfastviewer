package io.github.dunxuan.tgfastviewer.module;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class MediaAroundModule extends io.github.libxposed.api.XposedModule {

    private static final String TAG = "TgFastViewer";
    private static volatile MediaAroundModule self;

    static MediaAroundModule module() {
        return self;
    }

    static void logError(String message, Throwable throwable) {
        MediaAroundModule instance = self;
        if (instance != null) {
            instance.log(Log.ERROR, TAG, message, throwable);
        }
    }

    static void logInfo(String message) {
        MediaAroundModule instance = self;
        if (instance != null) {
            instance.log(Log.INFO, TAG, message);
        }
    }

    static void installLoadMediaHook(Tg tg) {
        MediaAroundModule instance = self;
        if (instance == null || tg == null || tg.loadMedia == null) {
            return;
        }
        try {
            instance.hook(tg.loadMedia).setId("loadMedia").intercept(Around::onLoadMedia);
            logInfo("media loading hooked: " + tg.controllerType.getName());
        } catch (Throwable t) {
            logError("loadMedia hook failed", t);
        }
        try {
            instance.hook(tg.processLoadedMedia).setId("deliver").intercept(Around::onProcessLoadedMedia);
            logInfo("delivery hooked: " + tg.processLoadedMedia.getParameterCount() + " params");
        } catch (Throwable t) {
            logError("processLoadedMedia hook failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"org.telegram.messenger".equals(param.getPackageName())) {
            return;
        }
        self = this;
        Tg tg = Tg.create(param.getDefaultClassLoader());
        if (tg == null) {
            return;
        }
        Tg.INSTANCE = tg;
        try {
            hookViewer(tg);
            hookNetwork(tg);
            log(Log.INFO, TAG, "hooks ready");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook install failed", t);
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
        hook(target).setId("viewer").intercept(chain -> {
            Object viewer = chain.getThisObject();
            int notificationId = Ref.asInt(chain.getArg(0), -1);
            int account = Ref.asInt(chain.getArg(1), -1);
            Object rawArgs = chain.getArg(2);
            Object[] args = rawArgs instanceof Object[] ? (Object[]) rawArgs : null;
            if (viewer != null && args != null) {
                Viewer.onNotification(viewer, account, args);
            }
            Object result = chain.proceed();
            return result;
        });
    }

    private void hookNetwork(Tg tg) {
        int index = 0;
        for (Method method : tg.sendRequestFamily) {
            final int slot = index++;
            hook(method).setId("network" + slot).intercept(chain -> {
                Object request = chain.getArg(0);
                Tg current = Tg.INSTANCE;
                if (current != null && request != null) {
                    Class<?> type = request.getClass();
                    if (type == current.searchType
                            || (current.searchCountersType != null && type == current.searchCountersType)) {
                        current.observeRequest(request);
                        current.discover();
                    }
                }
                return chain.proceed();
            });
        }
        logInfo("network hooks: " + index);
    }
}

