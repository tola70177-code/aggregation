package com.github.botaggregation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class UrlCleanerService {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "ref", "affiliate", "aff",
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
            "tag", "linkcode", "linkid",
            "fbclid", "gclid", "dclid", "msclkid",
            "mc_cid", "mc_eid"
    );

    public String clean(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }

        try {
            URI uri = URI.create(url.trim());
            String query = uri.getRawQuery();

            if (query == null || query.isEmpty()) {
                return url.trim();
            }

            Map<String, String> cleanParams = new LinkedHashMap<>();
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8).toLowerCase();
                if (!TRACKING_PARAMS.contains(key)) {
                    cleanParams.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }

            StringBuilder cleaned = new StringBuilder();
            cleaned.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                cleaned.append(":").append(uri.getPort());
            }
            cleaned.append(uri.getRawPath());

            if (!cleanParams.isEmpty()) {
                cleaned.append("?");
                var entries = cleanParams.entrySet().iterator();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    cleaned.append(entry.getKey());
                    if (!entry.getValue().isEmpty()) {
                        cleaned.append("=").append(entry.getValue());
                    }
                    if (entries.hasNext()) {
                        cleaned.append("&");
                    }
                }
            }

            return cleaned.toString();
        } catch (Exception e) {
            log.warn("[URL-CLEANER] Failed to parse URL: {}", url, e);
            return url.trim();
        }
    }
}
