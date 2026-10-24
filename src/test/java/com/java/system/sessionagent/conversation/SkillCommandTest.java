package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.SkillCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class SkillCommandTest {
    @Test
    void parses_only_the_leading_token_and_retains_the_question() {
        assertThat(SkillCommand.parse("/skill:review  Explain this\nexact text  "))
                .contains(new SkillCommand("review", "Explain this\nexact text  "));
        assertThat(SkillCommand.parse("/skill:review")).contains(new SkillCommand("review", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/skill:", "/skill:../secret question", "/skill:BAD! question"})
    void classifies_malformed_explicit_selections_instead_of_admitting_ordinary_text(String text) {
        assertThat(SkillCommand.parse(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {" /skill:review question", "please /skill:review", "/skills:review", "review this", ""})
    void leaves_nonleading_tokens_aliases_and_natural_language_ordinary(String text) {
        assertThat(SkillCommand.parse(text)).isEmpty();
    }
}
