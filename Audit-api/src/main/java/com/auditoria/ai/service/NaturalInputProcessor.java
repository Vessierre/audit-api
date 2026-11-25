package com.auditoria.ai.service;

import com.auditoria.ai.dto.SourceRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NaturalInputProcessor {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>()]+", Pattern.CASE_INSENSITIVE);

    public ProcessedNaturalInput process(String entrada) {
        String normalized = entrada.replace("\\\\://", "://");
        Matcher matcher = URL_PATTERN.matcher(normalized);
        List<SourceRequest> sources = new ArrayList<>();
        StringBuffer textWithoutUrls = new StringBuffer();
        int sourceNumber = 1;

        while (matcher.find()) {
            String url = trimTrailingPunctuation(matcher.group());
            if (isHttpUrl(url)) {
                sources.add(new SourceRequest(
                        "Fonte informada no texto natural.",
                        "Fonte informada " + sourceNumber++,
                        url
                ));
                String originalUrlWithPunctuation = url + matcher.group().substring(url.length());
                matcher.appendReplacement(
                        textWithoutUrls,
                        Matcher.quoteReplacement(originalUrlWithPunctuation)
                );
            } else {
                matcher.appendReplacement(textWithoutUrls, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(textWithoutUrls);

        return new ProcessedNaturalInput(textWithoutUrls.toString(), sources);
    }

    private String trimTrailingPunctuation(String url) {
        return url.replaceFirst("[.,;:!?]+$", "");
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    public record ProcessedNaturalInput(String text, List<SourceRequest> sources) {
    }
}
