package io.github.dunxuan.tgfastviewer.module;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class Viewer {

    private static volatile Object instance;
    private static volatile int account = -1;
    private static volatile boolean hideCounter;
    private static volatile Field[] listFields;
    private static volatile Field[] allFields;
    private static volatile Object pendingAnchor;
    private static volatile long pendingDialogId;
    private static volatile long pendingTime;

    private Viewer() {
    }

    static void onNotification(Object viewerInstance, int accountNum, Object[] args) {
        if (viewerInstance == null || args == null) {
            return;
        }
        instance = viewerInstance;
        if (accountNum >= 0) {
            account = accountNum;
        }
        Around.lookupCounter(viewerInstance);
        if (isCountLoaded(args)) {
            hideCounter = false;
            long dialogId = args[0] instanceof Long ? (Long) args[0] : 0;
            Object anchor = findAnchor(viewerInstance, dialogId);
            if (anchor != null) {
                pendingAnchor = anchor;
                pendingDialogId = dialogId;
                pendingTime = System.currentTimeMillis();
            }
        }
    }

    static void logListsAfter(Object viewerInstance, Object[] args) {
    }

    private static boolean isCountLoaded(Object[] args) {
        return args.length >= 3 && args[0] instanceof Long && args[2] instanceof Integer;
    }

    static Object takePendingAnchor(long dialogId) {
        Object anchor = pendingAnchor;
        if (anchor == null || pendingDialogId != dialogId) {
            return null;
        }
        if (System.currentTimeMillis() - pendingTime > 4000L) {
            pendingAnchor = null;
            return null;
        }
        pendingAnchor = null;
        return anchor;
    }

    static Object instance() {
        return instance;
    }

    static int account() {
        return account;
    }

    static boolean hideCounter() {
        return hideCounter;
    }

    static void hideCounter(boolean value) {
        hideCounter = value;
    }

    static Object findAnchor(Object viewerInstance, long dialogId) {
        Tg tg = Tg.INSTANCE;
        if (tg == null || viewerInstance == null) {
            return null;
        }
        Object anchor = null;
        boolean single = true;
        for (Field field : listFields(viewerInstance.getClass())) {
            Object value = Ref.get(field, viewerInstance);
            if (!(value instanceof ArrayList)) {
                continue;
            }
            ArrayList<?> list = (ArrayList<?>) value;
            int size = list.size();
            if (size == 0) {
                continue;
            }
            Object first = list.get(0);
            if (first == null || tg.ownerOf(first) == null) {
                continue;
            }
            if (size > 1) {
                single = false;
            } else if (anchor == null) {
                anchor = first;
            }
        }
        if (!single || anchor == null) {
            return null;
        }
        if (tg.dialogIdOf(anchor) != dialogId) {
            return null;
        }
        return anchor;
    }

    static Object findCountView(Object viewerInstance) {
        if (viewerInstance == null) {
            return null;
        }
        for (Field field : allFields(viewerInstance.getClass())) {
            Object value = Ref.get(field, viewerInstance);
            if (!(value instanceof View)) {
                continue;
            }
            if (hasCounterSignatures(value.getClass())) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasCounterSignatures(Class<?> type) {
        boolean setter = false;
        boolean direct = false;
        boolean show = false;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() != void.class) {
                continue;
            }
            if (params.length == 2 && params[0] == int.class && params[1] == int.class) {
                setter = true;
            } else if (params.length == 3 && params[0] == int.class && params[1] == int.class && params[2] == boolean.class) {
                direct = true;
            } else if (params.length == 2 && params[0] == boolean.class && params[1] == boolean.class) {
                show = true;
            }
        }
        return setter && direct && show;
    }

    private static Field[] listFields(Class<?> type) {
        Field[] cached = listFields;
        if (cached != null) {
            return cached;
        }
        List<Field> found = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == ArrayList.class) {
                field.setAccessible(true);
                found.add(field);
            }
        }
        Field[] result = found.toArray(new Field[0]);
        listFields = result;
        return result;
    }

    private static Field[] allFields(Class<?> type) {
        Field[] cached = allFields;
        if (cached != null) {
            return cached;
        }
        List<Field> found = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            field.setAccessible(true);
            found.add(field);
        }
        Field[] result = found.toArray(new Field[0]);
        allFields = result;
        return result;
    }
}


