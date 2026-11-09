package com.baidu.carplayer.backup;

import java.util.Collection;

/** 导入重名处理：已存在时依次尝试“名称 (导入)”、“名称 (导入2)”…。 */
public final class ImportNaming {
    private ImportNaming() {}

    public static String uniqueName(String name, Collection<String> taken) {
        if (!taken.contains(name)) return name;
        String candidate = name + " (导入)";
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = name + " (导入" + i + ")";
        }
        return candidate;
    }
}
