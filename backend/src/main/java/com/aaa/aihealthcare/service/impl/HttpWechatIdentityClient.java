package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.service.WechatIdentityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class HttpWechatIdentityClient implements WechatIdentityClient {
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String appId;
    private final String appSecret;

    public HttpWechatIdentityClient(
            ObjectMapper objectMapper,
            @Value("${healthcare.wechat.app-id:${WECHAT_APP_ID:}}") String appId,
            @Value("${healthcare.wechat.app-secret:${WECHAT_APP_SECRET:}}") String appSecret) {
        this.objectMapper = objectMapper;
        this.appId = appId;
        this.appSecret = appSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String exchangeCode(String code) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("微信登录未配置，请联系管理员");
        }
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new IllegalArgumentException("微信登录凭证不能为空且不能超过512个字符");
        }

        URI requestUri = URI.create(ENDPOINT
                + "?appid=" + encode(appId)
                + "&secret=" + encode(appSecret)
                + "&js_code=" + encode(code)
                + "&grant_type=authorization_code");
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("微信登录服务暂不可用，请稍后重试");
        } catch (IOException exception) {
            throw new IllegalStateException("微信登录服务暂不可用，请稍后重试");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("微信登录服务暂不可用，请稍后重试");
        }

        JsonNode result;
        try {
            result = objectMapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("微信登录服务返回异常，请稍后重试");
        }
        JsonNode errorCode = result.get("errcode");
        if (errorCode != null && !errorCode.isNull() && errorCode.asInt() != 0) {
            throw wechatError(errorCode.asInt());
        }
        JsonNode openidNode = result.get("openid");
        if (openidNode == null || !openidNode.isTextual()) {
            throw new IllegalStateException("微信登录服务未返回有效身份，请重试");
        }
        String openid = openidNode.asText();
        if (openid.isBlank() || openid.length() > 64 || !openid.equals(openid.strip())) {
            throw new IllegalStateException("微信登录服务未返回有效身份，请重试");
        }
        return openid;
    }

    private IllegalStateException wechatError(int errorCode) {
        if (errorCode == 40029 || errorCode == 40163 || errorCode == 41008) {
            return new IllegalStateException("微信登录凭证无效或已过期，请重新登录");
        }
        return new IllegalStateException("微信登录服务返回错误（errcode=" + errorCode + "）");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
