package com.aaa.aihealthcare.payment;

import com.aaa.aihealthcare.payment.NativeWechatGateway.Closed;
import com.aaa.aihealthcare.payment.NativeWechatGateway.GatewayException;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Order;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Prepay;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Trade;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Native 微信支付 v2 客户端，所有官方响应必须先通过签名与商户校验。 */
public class NativeWechatClient implements NativeWechatGateway {
    private static final String OFFICIAL_BASE_URL = "https://api.mch.weixin.qq.com";
    private static final String UNIFIED_ORDER_PATH = "/pay/unifiedorder";
    private static final String ORDER_QUERY_PATH = "/pay/orderquery";
    private static final String CLOSE_ORDER_PATH = "/pay/closeorder";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter WECHAT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String MD5 = "MD5";
    private static final String HMAC_SHA256 = "HMAC-SHA256";

    private final NativeWechatProperties properties;
    private final Transport transport;
    private final HttpClient httpClient;

    public NativeWechatClient(NativeWechatProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = this::sendOfficialRequest;
        this.httpClient = newHttpClient();
    }

    public NativeWechatClient(NativeWechatProperties properties, Transport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.httpClient = newHttpClient();
    }

    @Override
    public Prepay create(Order order) {
        requireConfigured();
        validateOrder(order);
        Map<String, String> request = baseFields();
        request.put("body", order.description());
        request.put("notify_url", properties.getNotifyUrl());
        request.put("out_trade_no", order.outTradeNo());
        request.put("product_id", order.outTradeNo());
        request.put("spbill_create_ip", "127.0.0.1");
        request.put("time_start", formatTime(Instant.now()));
        request.put("time_expire", formatTime(order.expiresAt()));
        request.put("total_fee", amountText(order.totalFee()));
        request.put("trade_type", "NATIVE");

        Map<String, String> response = postSigned(UNIFIED_ORDER_PATH, request);
        validateResponseEnvelope(response);
        requireBusinessSuccess(response);
        String codeUrl = requiredResponseValue(response, "code_url");
        String prepayId = requiredResponseValue(response, "prepay_id");
        return new Prepay(codeUrl, prepayId);
    }

    @Override
    public Trade query(String outTradeNo) {
        requireConfigured();
        requireText(outTradeNo, "out_trade_no");
        Map<String, String> request = baseFields();
        request.put("out_trade_no", outTradeNo);

        Map<String, String> response = postSigned(ORDER_QUERY_PATH, request);
        validateResponseEnvelope(response);
        if (!isBusinessSuccess(response)) {
            if ("ORDERNOTEXIST".equals(errorCode(response))) {
                return new Trade(outTradeNo, null, null, "NOT_FOUND", null, null);
            }
            throw businessFailure(response);
        }
        requireMerchant(response);
        String returnedOrder = requiredResponseValue(response, "out_trade_no");
        if (!outTradeNo.equals(returnedOrder)) {
            throw invalidResponse("微信支付查单返回的商户订单号不一致");
        }
        String state = requiredResponseValue(response, "trade_state");
        Long totalFee = parseOptionalAmount(response.get("total_fee"));
        if ("SUCCESS".equals(state)) {
            requirePaymentEvidence(response, totalFee);
        }
        return new Trade(
                returnedOrder,
                textOrNull(response.get("transaction_id")),
                totalFee,
                state,
                parseSuccessTime(response.get("time_end")),
                payerOpenid(response)
        );
    }

    @Override
    public Closed close(String outTradeNo) {
        requireConfigured();
        requireText(outTradeNo, "out_trade_no");
        Map<String, String> request = baseFields();
        request.put("out_trade_no", outTradeNo);

        Map<String, String> response = postSigned(CLOSE_ORDER_PATH, request);
        validateResponseEnvelope(response);
        if (isBusinessSuccess(response)) {
            return new Closed("CLOSED");
        }
        return switch (errorCode(response)) {
            case "ORDERPAID" -> new Closed("PAID");
            case "ORDERCLOSED" -> new Closed("CLOSED");
            case "ORDERNOTEXIST" -> new Closed("NOT_FOUND");
            default -> throw businessFailure(response);
        };
    }

    @Override
    public Trade parseNotification(String xml) {
        requireConfigured();
        Map<String, String> response = parseXml(xml);
        validateResponseEnvelope(response);
        requireBusinessSuccess(response);
        String outTradeNo = requiredResponseValue(response, "out_trade_no");
        Long totalFee = parseOptionalAmount(response.get("total_fee"));
        requirePaymentEvidence(response, totalFee);
        String state = textOrNull(response.get("trade_state"));
        if (state == null) {
            state = "SUCCESS";
        }
        if (!"SUCCESS".equals(state)) {
            throw new GatewayException("NOTIFY_STATE_NOT_SUCCESS", "微信支付通知未表示支付成功", false);
        }
        return new Trade(
                outTradeNo,
                requiredResponseValue(response, "transaction_id"),
                totalFee,
                state,
                parseSuccessTime(response.get("time_end")),
                payerOpenid(response)
        );
    }

    /** 测试注入入口；生产构造固定使用 JDK HttpClient 与官方域名。 */
    @FunctionalInterface
    public interface Transport {
        String send(String path, String xml);
    }

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private String sendOfficialRequest(String path, String xml) {
        URI endpoint = URI.create(OFFICIAL_BASE_URL + path);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("User-Agent", "AIhealthcare-Native-Payment/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        return sendWithDeadline(httpClient,request,timeoutSeconds());
    }

    /** 截止时间覆盖响应头和完整响应体；大小限制在订阅读取时生效。 */
    static String sendWithDeadline(HttpClient client,HttpRequest request,int timeoutSeconds) {
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request,info -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(timeoutSeconds,TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GatewayException("HTTP_ERROR", "微信支付接口返回非成功 HTTP 状态", true);
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (GatewayException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new GatewayException("TRANSPORT_TIMEOUT", "微信支付响应超时，结果待确认", true);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayException("INTERRUPTED", "微信支付请求被中断", true);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof GatewayException gatewayError) throw gatewayError;
            throw new GatewayException("TRANSPORT_ERROR", "微信支付网络请求失败", true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk:chunks) {
                if (chunk.remaining()>MAX_BODY_BYTES-bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new GatewayException("RESPONSE_TOO_LARGE","微信支付响应超过允许大小",true)); return;
                }
                byte[] data=new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private Map<String, String> postSigned(String path, Map<String, String> fields) {
        fields.put("sign", sign(fields, properties.getApiKey(), MD5));
        String xml = toXml(fields);
        if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new GatewayException("REQUEST_TOO_LARGE", "微信支付请求超过允许大小", false);
        }
        String responseXml;
        try {
            responseXml = transport.send(path, xml);
        } catch (GatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GatewayException("TRANSPORT_ERROR", "微信支付网络请求失败", true);
        }
        return parseXml(responseXml);
    }

    private Map<String, String> baseFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", properties.getAppid());
        fields.put("mch_id", properties.getMchId());
        fields.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        fields.put("sign_type", MD5);
        return fields;
    }

    private void validateResponseEnvelope(Map<String, String> response) {
        if (!"SUCCESS".equals(response.get("return_code"))) {
            throw new GatewayException("RETURN_CODE_FAIL", "微信支付接口返回失败，结果需查单确认", true);
        }
        verifySignature(response);
        requireMerchant(response);
        if (textOrNull(response.get("result_code")) == null) {
            throw invalidResponse("微信支付响应缺少 result_code");
        }
    }

    private void verifySignature(Map<String, String> response) {
        String supplied = textOrNull(response.get("sign"));
        if (supplied == null) {
            throw invalidResponse("微信支付响应缺少签名");
        }
        String signType = textOrNull(response.get("sign_type"));
        if (signType == null) {
            signType = MD5;
        }
        signType = signType.toUpperCase(Locale.ROOT);
        if (!MD5.equals(signType) && !HMAC_SHA256.equals(signType)) {
            throw new GatewayException("SIGN_TYPE_UNSUPPORTED", "微信支付响应使用了不支持的签名算法", true);
        }
        String expected = sign(response, properties.getApiKey(), signType);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                supplied.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new GatewayException("SIGN_INVALID", "微信支付响应签名无效", true);
        }
    }

    private void requireBusinessSuccess(Map<String, String> response) {
        if (isBusinessSuccess(response)) {
            requireMerchant(response);
            return;
        }
        throw businessFailure(response);
    }

    private boolean isBusinessSuccess(Map<String, String> response) {
        return "SUCCESS".equals(response.get("result_code"));
    }

    private void requireMerchant(Map<String, String> response) {
        if (!Objects.equals(properties.getAppid(), response.get("appid"))
                || !Objects.equals(properties.getMchId(), response.get("mch_id"))) {
            throw new GatewayException("MERCHANT_MISMATCH", "微信支付响应的商户身份不匹配", true);
        }
    }

    private void requirePaymentEvidence(Map<String, String> response, Long totalFee) {
        if (textOrNull(response.get("transaction_id")) == null || totalFee == null) {
            throw invalidResponse("微信支付成功结果缺少交易号或金额");
        }
    }

    private GatewayException businessFailure(Map<String, String> response) {
        String code = errorCode(response);
        if (code == null) {
            code = "RESULT_CODE_FAIL";
        }
        boolean uncertain = java.util.Set.of("SYSTEMERROR", "BANKERROR", "ORDERPAID").contains(code);
        return new GatewayException(code, "微信支付业务处理未成功", uncertain);
    }

    private String errorCode(Map<String, String> response) {
        String code = textOrNull(response.get("err_code"));
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }

    private Long parseOptionalAmount(String value) {
        String normalized = textOrNull(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("[1-9][0-9]*")) {
            throw invalidResponse("微信支付金额不是规范的正整数分");
        }
        try {
            long amount = Long.parseLong(normalized);
            if (amount <= 0) {
                throw invalidResponse("微信支付金额必须为正整数分");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw invalidResponse("微信支付金额不是整数分");
        }
    }

    private Instant parseSuccessTime(String value) {
        String normalized = textOrNull(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("[1-9][0-9]*")) {
            throw invalidResponse("微信支付金额不是规范的正整数分");
        }
        try {
            return LocalDateTime.parse(normalized, WECHAT_TIME).atZone(CHINA_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            throw invalidResponse("微信支付成功时间格式无效");
        }
    }

    private String payerOpenid(Map<String, String> response) {
        String openid = textOrNull(response.get("openid"));
        return openid != null ? openid : textOrNull(response.get("payer_openid"));
    }

    private void validateOrder(Order order) {
        if (order == null) {
            throw invalidRequest("支付订单不能为空");
        }
        requireText(order.outTradeNo(), "out_trade_no");
        requireText(order.description(), "body");
        amountText(order.totalFee());
        if (order.expiresAt() == null) {
            throw invalidRequest("支付订单缺少过期时间");
        }
    }

    private String amountText(long amountFen) {
        if (amountFen <= 0) {
            throw invalidRequest("支付金额必须为正整数分");
        }
        if (amountFen > Integer.MAX_VALUE) {
            throw invalidRequest("支付金额超过微信支付整数分上限");
        }
        return Long.toString(amountFen);
    }

    private void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new GatewayException("DISABLED", "Native 微信支付未启用", false);
        }
        requireText(properties.getAppid(), "appid");
        requireText(properties.getMchId(), "mch_id");
        requireText(properties.getApiKey(), "apiKey");
        requireText(properties.getNotifyUrl(), "notify_url");
    }

    private void requireText(String value, String field) {
        if (textOrNull(value) == null) {
            throw invalidRequest("支付请求字段缺失：" + field);
        }
    }

    private String requiredResponseValue(Map<String, String> response, String field) {
        String value = textOrNull(response.get(field));
        if (value == null) {
            throw invalidResponse("微信支付响应缺少字段：" + field);
        }
        return value;
    }

    private String formatTime(Instant instant) {
        return WECHAT_TIME.format(instant.atZone(CHINA_ZONE));
    }

    private String sign(Map<String, String> fields, String key, String signType) {
        String canonical = fields.entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        String content = canonical + "&key=" + key;
        try {
            if (MD5.equals(signType)) {
                return upperHex(MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8)));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return upperHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new GatewayException("SIGN_COMPUTE_FAILED", "微信支付签名计算失败", false);
        }
    }

    private String upperHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return result.toString();
    }

    private String toXml(Map<String, String> fields) {
        StringBuilder xml = new StringBuilder("<xml>");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            xml.append('<').append(entry.getKey()).append('>')
                    .append(escapeXml(entry.getValue()))
                    .append("</").append(entry.getKey()).append('>');
        }
        return xml.append("</xml>").toString();
    }

    private Map<String, String> parseXml(String xml) {
        String normalized = textOrNull(xml);
        if (normalized == null) {
            throw invalidResponse("微信支付响应为空");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new GatewayException("RESPONSE_TOO_LARGE", "微信支付响应超过允许大小", true);
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw invalidResponse("微信支付响应包含禁止的 XML 声明");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new InputSource(new StringReader(normalized)));
            Element root = document.getDocumentElement();
            if (root == null || !"xml".equals(root.getNodeName())) {
                throw invalidResponse("微信支付响应根节点无效");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            NodeList children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                if (element.getElementsByTagName("*").getLength() > 0) {
                    throw invalidResponse("微信支付响应包含嵌套元素");
                }
                String value = element.getTextContent();
                if (fields.putIfAbsent(element.getNodeName(), value == null ? "" : value) != null) {
                    throw invalidResponse("微信支付响应包含重复字段");
                }
            }
            return fields;
        } catch (GatewayException exception) {
            throw exception;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalidResponse("微信支付响应 XML 无效");
        }
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private GatewayException invalidRequest(String message) {
        return new GatewayException("INVALID_REQUEST", message, false);
    }

    private GatewayException invalidResponse(String message) {
        return new GatewayException("INVALID_RESPONSE", message, true);
    }

    private String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int timeoutSeconds() {
        int configured = properties.getRequestTimeoutSeconds();
        return Math.max(1, Math.min(configured, 60));
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }
}
