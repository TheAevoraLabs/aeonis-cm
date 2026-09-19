package com.qc.aeonis.platform;

import java.util.ServiceLoader;

public class Services {
    public static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz).findFirst()
                .orElseThrow(() -> new RuntimeException("Failed to load service: " + clazz.getName()));
    }
}
