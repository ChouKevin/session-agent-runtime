package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SlackPermalinkParser {

    private static final Pattern HOST = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.slack\\.com$");
    private static final Pattern PATH = Pattern.compile("^/archives/([A-Z][A-Z0-9]{1,127})/p([1-9][0-9]{0,15})([0-9]{6})$");
    private static final Pattern TIMESTAMP = Pattern.compile("^[1-9][0-9]{0,15}\\.[0-9]{6}$");

    public SlackPermalink parse(String permalink) {
        Assert.hasText(permalink, "Slack permalink must not be blank");
        URI uri = parseUri(permalink);
        validateAuthority(uri);
        Matcher path = PATH.matcher(Objects.requireNonNull(uri.getRawPath(), "Slack permalink path must not be null"));
        if (!path.matches()) {
            throw invalidPermalink();
        }
        String channelId = path.group(1);
        String messageTimestamp = path.group(2) + "." + path.group(3);
        String query = uri.getRawQuery();
        if (Objects.isNull(query)) {
            return new SlackPermalink(channelId, messageTimestamp);
        }
        Map<String, String> values = queryValues(query);
        if (values.size() != 2 || !values.containsKey("thread_ts") || !values.containsKey("cid")) {
            throw invalidPermalink();
        }
        String rootThreadTimestamp = values.get("thread_ts");
        if (!TIMESTAMP.matcher(rootThreadTimestamp).matches() || !channelId.equals(values.get("cid"))
                || rootThreadTimestamp.equals(messageTimestamp)) {
            throw invalidPermalink();
        }
        return new SlackPermalink(channelId, rootThreadTimestamp);
    }

    private static URI parseUri(String permalink) {
        try {
            return new URI(permalink);
        } catch (URISyntaxException exception) {
            throw invalidPermalink();
        }
    }

    private static void validateAuthority(URI uri) {
        String host = uri.getHost();
        String rawAuthority = uri.getRawAuthority();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
                || !StringUtils.hasText(rawAuthority) || !HOST.matcher(rawAuthority).matches()
                || !rawAuthority.equals(host) || Objects.nonNull(uri.getRawFragment())) {
            throw invalidPermalink();
        }
    }

    private static Map<String, String> queryValues(String query) {
        Map<String, String> values = new HashMap<>();
        for (String component : query.split("&", -1)) {
            int separator = component.indexOf('=');
            if (separator <= 0 || separator != component.lastIndexOf('=') || component.contains("%")) {
                throw invalidPermalink();
            }
            String name = component.substring(0, separator);
            String value = component.substring(separator + 1);
            if (!StringUtils.hasText(value) || Objects.nonNull(values.putIfAbsent(name, value))) {
                throw invalidPermalink();
            }
        }
        return values;
    }

    private static IllegalArgumentException invalidPermalink() {
        return new IllegalArgumentException("Unsupported Slack permalink");
    }
}
