package com.java.system.sessionagent.semantic.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;

/** Closed model input union for the exact provider concept-follow-up identities. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SemanticIdentity.Type.class, name = "TYPE"),
        @JsonSubTypes.Type(value = SemanticIdentity.Method.class, name = "METHOD"),
        @JsonSubTypes.Type(value = SemanticIdentity.Field.class, name = "FIELD"),
        @JsonSubTypes.Type(value = SemanticIdentity.AnnotationUsage.class, name = "ANNOTATION_USAGE"),
        @JsonSubTypes.Type(value = SemanticIdentity.TypeUsage.class, name = "TYPE_USAGE"),
        @JsonSubTypes.Type(value = SemanticIdentity.ApiRoute.class, name = "API_ROUTE"),
        @JsonSubTypes.Type(value = SemanticIdentity.MqDestination.class, name = "MQ_DESTINATION"),
        @JsonSubTypes.Type(value = SemanticIdentity.Schedule.class, name = "SCHEDULE"),
        @JsonSubTypes.Type(value = SemanticIdentity.MapperStatement.class, name = "MAPPER_STATEMENT"),
        @JsonSubTypes.Type(value = SemanticIdentity.MapperStatementVariant.class, name = "MAPPER_STATEMENT_VARIANT")
})
public sealed interface SemanticIdentity permits SemanticIdentity.Type, SemanticIdentity.Method, SemanticIdentity.Field,
        SemanticIdentity.AnnotationUsage, SemanticIdentity.TypeUsage, SemanticIdentity.ApiRoute, SemanticIdentity.MqDestination,
        SemanticIdentity.Schedule, SemanticIdentity.MapperStatement, SemanticIdentity.MapperStatementVariant {

    record Type(ProviderDtos.SourceTypeIdentityPayload sourceType) implements SemanticIdentity {
        public Type { Assert.notNull(sourceType, "Concept type source identity must not be null"); }
    }

    record Method(ProviderDtos.MethodTargetPayload target) implements SemanticIdentity {
        public Method { Assert.notNull(target, "Concept method target must not be null"); }
    }

    record Field(ProviderDtos.SourceMemberIdentityPayload identity) implements SemanticIdentity {
        public Field {
            Assert.notNull(identity, "Concept field identity must not be null");
            Assert.isTrue(identity instanceof ProviderDtos.SourceMemberIdentityPayload.TypeMember
                    || identity instanceof ProviderDtos.SourceMemberIdentityPayload.MethodScoped,
                    "Concept field identity must be type- or method-scoped");
        }
    }

    record AnnotationUsage(ProviderDtos.DeclarationSubjectPayload declaration,
                           ProviderDtos.AnnotationTypePayload annotationType) implements SemanticIdentity {
        public AnnotationUsage {
            Assert.notNull(declaration, "Annotation declaration must not be null");
            Assert.notNull(annotationType, "Annotation type must not be null");
        }
    }

    record TypeUsage(ProviderDtos.DeclarationSubjectPayload owner,
                     ProviderDtos.TypeUsageLocationPayload location,
                     List<ProviderDtos.TypeUsagePathPayload> path,
                     ProviderDtos.ReferencedTypePayload referencedType) implements SemanticIdentity {
        public TypeUsage {
            Assert.notNull(owner, "Type-usage owner must not be null");
            Assert.notNull(location, "Type-usage location must not be null");
            path = List.copyOf(path);
            Assert.notNull(referencedType, "Referenced type must not be null");
        }
    }

    record ApiRoute(ProviderDtos.MethodTargetPayload target, String httpVerb, String route) implements SemanticIdentity {
        public ApiRoute {
            Assert.notNull(target, "API route target must not be null");
            Assert.hasText(httpVerb, "API route HTTP verb must not be blank");
            Assert.hasText(route, "API route must not be blank");
        }
    }

    record MqDestination(ProviderDtos.MethodTargetPayload target, String broker, String destination) implements SemanticIdentity {
        public MqDestination {
            Assert.notNull(target, "MQ destination target must not be null");
            Assert.hasText(broker, "MQ broker must not be blank");
            Assert.hasText(destination, "MQ destination must not be blank");
        }
    }

    record Schedule(ProviderDtos.MethodTargetPayload target, String triggerKind,
                    Optional<String> triggerValue) implements SemanticIdentity {
        public Schedule {
            Assert.notNull(target, "Schedule target must not be null");
            Assert.hasText(triggerKind, "Schedule trigger kind must not be blank");
            triggerValue = Optional.ofNullable(triggerValue).orElse(Optional.empty());
        }
    }

    record MapperStatement(ProviderDtos.MapperStatementKeyPayload identity) implements SemanticIdentity {
        public MapperStatement { Assert.notNull(identity, "Mapper statement identity must not be null"); }
    }

    record MapperStatementVariant(ProviderDtos.MapperStatementIdentityPayload identity) implements SemanticIdentity {
        public MapperStatementVariant { Assert.notNull(identity, "Mapper statement variant identity must not be null"); }
    }
}
