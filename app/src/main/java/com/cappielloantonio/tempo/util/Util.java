package com.cappielloantonio.tempo.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public class Util {
    public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
        try {
            Map<Object, Boolean> uniqueMap = new ConcurrentHashMap<>();
            return t -> uniqueMap.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
        } catch (NullPointerException exception) {
            return null;
        }
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException ex) {
            return value;
        }
    }
}
