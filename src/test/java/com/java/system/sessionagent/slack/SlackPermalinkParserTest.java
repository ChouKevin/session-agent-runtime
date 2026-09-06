package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SlackPermalinkParserTest {

    @Test
    void resolves_root_and_reply_permalinks_to_their_bound_root_thread_without_network_access() {
        SlackPermalinkParser parser = new SlackPermalinkParser();

        SlackPermalink root = parser.parse("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456");
        SlackPermalink reply = parser.parse(
                "https://acme.slack.com/archives/C01ABCDEF/p1712345687123456?thread_ts=1712345678.123456&cid=C01ABCDEF");

        assertThat(root).isEqualTo(new SlackPermalink("C01ABCDEF", "1712345678.123456"));
        assertThat(reply).isEqualTo(new SlackPermalink("C01ABCDEF", "1712345678.123456"));
    }

    @Test
    void rejects_unsupported_and_ambiguous_permalink_identities_locally() {
        SlackPermalinkParser parser = new SlackPermalinkParser();

        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("http://acme.slack.com/archives/C01ABCDEF/p1712345678123456"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com.evil.test/archives/C01ABCDEF/p1712345678123456"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com@evil.test/archives/C01ABCDEF/p1712345678123456"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456#fragment"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456?"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456?thread_ts=1712345678.123456"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> parser.parse("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456?thread_ts=1712345678.123456&cid=C01ABCDEF"));
    }
}
