package com.wireglass.listview.dto;

public record RunRequest(
        String url,
        String method,
        String body,
        String contentType,
        int threads,
        int iterations) {

    public RunRequest {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (threads < 1) {
            threads = 1;
        }
        if (iterations < 1) {
            iterations = 1;
        }
    }
}
