package com.softropic.skillars.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ua_parser.Client;
import ua_parser.Parser;

/**
 * Utility class for extracting client context information from HTTP requests.
 * <p>
 * Provides methods to capture client metadata such as operating system, browser,
 * device type, and IP address from the current request context. This information
 * is useful for security auditing, user notifications, and analytics.
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * Map&lt;String, Object&gt; clientInfo = ClientContextProvider.getClientContextMap();
 * // clientInfo contains: operatingSystem, browserName, device, ipAddress
 * </pre>
 *
 * @see RequestMetadataProvider
 */
public final class ClientContextProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientContextProvider.class);

    private ClientContextProvider() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Extracts client context information from the current HTTP request.
     * <p>
     * This method parses the user agent string to identify the client's operating
     * system, browser, and device type, and retrieves the IP address from the
     * request metadata.
     *
     * @return a map containing client context with keys:
     *         <ul>
     *           <li>"operatingSystem" - OS family (e.g., "Windows", "Mac OS X")</li>
     *           <li>"browserName" - Browser family (e.g., "Chrome", "Firefox")</li>
     *           <li>"device" - Device family (e.g., "Desktop", "Mobile")</li>
     *           <li>"ipAddress" - Client IP address</li>
     *         </ul>
     *         Any unavailable fields will have null values.
     */
    public static Map<String, Object> getClientContextMap() {
        final Client reqAgent = parseRequestAgent();
        final Map<String, Object> dataMap = new HashMap<>();

        if (reqAgent != null) {
            dataMap.put("operatingSystem", Objects.isNull(reqAgent.os) ? null : reqAgent.os.family);
            dataMap.put("browserName", Objects.isNull(reqAgent.userAgent) ? null : reqAgent.userAgent.family);
            dataMap.put("device", Objects.isNull(reqAgent.device) ? null : reqAgent.device.family);
            dataMap.put("ipAddress", RequestMetadataProvider.getClientInfo().getIpAddress());
        }

        return dataMap;
    }

    /**
     * Parses the user agent string from the current request to extract client details.
     * <p>
     * Uses the ua-parser library to identify browser, operating system, and device
     * information from the HTTP User-Agent header.
     *
     * @return parsed client information, or null if user agent is unavailable
     */
    private static Client parseRequestAgent() {
        try {
            final String userAgent = RequestMetadataProvider.getClientInfo().getUserAgent();
            if (userAgent == null) {
                LOGGER.debug("User agent not available in request metadata");
                return null;
            }
            final Parser uaParser = new Parser();
            return uaParser.parse(userAgent);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse user agent", e);
            return null;
        }
    }
}
