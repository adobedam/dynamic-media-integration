package com.adobedam.core.dms.filters;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.engine.EngineConstants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.osgi.service.component.propertytypes.ServiceRanking;
import org.osgi.service.component.propertytypes.ServiceVendor;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sling Filter to intercept .model.json requests and modify DAM paths based on
 * Scene7 metadata.
 */
@Component(service = Filter.class, configurationPolicy = org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE, property = {
        EngineConstants.SLING_FILTER_SCOPE + "=" + EngineConstants.FILTER_SCOPE_COMPONENT,
        "sling.filter.pattern=.*\\.model\\.json" // Intercept all .model.json requests
})
@ServiceRanking(1000) // Ensure high-priority execution
@ServiceDescription("ADOBE DAM - Dynamic Media Services - Model JSON Filter")
@ServiceVendor("ADOBE DAM")
public class ModelJsonFilter implements Filter {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // Use ThreadLocal to track request processing
    private final ThreadLocal<AtomicBoolean> requestProcessed = ThreadLocal.withInitial(AtomicBoolean::new);

    private List<String> urlPatterns;

    @ObjectClassDefinition(name = "ADOBE DAM - Dynamic Media Services - Model JSON Filter", description = "Configuration for the Dynamic Media Services Model JSON Filter.")
    @interface Config {
        @AttributeDefinition(name = "URL Patterns", description = "List of all URL Patterns of mode.json requests to be intercepted.")
        String[] url_patterns() default {};
    }

    @Activate
    @Modified
    void configure(Config config) {
        urlPatterns = Arrays.asList(config.url_patterns());
        
    }

    @Override
    public void init(FilterConfig filterConfig) {
        logger.info("[dms-integration.core] ModelJsonFilter.java : init() : Initialized successfully.");
        // No specific initialization needed
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        logger.debug("[dms-integration.core] ModelJsonFilter.java : doFilter() : Method called for URL : {}",((SlingHttpServletRequest) request).getRequestURI());
        if (!(request instanceof SlingHttpServletRequest) || !(response instanceof SlingHttpServletResponse)) {
            logger.debug("[dms-integration.core] ModelJsonFilter.java : doFilter() : Invalid request/response types. Skipping filter.");
            chain.doFilter(request, response);
            return;
        }

        SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
        String requestUri = slingRequest.getRequestURI();

        // Check if the URL starts with one of the allowed patterns
        if (!matchesAllowedPattern(requestUri)) {
            logger.debug("[dms-integration.core] ModelJsonFilter.java : doFilter() : Skipping request not in allowed paths: {}", requestUri);
            chain.doFilter(request, response);
            return;
        }

        // Ensure we process the request only once per thread
        if (requestProcessed.get().get()) {
            logger.debug("[dms-integration.core] ModelJsonFilter.java : doFilter() : Skipping duplicate processing for request: {}",slingRequest.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        // Mark request as processed
        requestProcessed.get().set(true);

        // Create a custom response wrapper to capture the JSON output
        ResponseWrapper capturingResponse = new ResponseWrapper(slingResponse);
        chain.doFilter(request, capturingResponse);
        // Get the JSON response as string
        String jsonResponse = capturingResponse.toString();
        logger.debug(
                "[dms-integration.core] ModelJsonFilter.java : doFilter() : Captured JSON response [BEFORE] processing : {}",jsonResponse);
        if (!jsonResponse.isEmpty()) {
            try {
                JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
                ResourceResolver resourceResolver = slingRequest.getResourceResolver();

                // Scan and modify JSON for Scene7 assets
                scanAndModifyJson(jsonObject, resourceResolver);

                // Write the modified JSON back to response
                slingResponse.setContentType("application/json");
                slingResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
                PrintWriter writer = slingResponse.getWriter();
                writer.write(jsonObject.toString());
                writer.flush();
                logger.debug("[dms-integration.core] ModelJsonFilter.java : doFilter() : Captured JSON response [AFTER] processing : {}",jsonObject.toString());
                return;

            } catch (Exception e) {
                // Log error and return original response
                logger.error("[dms-integration.core] ModelJsonFilter.java : doFilter() : Error processing JSON response.",e);
                slingResponse.getWriter().write(jsonResponse);
            }
        }
    }

    @Override
    public void destroy() {
        logger.info("[dms-integration.core] ModelJsonFilter.java : destroy() : Destroyed successfully.");
        // No specific cleanup needed
    }

    /**
     * Checks if the request URI starts with one of the allowed patterns.
     */
    private boolean matchesAllowedPattern(String requestUri) {
        return urlPatterns.stream().anyMatch(requestUri::startsWith);
    }

    /**
     * Recursively scans a JSON object and modifies DAM asset references if they are
     * published to Scene7.
     */
    private void scanAndModifyJson(Object jsonObject, ResourceResolver resourceResolver) {
        if (jsonObject instanceof JsonObject) {
            JsonObject json = (JsonObject) jsonObject;

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && value.getAsString().startsWith("/content/dam")) {
                    // Process DAM path and replace if Scene7 published
                    String damPath = value.getAsString();
                    logger.debug("[dms-integration.core] ModelJsonFilter.java : scanAndModifyJson() : Found DAM Path in JSON: {}", damPath);
                    String scene7Url = getScene7Url(damPath, resourceResolver);
                    if (scene7Url != null) {
                        logger.debug("[dms-integration.core] ModelJsonFilter.java : scanAndModifyJson() : Replacing with Scene7 URL: {}", scene7Url);
                        json.addProperty(entry.getKey(), scene7Url); // Replace DAM path with Scene7 URL
                    } else {
                        logger.debug("[dms-integration.core] ModelJsonFilter.java : scanAndModifyJson() : Scene7 URL not found for DAM Path: {}", damPath);
                    }
                } else {
                    // Recursively process nested objects or arrays
                    scanAndModifyJson(value, resourceResolver);
                }
            }
        } else if (jsonObject instanceof JsonArray) {
            JsonArray jsonArray = (JsonArray) jsonObject;
            for (int i = 0; i < jsonArray.size(); i++) {
                scanAndModifyJson(jsonArray.get(i), resourceResolver);
            }
        }
    }

    /**
     * Retrieves Scene7 URL if the asset is published to Scene7.
     */
    private String getScene7Url(String damPath, ResourceResolver resourceResolver) {
        logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Processing DAM path : {}",
                damPath);

        try {
            Resource assetResource = resourceResolver.getResource(damPath + "/jcr:content/metadata");
            if (assetResource != null) {
                ValueMap metadata = assetResource.adaptTo(ValueMap.class);
                if (metadata == null) {
                    logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Metadata is NULL for asset: {}",damPath);
                    return null;
                }

                String scene7Status = metadata.get("dam:scene7FileStatus", String.class);
                if ("PublishComplete".equals(scene7Status)) {
                    String scene7Domain = metadata.get("dam:scene7Domain", String.class);
                    String scene7File = metadata.get("dam:scene7File", String.class);

                    if (scene7Domain == null || scene7File == null) {
                        logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Scene7 metadata missing for asset: {}",damPath);
                        return null;
                    }

                    String returnUrl = scene7Domain + "is/image/" + scene7File;
                    logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Scene7 URL : {}",returnUrl);
                    return returnUrl;
                } else {
                    logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Asset is NOT published to Scene7 - PATH = {}.",damPath);
                }
            }
        } catch (Exception e) {
            // Suppress XMPException errors and only log them at DEBUG level
            logger.debug("[dms-integration.core] ModelJsonFilter.java : getScene7Url() : Suppressed exception while fetching metadata for asset: {}",damPath, e);
        }

        return null; // Return null if asset is not published to Scene7
    }
}