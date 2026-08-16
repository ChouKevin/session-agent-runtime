package com.java.system.sessionagent.semantic.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.util.Assert;

/** Closed model input union for the provider internal-reference follow-up target. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InternalReferenceTarget.Type.class, name = "TYPE"),
        @JsonSubTypes.Type(value = InternalReferenceTarget.Method.class, name = "METHOD"),
        @JsonSubTypes.Type(value = InternalReferenceTarget.Member.class, name = "MEMBER")
})
public sealed interface InternalReferenceTarget permits InternalReferenceTarget.Type, InternalReferenceTarget.Method,
        InternalReferenceTarget.Member {

    record Type(ProviderDtos.SourceTypeIdentityPayload identity) implements InternalReferenceTarget {
        public Type { Assert.notNull(identity, "Internal-reference type identity must not be null"); }
    }

    record Method(ProviderDtos.MethodTargetPayload identity) implements InternalReferenceTarget {
        public Method { Assert.notNull(identity, "Internal-reference method identity must not be null"); }
    }

    record Member(ProviderDtos.SourceMemberIdentityPayload identity) implements InternalReferenceTarget {
        public Member { Assert.notNull(identity, "Internal-reference member identity must not be null"); }
    }
}
