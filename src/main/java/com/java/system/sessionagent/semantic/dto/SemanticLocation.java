package com.java.system.sessionagent.semantic.dto;

import com.java.system.sessionagent.semantic.tool.input.SemanticInputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.util.Assert;

/** Provider source-range payload: zero-based UTF-16 line/character positions and a half-open range. */
public record SemanticLocation(String sourceFile, @Valid Range range) {

    public SemanticLocation {
        sourceFile = SemanticInputRules.path(sourceFile);
        Assert.notNull(range, "Source range must not be null");
    }

    public record Position(@Min(0) int line, @Min(0) int character) { }

    public record Range(@Valid Position start, @Valid Position end) {
        public Range {
            Assert.notNull(start, "Range start must not be null");
            Assert.notNull(end, "Range end must not be null");
            Assert.isTrue(compare(start, end) < 0, "Range must be non-empty and lexicographically ordered");
        }

        private static int compare(Position first, Position second) {
            int line = Integer.compare(first.line(), second.line());
            return line == 0 ? Integer.compare(first.character(), second.character()) : line;
        }
    }
}
