package com.yourserver.chestlogger.util;

import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public final class ComponentHelper {

    private ComponentHelper() {}

    public static Object createLiteral(String text) {
        if (text == null) text = "";
        try {
            return net.minecraft.network.chat.Component.literal(text);
        } catch (Throwable t1) {
            try {
                Class<?> clazz = Class.forName("net.minecraft.network.chat.Component");
                Method m = clazz.getMethod("literal", String.class);
                return m.invoke(null, text);
            } catch (Throwable t2) {
                try {
                    Class<?> clazz = Class.forName("net.minecraft.class_2561");
                    for (Method m : clazz.getMethods()) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                            return m.invoke(null, text);
                        }
                    }
                } catch (Throwable t3) {}
            }
        }
        return null;
    }

    public static void sendSuccess(CommandSourceStack source, String message, boolean allowLogging) {
        if (source == null || message == null) return;
        Object comp = createLiteral(message);
        if (comp == null) return;

        try {
            for (Method m : source.getClass().getMethods()) {
                if (m.getName().equals("sendSuccess") && m.getParameterCount() == 2) {
                    Class<?> param0 = m.getParameterTypes()[0];
                    if (Supplier.class.isAssignableFrom(param0)) {
                        Supplier<?> supplier = () -> comp;
                        m.invoke(source, supplier, allowLogging);
                        return;
                    } else if (param0.isInstance(comp)) {
                        m.invoke(source, comp, allowLogging);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void sendFailure(CommandSourceStack source, String message) {
        if (source == null || message == null) return;
        Object comp = createLiteral(message);
        if (comp == null) return;

        try {
            for (Method m : source.getClass().getMethods()) {
                if (m.getName().equals("sendFailure") && m.getParameterCount() == 1) {
                    Class<?> param0 = m.getParameterTypes()[0];
                    if (param0.isInstance(comp)) {
                        m.invoke(source, comp);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
