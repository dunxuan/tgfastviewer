package io.github.dunxuan.tgfastviewer.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Tg {

    private static final String[] FILTER_NAMES = {
            "TL_inputMessagesFilterPhotoVideo",
            "TL_inputMessagesFilterDocument",
            "TL_inputMessagesFilterRoundVoice",
            "TL_inputMessagesFilterUrl",
            "TL_inputMessagesFilterMusic",
            "TL_inputMessagesFilterGif",
            "TL_inputMessagesFilterPhotos",
            "TL_inputMessagesFilterVideo",
            "TL_inputMessagesFilterPoll"
    };

    static volatile Tg INSTANCE;

    final ClassLoader loader;
    final Class<?> photoViewer;
    final Class<?> connectionsManager;
    final Class<?> requestDelegate;
    final Class<?> searchType;
    final Class<?> messagesType;
    final Class<?> messageType;
    final Class<?> secretMessageType;
    final Class<?> searchCountersType;
    final Class<?>[] filters;

    final Method sendRequest;
    final java.util.List<Method> sendRequestFamily = new java.util.ArrayList<>();
    final Method connectionsGetInstance;

    final Field searchPeer;
    final Field searchFilter;
    final Field searchQ;
    final Field searchLimit;
    final Field searchOffsetId;
    final Field searchAddOffset;
    final Field searchTopMsgId;
    final Field searchSavedPeerId;
    final Field searchFlags;

    final Field resultMessages;
    final Field resultFlags;
    final Field resultOffsetIdOffset;

    final Field messageId;
    final Field messageDialogId;
    volatile Field messageOwner;

    final Field countersPeer;
    final Field countersTopMsgId;
    final Field countersSavedPeerId;
    final Field countersFlags;

    volatile Class<?> controllerType;
    volatile Method loadMedia;
    volatile Method processLoadedMedia;

    private volatile Peer lastPeer;
    private int diagnoseLeft = 3;

    static final class Peer {
        final Object inputPeer;
        final int topMsgId;
        final Object savedPeerId;
        final int flags;

        Peer(Object inputPeer, int topMsgId, Object savedPeerId, int flags) {
            this.inputPeer = inputPeer;
            this.topMsgId = topMsgId;
            this.savedPeerId = savedPeerId;
            this.flags = flags;
        }
    }

    private Tg(ClassLoader cl) throws Exception {
        loader = cl;

        photoViewer = requireClass(cl, "org.telegram.ui.PhotoViewer");
        connectionsManager = requireClass(cl, "org.telegram.tgnet.ConnectionsManager");
        searchType = requireClass(cl, "org.telegram.tgnet.TLRPC$TL_messages_search");
        messagesType = requireClass(cl, "org.telegram.tgnet.TLRPC$messages_Messages");
        messageType = requireClass(cl, "org.telegram.tgnet.TLRPC$Message");
        secretMessageType = Ref.load(cl, "org.telegram.tgnet.TLRPC$TL_message_secret");
        searchCountersType = Ref.load(cl, "org.telegram.tgnet.TLRPC$TL_messages_getSearchCounters");

        filters = new Class<?>[FILTER_NAMES.length];
        for (int i = 0; i < FILTER_NAMES.length; i++) {
            filters[i] = requireClass(cl, "org.telegram.tgnet.TLRPC$" + FILTER_NAMES[i]);
        }

        Method send = null;
        Class<?> delegate = null;
        for (Method method : connectionsManager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("sendRequest") && params.length == 2
                    && params[0].getName().equals("org.telegram.tgnet.TLObject")
                    && params[1].getName().endsWith("RequestDelegate")) {
                method.setAccessible(true);
                send = method;
                delegate = params[1];
                break;
            }
        }
        if (send == null || delegate == null) {
            throw new NoSuchMethodException("sendRequest");
        }
        sendRequest = send;
        requestDelegate = delegate;
        for (Method method : connectionsManager.getDeclaredMethods()) {
            if (!method.getName().equals("sendRequest")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length >= 2 && params[0].getName().equals("org.telegram.tgnet.TLObject")
                    && params[1] == delegate) {
                method.setAccessible(true);
                sendRequestFamily.add(method);
            }
        }

        Method getInstance = null;
        for (Method method : connectionsManager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("getInstance") && params.length == 1 && params[0] == int.class
                    && method.getReturnType() == connectionsManager) {
                method.setAccessible(true);
                getInstance = method;
                break;
            }
        }
        if (getInstance == null) {
            throw new NoSuchMethodException("ConnectionsManager.getInstance");
        }
        connectionsGetInstance = getInstance;

        searchPeer = requireField(searchType, "peer");
        searchFilter = requireField(searchType, "filter");
        searchQ = requireField(searchType, "q");
        searchLimit = requireField(searchType, "limit");
        searchOffsetId = requireField(searchType, "offset_id");
        searchAddOffset = requireField(searchType, "add_offset");
        searchTopMsgId = requireField(searchType, "top_msg_id");
        searchSavedPeerId = requireField(searchType, "saved_peer_id");
        searchFlags = requireField(searchType, "flags");

        resultMessages = requireField(messagesType, "messages");
        resultFlags = requireField(messagesType, "flags");
        resultOffsetIdOffset = requireField(messagesType, "offset_id_offset");

        messageId = requireField(messageType, "id");
        messageDialogId = requireField(messageType, "dialog_id");
        messageOwner = null;

        if (searchCountersType != null) {
            countersPeer = requireField(searchCountersType, "peer");
            countersTopMsgId = requireField(searchCountersType, "top_msg_id");
            countersSavedPeerId = requireField(searchCountersType, "saved_peer_id");
            countersFlags = requireField(searchCountersType, "flags");
        } else {
            countersPeer = null;
            countersTopMsgId = null;
            countersSavedPeerId = null;
            countersFlags = null;
        }
    }

    static Tg create(ClassLoader cl) {
        try {
            return new Tg(cl);
        } catch (Throwable t) {
            MediaAroundModule.logError("bind failed", t);
            return null;
        }
    }

    private static Class<?> requireClass(ClassLoader cl, String name) throws ClassNotFoundException {
        Class<?> type = Ref.load(cl, name);
        if (type == null) {
            throw new ClassNotFoundException(name);
        }
        return type;
    }

    private static Field requireField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = Ref.field(owner, name);
        if (field == null) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
        field.setAccessible(true);
        return field;
    }

    Peer lastPeer() {
        return lastPeer;
    }

    Object newSearch() {
        return Ref.newInstance(searchType);
    }

    Object filterFor(int type) {
        if (type < 0 || type >= filters.length) {
            return null;
        }
        return Ref.newInstance(filters[type]);
    }

    boolean isSecret(Object message) {
        return secretMessageType != null && secretMessageType.isInstance(message);
    }

    Object ownerOf(Object messageObject) {
        if (messageObject == null) {
            return null;
        }
        Field owner = messageOwner;
        if (owner == null || owner.getDeclaringClass() != messageObject.getClass()) {
            owner = findOwnerField(messageObject.getClass());
            if (owner == null) {
                return null;
            }
            messageOwner = owner;
        }
        return Ref.get(owner, messageObject);
    }

    private Field findOwnerField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == messageType) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    int idOf(Object messageObject) {
        return Ref.getInt(messageId, ownerOf(messageObject), 0);
    }

    long dialogIdOf(Object messageObject) {
        Object owner = ownerOf(messageObject);
        if (owner == null) {
            return 0L;
        }
        Object value = Ref.get(messageDialogId, owner);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    void observeRequest(Object request) {
        try {
            if (request == null) {
                return;
            }
            Class<?> type = request.getClass();
            if (type == searchType) {
                Object peer = Ref.get(searchPeer, request);
                if (peer != null) {
                    lastPeer = new Peer(peer, Ref.getInt(searchTopMsgId, request, 0),
                            Ref.get(searchSavedPeerId, request), Ref.getInt(searchFlags, request, 0));
                }
            } else if (searchCountersType != null && type == searchCountersType && countersPeer != null) {
                Object peer = Ref.get(countersPeer, request);
                if (peer != null) {
                    lastPeer = new Peer(peer, Ref.getInt(countersTopMsgId, request, 0),
                            Ref.get(countersSavedPeerId, request), Ref.getInt(countersFlags, request, 0));
                }
            }
        } catch (Throwable ignore) {
        }
    }

    synchronized void discover() {
        if (loadMedia != null) {
            return;
        }
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (StackTraceElement frame : frames) {
            String name = frame.getClassName();
            if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("io.github.libxposed")
                    || name.startsWith("io.github.dunxuan.tgfastviewer") || name.startsWith("org.telegram.tgnet")) {
                continue;
            }
            Class<?> candidate = Ref.load(loader, name);
            if (candidate == null) {
                continue;
            }
            Method load = findLoadMedia(candidate);
            Method process = findProcessLoadedMedia(candidate);
            if (diagnoseLeft > 0 && load == null) {
                diagnoseLeft--;
                MediaAroundModule.logInfo("cand " + name + " shapes=" + shapes(candidate));
            }
            if (load == null || process == null) {
                continue;
            }
            controllerType = candidate;
            loadMedia = load;
            processLoadedMedia = process;
            MediaAroundModule.installLoadMediaHook(this);
            return;
        }
    }

    private String shapes(Class<?> owner) {
        StringBuilder builder = new StringBuilder();
        for (Method method : owner.getDeclaredMethods()) {
            int count = method.getParameterCount();
            if (count >= 9 && count <= 14) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(count).append(':').append(method.getName());
            }
        }
        return builder.toString();
    }

    private Method findLoadMedia(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 11) {
                continue;
            }
            if (params[0] != long.class || params[1] != int.class || params[2] != int.class || params[3] != int.class
                    || params[4] != int.class || params[5] != long.class || params[6] != int.class
                    || params[7] != int.class || params[8] != int.class) {
                continue;
            }
            if (params[9].isPrimitive() || params[9] == String.class) {
                continue;
            }
            if (params[10] != String.class) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private Method findProcessLoadedMedia(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 11 && params.length != 12) {
                continue;
            }
            if (params[0] != messagesType) {
                continue;
            }
            if (params[1] != long.class || params[2] != int.class || params[3] != int.class
                    || params[4] != int.class || params[5] != int.class || params[6] != long.class
                    || params[7] != int.class || params[8] != int.class) {
                continue;
            }
            if (params[params.length - 1] != int.class) {
                continue;
            }
            boolean valid = true;
            for (int i = 9; i < params.length - 1; i++) {
                if (params[i] != boolean.class) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }
}







