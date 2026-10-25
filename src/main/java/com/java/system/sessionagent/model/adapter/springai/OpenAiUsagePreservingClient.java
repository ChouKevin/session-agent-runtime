package com.java.system.sessionagent.model.adapter.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ClientOptions;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeleteParams;
import com.openai.models.chat.completions.ChatCompletionDeleted;
import com.openai.models.chat.completions.ChatCompletionListPage;
import com.openai.models.chat.completions.ChatCompletionListParams;
import com.openai.models.chat.completions.ChatCompletionRetrieveParams;
import com.openai.models.chat.completions.ChatCompletionUpdateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.AdminService;
import com.openai.services.blocking.AudioService;
import com.openai.services.blocking.BatchService;
import com.openai.services.blocking.BetaService;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.CompletionService;
import com.openai.services.blocking.ContainerService;
import com.openai.services.blocking.ContentProvenanceCheckService;
import com.openai.services.blocking.ConversationService;
import com.openai.services.blocking.DecisionService;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.EvalService;
import com.openai.services.blocking.FileService;
import com.openai.services.blocking.FineTuningService;
import com.openai.services.blocking.GraderService;
import com.openai.services.blocking.ImageService;
import com.openai.services.blocking.LiveService;
import com.openai.services.blocking.ModelService;
import com.openai.services.blocking.ModerationService;
import com.openai.services.blocking.RealtimeService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.SafetyService;
import com.openai.services.blocking.SkillService;
import com.openai.services.blocking.UploadService;
import com.openai.services.blocking.VectorStoreService;
import com.openai.services.blocking.VideoService;
import com.openai.services.blocking.WebhookService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.openai.services.blocking.chat.completions.MessageService;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Preserves defective provider usage without changing response text or fabricating ordinary token counts. */
public final class OpenAiUsagePreservingClient implements OpenAIClient {
    static final String RAW_USAGE_METADATA = "sessionagent.openai.raw_usage";

    private final OpenAIClient delegate;
    private final ChatService chat;

    public OpenAiUsagePreservingClient(OpenAIClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "OpenAI SDK delegate must not be null");
        this.chat = new UsageChatService(delegate.chat());
    }

    @Override public OpenAIClientAsync async() { return delegate.async(); }

    @Override public OpenAIClient.WithRawResponse withRawResponse() { return delegate.withRawResponse(); }

    @Override public OpenAIClient withOptions(Consumer<ClientOptions.Builder> argument0) { return new OpenAiUsagePreservingClient(delegate.withOptions(argument0)); }

    @Override public DecisionService decisions() { return delegate.decisions(); }

    @Override public CompletionService completions() { return delegate.completions(); }

    @Override public ChatService chat() { return chat; }

    @Override public EmbeddingService embeddings() { return delegate.embeddings(); }

    @Override public FileService files() { return delegate.files(); }

    @Override public ImageService images() { return delegate.images(); }

    @Override public ContentProvenanceCheckService contentProvenanceChecks() { return delegate.contentProvenanceChecks(); }

    @Override public AudioService audio() { return delegate.audio(); }

    @Override public ModerationService moderations() { return delegate.moderations(); }

    @Override public ModelService models() { return delegate.models(); }

    @Override public FineTuningService fineTuning() { return delegate.fineTuning(); }

    @Override public GraderService graders() { return delegate.graders(); }

    @Override public VectorStoreService vectorStores() { return delegate.vectorStores(); }

    @Override public SafetyService safety() { return delegate.safety(); }

    @Override public WebhookService webhooks() { return delegate.webhooks(); }

    @Override public BetaService beta() { return delegate.beta(); }

    @Override public BatchService batches() { return delegate.batches(); }

    @Override public UploadService uploads() { return delegate.uploads(); }

    @Override public AdminService admin() { return delegate.admin(); }

    @Override public ResponseService responses() { return delegate.responses(); }

    @Override public LiveService live() { return delegate.live(); }

    @Override public RealtimeService realtime() { return delegate.realtime(); }

    @Override public ConversationService conversations() { return delegate.conversations(); }

    @Override public EvalService evals() { return delegate.evals(); }

    @Override public ContainerService containers() { return delegate.containers(); }

    @Override public SkillService skills() { return delegate.skills(); }

    @Override public VideoService videos() { return delegate.videos(); }

    @Override public void close() { delegate.close(); }

    private static final class UsageChatService implements ChatService {
        private final ChatService delegate;
        private final ChatCompletionService completions;

        private UsageChatService(ChatService delegate) {
            this.delegate = Objects.requireNonNull(delegate, "OpenAI SDK delegate must not be null");
            this.completions = new UsageCompletionService(delegate.completions());
        }

        @Override public ChatService.WithRawResponse withRawResponse() { return delegate.withRawResponse(); }

        @Override public ChatService withOptions(Consumer<ClientOptions.Builder> argument0) { return new UsageChatService(delegate.withOptions(argument0)); }

        @Override public ChatCompletionService completions() { return completions; }
    }

    private static final class UsageCompletionService implements ChatCompletionService {
        private final ChatCompletionService delegate;
        private final ChatCompletionService.WithRawResponse raw;

        private UsageCompletionService(ChatCompletionService delegate) {
            this.delegate = Objects.requireNonNull(delegate, "OpenAI SDK delegate must not be null");
            this.raw = new UsageRawCompletionService(delegate.withRawResponse());
        }

        @Override public ChatCompletionService.WithRawResponse withRawResponse() { return raw; }

        @Override public ChatCompletionService withOptions(Consumer<ClientOptions.Builder> argument0) { return new UsageCompletionService(delegate.withOptions(argument0)); }

        @Override public MessageService messages() { return delegate.messages(); }

        @Override public ChatCompletion create(ChatCompletionCreateParams argument0) { return delegate.create(argument0); }

        @Override public ChatCompletion create(ChatCompletionCreateParams argument0, RequestOptions argument1) { return delegate.create(argument0, argument1); }

        @Override public <T> StructuredChatCompletion<T> create(StructuredChatCompletionCreateParams<T> argument0) { return delegate.create(argument0); }

        @Override public <T> StructuredChatCompletion<T> create(StructuredChatCompletionCreateParams<T> argument0, RequestOptions argument1) { return delegate.create(argument0, argument1); }

        @Override public StreamResponse<ChatCompletionChunk> createStreaming(ChatCompletionCreateParams argument0) { return delegate.createStreaming(argument0); }

        @Override public StreamResponse<ChatCompletionChunk> createStreaming(ChatCompletionCreateParams argument0, RequestOptions argument1) { return delegate.createStreaming(argument0, argument1); }

        @Override public StreamResponse<ChatCompletionChunk> createStreaming(StructuredChatCompletionCreateParams<?> argument0) { return delegate.createStreaming(argument0); }

        @Override public StreamResponse<ChatCompletionChunk> createStreaming(StructuredChatCompletionCreateParams<?> argument0, RequestOptions argument1) { return delegate.createStreaming(argument0, argument1); }

        @Override public ChatCompletion retrieve(String argument0) { return delegate.retrieve(argument0); }

        @Override public ChatCompletion retrieve(String argument0, ChatCompletionRetrieveParams argument1, RequestOptions argument2) { return delegate.retrieve(argument0, argument1, argument2); }

        @Override public ChatCompletion retrieve(String argument0, ChatCompletionRetrieveParams argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public ChatCompletion retrieve(ChatCompletionRetrieveParams argument0, RequestOptions argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public ChatCompletion retrieve(ChatCompletionRetrieveParams argument0) { return delegate.retrieve(argument0); }

        @Override public ChatCompletion retrieve(String argument0, RequestOptions argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public ChatCompletion update(String argument0, ChatCompletionUpdateParams argument1) { return delegate.update(argument0, argument1); }

        @Override public ChatCompletion update(String argument0, ChatCompletionUpdateParams argument1, RequestOptions argument2) { return delegate.update(argument0, argument1, argument2); }

        @Override public ChatCompletion update(ChatCompletionUpdateParams argument0) { return delegate.update(argument0); }

        @Override public ChatCompletion update(ChatCompletionUpdateParams argument0, RequestOptions argument1) { return delegate.update(argument0, argument1); }

        @Override public ChatCompletionListPage list() { return delegate.list(); }

        @Override public ChatCompletionListPage list(ChatCompletionListParams argument0, RequestOptions argument1) { return delegate.list(argument0, argument1); }

        @Override public ChatCompletionListPage list(ChatCompletionListParams argument0) { return delegate.list(argument0); }

        @Override public ChatCompletionListPage list(RequestOptions argument0) { return delegate.list(argument0); }

        @Override public ChatCompletionDeleted delete(String argument0) { return delegate.delete(argument0); }

        @Override public ChatCompletionDeleted delete(String argument0, ChatCompletionDeleteParams argument1, RequestOptions argument2) { return delegate.delete(argument0, argument1, argument2); }

        @Override public ChatCompletionDeleted delete(String argument0, ChatCompletionDeleteParams argument1) { return delegate.delete(argument0, argument1); }

        @Override public ChatCompletionDeleted delete(ChatCompletionDeleteParams argument0, RequestOptions argument1) { return delegate.delete(argument0, argument1); }

        @Override public ChatCompletionDeleted delete(ChatCompletionDeleteParams argument0) { return delegate.delete(argument0); }

        @Override public ChatCompletionDeleted delete(String argument0, RequestOptions argument1) { return delegate.delete(argument0, argument1); }
    }

    private static final class UsageRawCompletionService implements ChatCompletionService.WithRawResponse {
        private final ChatCompletionService.WithRawResponse delegate;

        private UsageRawCompletionService(ChatCompletionService.WithRawResponse delegate) {
            this.delegate = Objects.requireNonNull(delegate, "OpenAI SDK delegate must not be null");
        }

        @Override public ChatCompletionService.WithRawResponse withOptions(Consumer<ClientOptions.Builder> argument0) { return new UsageRawCompletionService(delegate.withOptions(argument0)); }

        @Override public MessageService.WithRawResponse messages() { return delegate.messages(); }

        @Override public HttpResponseFor<ChatCompletion> create(ChatCompletionCreateParams argument0) { return new UsageResponse(delegate.create(argument0)); }

        @Override public HttpResponseFor<ChatCompletion> create(ChatCompletionCreateParams argument0, RequestOptions argument1) { return new UsageResponse(delegate.create(argument0, argument1)); }

        @Override public HttpResponseFor<StreamResponse<ChatCompletionChunk>> createStreaming(ChatCompletionCreateParams argument0) { return delegate.createStreaming(argument0); }

        @Override public HttpResponseFor<StreamResponse<ChatCompletionChunk>> createStreaming(ChatCompletionCreateParams argument0, RequestOptions argument1) { return delegate.createStreaming(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(String argument0) { return delegate.retrieve(argument0); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(String argument0, ChatCompletionRetrieveParams argument1, RequestOptions argument2) { return delegate.retrieve(argument0, argument1, argument2); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(String argument0, ChatCompletionRetrieveParams argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(ChatCompletionRetrieveParams argument0, RequestOptions argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(ChatCompletionRetrieveParams argument0) { return delegate.retrieve(argument0); }

        @Override public HttpResponseFor<ChatCompletion> retrieve(String argument0, RequestOptions argument1) { return delegate.retrieve(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletion> update(String argument0, ChatCompletionUpdateParams argument1) { return delegate.update(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletion> update(String argument0, ChatCompletionUpdateParams argument1, RequestOptions argument2) { return delegate.update(argument0, argument1, argument2); }

        @Override public HttpResponseFor<ChatCompletion> update(ChatCompletionUpdateParams argument0) { return delegate.update(argument0); }

        @Override public HttpResponseFor<ChatCompletion> update(ChatCompletionUpdateParams argument0, RequestOptions argument1) { return delegate.update(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletionListPage> list() { return delegate.list(); }

        @Override public HttpResponseFor<ChatCompletionListPage> list(ChatCompletionListParams argument0, RequestOptions argument1) { return delegate.list(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletionListPage> list(ChatCompletionListParams argument0) { return delegate.list(argument0); }

        @Override public HttpResponseFor<ChatCompletionListPage> list(RequestOptions argument0) { return delegate.list(argument0); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(String argument0) { return delegate.delete(argument0); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(String argument0, ChatCompletionDeleteParams argument1, RequestOptions argument2) { return delegate.delete(argument0, argument1, argument2); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(String argument0, ChatCompletionDeleteParams argument1) { return delegate.delete(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(ChatCompletionDeleteParams argument0, RequestOptions argument1) { return delegate.delete(argument0, argument1); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(ChatCompletionDeleteParams argument0) { return delegate.delete(argument0); }

        @Override public HttpResponseFor<ChatCompletionDeleted> delete(String argument0, RequestOptions argument1) { return delegate.delete(argument0, argument1); }
    }

    private record UsageResponse(HttpResponseFor<ChatCompletion> delegate) implements HttpResponseFor<ChatCompletion> {
        @Override public ChatCompletion parse() { return preserveUsage(delegate.parse()); }
        @Override public int statusCode() { return delegate.statusCode(); }
        @Override public Headers headers() { return delegate.headers(); }
        @Override public Optional<String> requestId() { return delegate.requestId(); }
        @Override public InputStream body() { return delegate.body(); }
        @Override public void close() { delegate.close(); }
    }

    private static ChatCompletion preserveUsage(ChatCompletion response) {
        Optional<CompletionUsage> raw = response._usage().asKnown();
        if (raw.isPresent() && safelyMappedBySpring(raw.get())) {
            return response;
        }
        if (response._usage().isMissing() || response._usage().isNull()) {
            return response;
        }
        ChatCompletion.Builder sanitized = response.toBuilder()
                .usage(JsonField.<CompletionUsage>ofNullable(null));
        if (raw.isPresent()) {
            try {
                sanitized.putAdditionalProperty(RAW_USAGE_METADATA,
                        JsonValue.from(ObjectMappers.jsonMapper().writeValueAsString(raw.get())));
            } catch (JsonProcessingException ignored) {
                // A usage serialization defect is unknown accounting, not a failed answer.
            }
        }
        return sanitized.build();
    }

    private static boolean safelyMappedBySpring(CompletionUsage usage) {
        if (!fitsFrameworkCount(usage._promptTokens().asKnown())
                || !fitsFrameworkCount(usage._completionTokens().asKnown())
                || !fitsFrameworkCount(usage._totalTokens().asKnown())) {
            return false;
        }
        if (usage._promptTokensDetails().isMissing() || usage._promptTokensDetails().isNull()) {
            return true;
        }
        return usage._promptTokensDetails().asKnown()
                .map(details -> details._cachedTokens().isMissing() || details._cachedTokens().isNull()
                        || details._cachedTokens().asKnown().isPresent())
                .orElse(false);
    }

    private static boolean fitsFrameworkCount(Optional<Long> count) {
        return count.filter(value -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE).isPresent();
    }
}
