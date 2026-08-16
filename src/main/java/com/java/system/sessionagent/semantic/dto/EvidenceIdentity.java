package com.java.system.sessionagent.semantic.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.util.Assert;

/** Closed model input union for exact provider evidence-source identities. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EvidenceIdentity.AnnotationSql.class, name = "ANNOTATION_SQL"),
        @JsonSubTypes.Type(value = EvidenceIdentity.MapperStatement.class, name = "MAPPER_STATEMENT"),
        @JsonSubTypes.Type(value = EvidenceIdentity.MapperFragment.class, name = "MAPPER_FRAGMENT")
})
public sealed interface EvidenceIdentity permits EvidenceIdentity.AnnotationSql, EvidenceIdentity.MapperStatement,
        EvidenceIdentity.MapperFragment {

    record AnnotationSql(ProviderDtos.MapperStatementIdentityPayload statementIdentity) implements EvidenceIdentity {
        public AnnotationSql { Assert.notNull(statementIdentity, "Annotation SQL statement identity must not be null"); }
    }

    record MapperStatement(ProviderDtos.MapperStatementIdentityPayload statementIdentity) implements EvidenceIdentity {
        public MapperStatement { Assert.notNull(statementIdentity, "Mapper statement identity must not be null"); }
    }

    record MapperFragment(ProviderDtos.MapperFragmentIdentityPayload fragmentIdentity) implements EvidenceIdentity {
        public MapperFragment { Assert.notNull(fragmentIdentity, "Mapper fragment identity must not be null"); }
    }
}
