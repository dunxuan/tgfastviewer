package io.github.dunxuan.tgfastviewer.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Ref {

    private Ref() {
    }

    static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    static Field anyField(Class<?> owner, String name) {
        Field f = field(owner, name);
        if (f != null) {
            return f;
        }
        if (owner == null) {
            return null;
        }
        for (Class<?> c = owner.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            f = field(c, name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    static Object get(Field f, Object target) {
        if (f == null || target == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static int getInt(Field f, Object target, int fallback) {
        Object v = get(f, target);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    static void set(Field f, Object target, Object value) {
        if (f == null || target == null) {
            return;
        }
        try {
            f.set(target, value);
        } catch (Throwable ignore) {
        }
    }

    static void setInt(Field f, Object target, int value) {
        if (f == null || target == null) {
            return;
        }
        try {
            f.setInt(target, value);
        } catch (Throwable ignore) {
        }
    }

    static Object call(Method m, Object target, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static int callInt(Method m, Object target, Object... args) {
        Object v = call(m, target, args);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    static long callLong(Method m, Object target, Object... args) {
        Object v = call(m, target, args);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    static boolean callBool(Method m, Object target, Object... args) {
        Object v = call(m, target, args);
        return v instanceof Boolean && (Boolean) v;
    }

    static Object newInstance(Class<?> owner) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    static int asInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static long asLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
