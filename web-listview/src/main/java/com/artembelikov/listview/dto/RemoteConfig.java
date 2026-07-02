package com.artembelikov.listview.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record RemoteConfig(
        int version,
        List<RemoteSchemaRule> schemas,
        List<RemoteDashboardLink> dashboards) {

    public RemoteConfig {
        schemas = schemas == null ? List.of() : schemas.stream().filter(RemoteConfig::isValid).toList();
        dashboards = dashboards == null ? List.of()
                : dashboards.stream().filter(RemoteConfig::isValid).toList();
    }

    private static boolean isValid(RemoteSchemaRule rule) {
        return rule != null
                && hasText(rule.id())
                && hasText(rule.name())
                && hasText(rule.pattern())
                && hasText(rule.target())
                && rule.schema() != null;
    }

    private static boolean isValid(RemoteDashboardLink link) {
        return link != null
                && hasText(link.id())
                && hasText(link.name())
                && hasText(link.system())
                && hasText(link.scope())
                && hasText(link.urlTemplate());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record RemoteSchemaRule(
            String id,
            String name,
            String pattern,
            String target,
            JsonNode schema) {
    }

    public record RemoteDashboardLink(
            String id,
            String name,
            String system,
            String scope,
            String urlTemplate,
            String match) {
    }
}
