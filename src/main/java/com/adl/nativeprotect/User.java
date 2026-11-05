package com.adl.nativeprotect;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class User {

    private static final User instance = new User();

    public static User getInstance() {
        return instance;
    }

    private final Map<String, String> cache = new HashMap<>();

    private boolean nativeFailed = false;

    private User() {
        try {
            cache.put("username", getUsername());
            cache.put("hwid", getHwid());
            cache.put("role", getRole());
            cache.put("uid", getUid());
            cache.put("subTime", getSubsTime());
        } catch (UnsatisfiedLinkError e) {
            nativeFailed = true;
            cache.put("username", "KODEK");
            cache.put("hwid", "hwid-1231294809786-2348786");
            cache.put("role", "Admin");
            cache.put("uid", "777");
            cache.put("subTime", "2025-24-05");
        }
    }
    
    @com.adl.nativeprotect.Native
    private native String getUsername();
    @com.adl.nativeprotect.Native
    private native String getHwid();
    @com.adl.nativeprotect.Native
    private native String getRole();
    @com.adl.nativeprotect.Native
    private native String getUid();
    @com.adl.nativeprotect.Native
    private native String getSubsTime();

    public String profile(String profile) {
        return cache.getOrDefault(profile, "");
    }
}
