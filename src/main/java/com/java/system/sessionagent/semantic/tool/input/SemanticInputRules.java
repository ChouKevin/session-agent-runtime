package com.java.system.sessionagent.semantic.tool.input;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class SemanticInputRules {

    private SemanticInputRules() {
    }

    public static <T> List<T> distinct(List<T> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        List<T> copy = List.copyOf(values);
        Assert.isTrue(new HashSet<>(copy).size() == copy.size(), field + " must not contain duplicates");
        return copy;
    }

    public static String path(String value) {
        Assert.hasText(value, "Repository-relative path must not be blank");
        Assert.isTrue(value.length() <= 1_024, "Repository-relative path is too long");
        Assert.isTrue(!value.startsWith("/") && !value.matches("^[A-Za-z]:/.*"),
                "Repository-relative path must not be absolute");
        Assert.isTrue(!value.contains("\\") && !value.contains(".."), "Repository-relative path must be normalized");
        Assert.isTrue(!Character.isWhitespace(value.charAt(value.length() - 1)), "Repository-relative path must not end in whitespace");
        return value;
    }

    public static String text(String value, String field) {
        Assert.hasText(value, field + " must not be blank");
        return value;
    }

    public static String optionalText(String value, String field) {
        if (StringUtils.hasLength(value)) {
            Assert.isTrue(!value.isBlank(), field + " must not be blank when supplied");
        }
        return value;
    }
}
