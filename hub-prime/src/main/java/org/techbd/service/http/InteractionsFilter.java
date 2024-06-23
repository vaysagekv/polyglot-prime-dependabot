package org.techbd.service.http;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.techbd.conf.Configuration;
import org.techbd.service.http.Interactions.RequestResponseEncountered;
import org.techbd.udi.UdiPrimeJpaConfig;
import org.techbd.udi.auto.jooq.ingress.routines.UdiInsertInteraction;
import org.techbd.util.ArtifactStore;
import org.techbd.util.ArtifactStore.Artifact;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@WebFilter(urlPatterns = "/*")
@Order(-999)
public class InteractionsFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(InteractionsFilter.class.getName());

    @Value("${org.techbd.service.http.interactions.default-persist-strategy:#{null}}")
    private String defaultPersistStrategy;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private UdiPrimeJpaConfig udiPrimeJpaConfig;

    @Autowired
    private ApplicationContext appContext;

    public static final Interactions interactions = new Interactions();

    protected static final void setActiveRequestTenant(final @NonNull HttpServletRequest request,
            final @NonNull Interactions.Tenant tenant) {
        request.setAttribute("activeHttpRequestTenant", tenant);
    }

    public static final Interactions.Tenant getActiveRequestTenant(final @NonNull HttpServletRequest request) {
        return (Interactions.Tenant) request.getAttribute("activeHttpRequestTenant");
    }

    protected static final void setActiveRequestEnc(final @NonNull HttpServletRequest request,
            final @NonNull Interactions.RequestEncountered re) {
        request.setAttribute("activeHttpRequestEncountered", re);
        setActiveRequestTenant(request, re.tenant());
    }

    public static final Interactions.RequestEncountered getActiveRequestEnc(final @NonNull HttpServletRequest request) {
        return (Interactions.RequestEncountered) request.getAttribute("activeHttpRequestEncountered");
    }

    protected static final void setActiveInteraction(final @NonNull HttpServletRequest request,
            final @NonNull Interactions.RequestResponseEncountered rre) {
        request.setAttribute("activeHttpInteraction", rre);
    }

    public static final Interactions.RequestResponseEncountered getActiveInteraction(
            final @NonNull HttpServletRequest request) {
        return (Interactions.RequestResponseEncountered) request.getAttribute("activeHttpInteraction");
    }

    @Override
    protected void doFilterInternal(final @NonNull HttpServletRequest origRequest,
            @NonNull final HttpServletResponse origResponse, @NonNull final FilterChain chain)
            throws IOException, ServletException {
        if (isAsyncDispatch(origRequest)) {
            chain.doFilter(origRequest, origResponse);
            return;
        }

        // for the /Bundle/$validate (at least, and maybe even /Bundle) we want
        // to store the entire request/response cycle including the response payload;
        // for everything else we only want to keep the request and response without
        // payloads
        final var requestURI = origRequest.getRequestURI();
        final var persistPayloads = requestURI.contains("/Bundle/$validate");

        final var mutatableReq = new ContentCachingRequestWrapper(origRequest);
        final var requestBody = persistPayloads ? mutatableReq.getContentAsByteArray()
                : "persistPayloads = false".getBytes();

        // Prepare a serializable RequestEncountered as early as possible in
        // request cycle and store it as an attribute so that other filters
        // and controllers can use the common "active request" instance.
        final var requestEncountered = new Interactions.RequestEncountered(mutatableReq, requestBody);
        setActiveRequestEnc(origRequest, requestEncountered);

        final var mutatableResp = new ContentCachingResponseWrapper(origResponse);

        chain.doFilter(mutatableReq, mutatableResp);

        // for the /Bundle/$validate (at least, and maybe even /Bundle) we want
        // to store the entire request/response cycle including the response payload;
        // for everything else we only want to keep the request and response without
        // payloads
        RequestResponseEncountered rre = null;
        if (!persistPayloads) {
            rre = new Interactions.RequestResponseEncountered(requestEncountered,
                    new Interactions.ResponseEncountered(mutatableResp, requestEncountered,
                            "persistPayloads = false".getBytes()));
        } else {
            rre = new Interactions.RequestResponseEncountered(requestEncountered,
                    new Interactions.ResponseEncountered(mutatableResp, requestEncountered,
                            mutatableResp.getContentAsByteArray()));
        }

        interactions.addHistory(rre);
        setActiveInteraction(mutatableReq, rre);

        // we want to find our persistence strategy in either properties or in header;
        // because X-TechBD-Interaction-Persistence-Strategy is global, document it in
        // SwaggerConfig.customGlobalHeaders
        final var strategyJson = Optional
                .ofNullable(mutatableReq.getHeader(Interactions.Servlet.HeaderName.Request.PERSISTENCE_STRATEGY))
                .orElse(defaultPersistStrategy);
        final var asb = new ArtifactStore.Builder()
                .strategyJson(strategyJson)
                .provenanceJson(mutatableReq.getHeader(Interactions.Servlet.HeaderName.Request.PROVENANCE))
                .mailSender(mailSender)
                .appContext(appContext);

        final var provenance = "%s.doFilterInternal".formatted(InteractionsFilter.class.getName());
        final var artifact = ArtifactStore.jsonArtifact(rre, rre.interactionId().toString(),
                InteractionsFilter.class.getName() + ".interaction", asb.getProvenance());

        // TODO: use either annotations or other programmable logic for whether to store
        // interactions into the database (otherwise "all" is too expensive); for
        // example either all POST or just /Bundle/* etc.

        // TODO: convert this to a stored procedure and insert as one record; the user
        // should not need to know any internals, just pass in an interation ID and payload
        try {
            final var dsl = udiPrimeJpaConfig.dsl();
            final var intrHubId = rre.interactionId().toString();  
            final var insertInteraction = new UdiInsertInteraction();
            insertInteraction.setInteractionId(intrHubId);
            insertInteraction.setRequestPayload(Configuration.objectMapper.readTree(artifact.getJsonString().orElse("no artifact.getJsonString() in " + provenance)));
            insertInteraction.setElaboration(Configuration.objectMapper.readTree("{}"));
            insertInteraction.setCreatedBy(InteractionsFilter.class.getName());
            insertInteraction.setProvenance(provenance);
            insertInteraction.execute(dsl.configuration());
        } catch (Exception e) {
            LOG.error("insert HUB_INTERACTION and SAT_INTERACTION_HTTP_REQUEST", e);
        }

        final var ps = asb.build();
        mutatableResp.setHeader(Interactions.Servlet.HeaderName.Response.PERSISTENCE_STRATEGY_ARGS, strategyJson);
        if (ps != null) {
            final AtomicInteger info = new AtomicInteger(0);
            final AtomicInteger issue = new AtomicInteger(0);
            mutatableResp.setHeader(Interactions.Servlet.HeaderName.Response.PERSISTENCE_STRATEGY_FACTORY,
                    ps.getClass().getName());
            ps.persist(
                    artifact,
                    Optional.of(new ArtifactStore.PersistenceReporter() {
                        @Override
                        public void info(String message) {
                            mutatableResp
                                    .setHeader(Interactions.Servlet.HeaderName.Response.PERSISTENCE_STRATEGY_INSTANCE
                                            + "-Info-" + info.getAndIncrement(), message);
                        }

                        @Override
                        public void issue(String message) {
                            mutatableResp
                                    .setHeader(Interactions.Servlet.HeaderName.Response.PERSISTENCE_STRATEGY_INSTANCE
                                            + "-Issue-" + issue.getAndIncrement(), message);
                        }

                        @Override
                        public void persisted(Artifact artifact, String... location) {
                            // not doing anything with this yet
                        }
                    }));
        }

        mutatableResp.copyBodyToResponse();
    }
}
