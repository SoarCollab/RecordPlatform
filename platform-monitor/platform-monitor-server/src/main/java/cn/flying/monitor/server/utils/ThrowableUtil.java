package cn.flying.monitor.server.utils;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @program: monitor
 * @description:
 * @author: 王贝强
 * @create: 2025-01-14 14:37
 */
public class ThrowableUtil {
    /**
     * 获取堆栈信息
     */
    public static String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
            return sw.toString();
        }
    }
}
