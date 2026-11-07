package com.baidu.carplayer.network;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/** 从 URL、日志和错误信息中移除凭据。 */
public final class LogRedactor {
    private static final Pattern SECRET_PARAMS = Pattern.compile(
            "(?i)((?:access_token|refresh_token|client_secret|device_code|code)=)[^&#\\s\"']*");

    private LogRedactor() {
    }

    @Nullable
    public static String redact(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_PARAMS.matcher(value).replaceAll("$1***");
    }
}
