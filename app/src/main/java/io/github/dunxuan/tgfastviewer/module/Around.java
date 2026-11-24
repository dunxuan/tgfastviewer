package io.github.dunxuan.tgfastviewer.module;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

final class Around {

    static final int FIRST_PAGE = 20;
    static final int WINDOW = 50;
    private static final long WINDOW_TIMEOUT_MS = 4000;
    private static final int FLAG_OFFSET_ID = 4;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Class<?>> hookedCounters = new ArrayList<>();

    private Around() {
    }

    static Object onLoadMedia(XposedInterface.Chain chain) throws Throwable {
        Tg tg = Tg.INSTANCE;
        if (tg == null || tg.processLoadedMedia == null) {
            return chain.proceed();
        }
        List<Object> args = chain.getArgs();
        if (args.size() != 11) {
            return chain.proceed();
        }
        int count = Ref.asInt(args.get(1), -1);
        int maxId = Ref.asInt(args.get(2), -1);
        int minId = Ref.asInt(args.get(3), -1);
        int fromCache = Ref.asInt(args.get(6), -1);
        long dialogId = Ref.asLong(args.get(0), 0);
        int classGuid = Ref.asInt(args.get(7), -1);
        Object controller = chain.getThisObject();
        Tg.Peer peer = tg.peerFor(dialogId);
        if (count != FIRST_PAGE) {
            return chain.proceed();
        }
        if (maxId != 0 || minId != 0 || args.get(9) != null) {
            MediaAroundModule.logInfo("bail first-page: max=" + maxId + " min=" + minId + " tag=" + (args.get(9) != null));
            return chain.proceed();
        }
        Object query = args.get(10);
        if (query != null && !query.toString().isEmpty()) {
            MediaAroundModule.logInfo("bail first-page: query");
            return chain.proceed();
        }

        if (dialogId == 0 || controller == null || classGuid == 0 || peer == null) {
            MediaAroundModule.logInfo("bail first-page: dialog=" + dialogId + " controller=" + (controller != null)
                    + " guid=" + classGuid + " peer=" + (peer != null));
            return chain.proceed();
        }

        Object viewer = Viewer.instance();
        int account = Viewer.account();
        if (viewer == null || account < 0) {
            MediaAroundModule.logInfo("bail first-page: viewer=" + (viewer != null) + " account=" + account);
            return chain.proceed();
        }
        Object anchor = Viewer.takePendingAnchor(dialogId);
        if (anchor == null) {
            MediaAroundModule.logInfo("skip: no fresh anchor dialog=" + dialogId);
            return chain.proceed();
        }
        Object owner = tg.ownerOf(anchor);
        if (owner == null || tg.isSecret(owner)) {
            MediaAroundModule.logInfo("bail first-page: owner=" + (owner != null) + " secret=" + (owner != null && tg.isSecret(owner)));
            return chain.proceed();
        }
        int anchorId = tg.idOf(anchor);
        if (anchorId <= 0) {
            MediaAroundModule.logInfo("bail first-page: anchorId=" + anchorId);
            return chain.proceed();
        }

        MediaAroundModule.logInfo("around dialog=" + dialogId + " anchor=" + anchorId + " type=" + Ref.asInt(args.get(4), -1)
                + " guid=" + classGuid);
        Viewer.hideCounter(true);
        Pending pending = new Pending(controller, args, tg.loadMedia, account, dialogId, Ref.asInt(args.get(4), -1),
                Ref.asLong(args.get(5), 0), classGuid, Ref.asInt(args.get(8), 0), anchorId, peer);
        if (!pending.start()) {
            return chain.proceed();
        }
        return null;
    }

    static Object onProcessLoadedMedia(XposedInterface.Chain chain) throws Throwable {
        Tg tg = Tg.INSTANCE;
        if (tg == null) {
            return chain.proceed();
        }
        List<Object> args = chain.getArgs();
        if (args.size() < 10) {
            return chain.proceed();
        }
        int count = Ref.asInt(args.get(2), -1);
        int maxId = Ref.asInt(args.get(3), -1);
        int minId = Ref.asInt(args.get(4), -1);
        long dialogId = Ref.asLong(args.get(1), 0);
        int classGuid = Ref.asInt(args.get(8), -1);
        if (count != FIRST_PAGE || maxId != 0 || minId != 0) {
            return chain.proceed();
        }
        Object controller = chain.getThisObject();
        Tg.Peer peer = tg.peerFor(dialogId);
        if (controller == null || peer == null) {
            return chain.proceed();
        }
        Object anchor = Viewer.takePendingAnchor(dialogId);
        if (anchor == null) {

            return chain.proceed();
        }
        Object owner = tg.ownerOf(anchor);
        if (owner == null || tg.isSecret(owner)) {
            return chain.proceed();
        }
        int anchorId = tg.idOf(anchor);
        if (anchorId <= 0) {
            return chain.proceed();
        }
        MediaAroundModule.logInfo("x1 take over anchor=" + anchorId + " guid=" + classGuid);
        Viewer.hideCounter(true);
        Pending pending = new Pending(controller, args, tg.processLoadedMedia, Viewer.account(), dialogId,
                Ref.asInt(args.get(5), -1), Ref.asLong(args.get(6), 0), classGuid,
                args.size() > 10 ? Ref.asInt(args.get(10), 0) : 0, anchorId, peer);
        if (!pending.start()) {
            return chain.proceed();
        }
        return null;
    }

    static void lookupCounter(Object viewer) {
        Tg tg = Tg.INSTANCE;
        XposedInterface module = MediaAroundModule.module();
        if (tg == null || module == null) {
            return;
        }
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> inner : tg.photoViewer.getDeclaredClasses()) {
            if (android.view.View.class.isAssignableFrom(inner)) {
                targets.add(inner);
            }
        }
        Object countView = Viewer.findCountView(viewer);
        if (countView != null) {
            targets.add(countView.getClass());
        }
        for (Class<?> target : targets) {
            if (hookedCounters.contains(target)) {
                continue;
            }
            Method onDraw = findOnDraw(target);
            if (onDraw == null) {
                continue;
            }
            try {
                onDraw.setAccessible(true);
                module.hook(onDraw).setId("counter:" + target.getName()).intercept(Around::onCounterDraw);
                hookedCounters.add(target);
                MediaAroundModule.logInfo("counter hooked: " + target.getName());
            } catch (Throwable t) {
                MediaAroundModule.logError("counter hook failed", t);
            }
        }
    }

    private static Method findOnDraw(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals("onDraw") && params.length == 1 && params[0] == Canvas.class) {
                    return method;
                }
            }
        }
        return null;
    }

    static Object onCounterDraw(XposedInterface.Chain chain) throws Throwable {
        if (Viewer.hideCounter()) {
            return null;
        }
        return chain.proceed();
    }

    private interface ResponseHandler {
        void onResult(Object response, Object error);
    }

    private static final class Pending {

        private final Object controller;
        private final List<Object> args;
        private final Method entryMethod;
        private final int account;
        private final long dialogId;
        private final int type;
        private final long topicId;
        private final int classGuid;
        private final int requestIndex;
        private final int anchorId;
        private final Tg.Peer peer;
        private Object window;
        private int windowTopId;
        private volatile boolean settled;

        Pending(Object controller, List<Object> args, Method entryMethod, int account, long dialogId, int type,
                long topicId, int classGuid, int requestIndex, int anchorId, Tg.Peer peer) {
            this.controller = controller;
            this.args = new ArrayList<>(args);
            this.entryMethod = entryMethod;
            this.account = account;
            this.dialogId = dialogId;
            this.type = type;
            this.topicId = topicId;
            this.classGuid = classGuid;
            this.requestIndex = requestIndex;
            this.anchorId = anchorId;
            this.peer = peer;
        }

        boolean start() {
            Tg tg = Tg.INSTANCE;
            if (tg == null) {
                MediaAroundModule.logInfo("start: no tg");
                return false;
            }
            Object request = buildSearch(tg, anchorId, WINDOW, -(WINDOW / 2 + 1));
            if (request == null) {
                MediaAroundModule.logInfo("start: buildSearch null newSearch=" + (tg.searchType != null)
                        + " peer=" + (peer != null) + " input=" + (peer != null && peer.inputPeer != null)
                        + " type=" + type);
                return false;
            }
            boolean queued = send(tg, request, (response, error) -> onWindow(tg, response, error));
            if (!queued) {
                MediaAroundModule.logInfo("start: send not queued");
                return false;
            }
            MAIN.postDelayed(() -> {
                if (!settled) {
                    settled = true;
                    MediaAroundModule.logInfo("window timeout, falling back");
                    fallback(tg);
                }
            }, WINDOW_TIMEOUT_MS);
            return true;
        }

        private void onWindow(Tg tg, Object response, Object error) {
            if (settled) {
                MediaAroundModule.logInfo("window arrived after fallback, ignored");
                return;
            }
            settled = true;
            if (error != null || !tg.messagesType.isInstance(response)) {
                fallback(tg);
                return;
            }
            Object raw = Ref.get(tg.resultMessages, response);
            if (!(raw instanceof ArrayList)) {
                fallback(tg);
                return;
            }
            ArrayList<?> messages = (ArrayList<?>) raw;
            if (messages.isEmpty()) {
                fallback(tg);
                return;
            }
            Object first = messages.get(0);
            if (!tg.messageType.isInstance(first)) {
                fallback(tg);
                return;
            }
            MediaAroundModule.logInfo("window size=" + messages.size() + " top=" + Ref.getInt(tg.messageId, first, 0)
                    + " guid=" + classGuid);
            window = response;
            windowTopId = Ref.getInt(tg.messageId, first, 0);
            if (windowTopId <= 0) {
                fallback(tg);
                return;
            }
            deliver(tg);
            probeNewest(tg);
        }

        private void probeNewest(Tg tg) {
            Object request = buildSearch(tg, 0, 1, 0);
            if (request == null) {
                return;
            }
            boolean queued = send(tg, request, (response, error) -> {
                boolean exact = false;
                int newestId = 0;
                if (error == null && tg.messagesType.isInstance(response)) {
                    Object raw = Ref.get(tg.resultMessages, response);
                    if (raw instanceof ArrayList) {
                        ArrayList<?> newest = (ArrayList<?>) raw;
                        if (!newest.isEmpty() && tg.messageType.isInstance(newest.get(0))) {
                            newestId = Ref.getInt(tg.messageId, newest.get(0), 0);
                            exact = newestId == windowTopId;
                        }
                    }
                }
                MediaAroundModule.logInfo("probe newest=" + newestId + " windowTop=" + windowTopId + " exact=" + exact);
                Viewer.hideCounter(!exact);
            });
            if (!queued) {
                MediaAroundModule.logInfo("probe not queued");
            }
        }

        private void deliver(Tg tg) {
            if (window == null) {
                fallback(tg);
                return;
            }
            try {
                Object[] call = buildDeliverArgs(tg);
                XposedInterface module = MediaAroundModule.module();
                if (module == null) {
                    fallback(tg);
                    return;
                }
                XposedInterface.Invoker<?, Method> invoker = module.getInvoker(tg.processLoadedMedia);
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
                invoker.invoke(controller, call);
                MediaAroundModule.logInfo("delivered via processLoadedMedia params=" + call.length);
            } catch (Throwable t) {
                MediaAroundModule.logError("processLoadedMedia failed", t);
                fallback(tg);
            }
        }

        private Object[] buildDeliverArgs(Tg tg) {
            if (tg.processLoadedMedia.getParameterCount() == 12) {
                return new Object[]{window, dialogId, WINDOW, 0, windowTopId, type, topicId, 0, classGuid,
                        false, false, requestIndex};
            }
            return new Object[]{window, dialogId, WINDOW, 0, windowTopId, type, topicId, 0, classGuid,
                    false, requestIndex};
        }

        private void fallback(Tg tg) {
            MediaAroundModule.logInfo("fallback to original load");
            Viewer.hideCounter(false);
            XposedInterface module = MediaAroundModule.module();
            if (module == null) {
                return;
            }
            try {
                XposedInterface.Invoker<?, Method> invoker = module.getInvoker(entryMethod);
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
                invoker.invoke(controller, args.toArray());
            } catch (Throwable t) {
                MediaAroundModule.logError("fallback failed", t);
            }
        }

        private Object buildSearch(Tg tg, int offsetId, int limit, int addOffset) {
            Object request = tg.newSearch();
            if (request == null || peer == null || peer.inputPeer == null) {
                return null;
            }
            Ref.set(tg.searchPeer, request, peer.inputPeer);
            Object filter = tg.filterFor(type);
            if (filter != null) {
                Ref.set(tg.searchFilter, request, filter);
            }
            Ref.set(tg.searchQ, request, "");
            Ref.setInt(tg.searchLimit, request, limit);
            Ref.setInt(tg.searchOffsetId, request, offsetId);
            Ref.setInt(tg.searchAddOffset, request, addOffset);
            int flags = 0;
            if (peer.topMsgId != 0) {
                Ref.setInt(tg.searchTopMsgId, request, peer.topMsgId);
                flags |= 2;
            }
            if (peer.savedPeerId != null) {
                Ref.set(tg.searchSavedPeerId, request, peer.savedPeerId);
                flags |= 4;
            }
            Ref.setInt(tg.searchFlags, request, flags);
            return request;
        }

        private boolean send(Tg tg, Object request, ResponseHandler handler) {
            Object connectionsManager = Ref.call(tg.connectionsGetInstance, null, account);
            if (connectionsManager == null) {
                MediaAroundModule.logInfo("send: ConnectionsManager null account=" + account);
                return false;
            }
            InvocationHandler invocationHandler = (proxy, method, methodArgs) -> {
                String name = method.getName();
                if ("run".equals(name)) {
                    Object response = methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null;
                    Object error = methodArgs != null && methodArgs.length > 1 ? methodArgs[1] : null;
                    MediaAroundModule.logInfo("response arrived error=" + (error != null)
                            + " resp=" + (response != null ? response.getClass().getSimpleName() : "null"));
                    MAIN.post(() -> handler.onResult(response, error));
                    return null;
                }
                if ("toString".equals(name)) {
                    return "TgFastViewer";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null);
                }
                return null;
            };
            Object delegate = Proxy.newProxyInstance(tg.loader, new Class<?>[]{tg.requestDelegate}, invocationHandler);
            XposedInterface module = MediaAroundModule.module();
            if (module == null) {
                MediaAroundModule.logInfo("send: module null");
                return false;
            }
            int requestId;
            try {
                XposedInterface.Invoker<?, Method> invoker = module.getInvoker(tg.sendRequest);
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
                Object result = invoker.invoke(connectionsManager, request, delegate);
                requestId = result instanceof Number ? ((Number) result).intValue() : 0;
            } catch (Throwable t) {
                MediaAroundModule.logError("around request failed", t);
                return false;
            }
            MediaAroundModule.logInfo("request sent token=" + requestId);
            return requestId != 0;
        }
    }
}







