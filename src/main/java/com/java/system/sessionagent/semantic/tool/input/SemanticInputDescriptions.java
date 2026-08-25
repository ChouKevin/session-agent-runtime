package com.java.system.sessionagent.semantic.tool.input;

/** Shared model guidance for the flat Semantic Query contract. */
final class SemanticInputDescriptions {

    static final String REPOSITORY_ID =
            "Exact repositoryId copied from list_repositories or reliable visible history";
    static final String REVISION =
            "Exact revision paired with repositoryId in the same prior result; never invent or normalize it";
    static final String PACKAGE_NAME =
            "Exact Java packageName copied from a prior Semantic candidate or source result";
    static final String CLASS_NAME =
            "Exact Java className copied from the same prior Semantic identity";
    static final String SOURCE_FILE =
            "Exact repository-relative sourceFile copied from the same prior Semantic identity";
    static final String METHOD_NAME =
            "Exact methodName copied from the same prior Semantic method identity";
    static final String PARAMETER_TYPES =
            "Ordered parameterTypes copied unchanged from the same prior method identity; use an empty list for no arguments";
    static final String OFFSET =
            "Paging offset copied from a prior page when continuing; otherwise omit to use the provider default";
    static final String LIMIT =
            "Paging limit copied from a prior page when continuing; otherwise omit to use the provider default";

    private SemanticInputDescriptions() {
    }
}
