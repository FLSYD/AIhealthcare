package com.aaa.aihealthcare.payment;

import com.aaa.aihealthcare.payment.NativeWechatGateway.Closed;
import com.aaa.aihealthcare.payment.NativeWechatGateway.GatewayException;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Order;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Prepay;
import com.aaa.aihealthcare.payment.NativeWechatGateway.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeWechatClientTests {
    private static final String APPID = "wx0123456789abcdef";
    private static final String MCH_ID = "1900000001";
    private static final String API_KEY = "0123456789abcdef0123456789abcdef";
    private static final String MD5 = "MD5";
    private static final Pattern ELEMENT = Pattern.compile("<([a-zA-Z0-9_]+)>(.*?)</\\1>", Pattern.DOTALL);

    @Test
    void propertiesUseExpectedDefaultsWithoutPrintingSecrets() {
        NativeWechatProperties properties = new NativeWechatProperties();
        assertFalse(properties.isEnabled());
        assertEquals("http://127.0.0.1:18080/api/payments/wechat/notify", properties.getNotifyUrl());
        assertEquals("http://127.0.0.1:15173", properties.getCashierBaseUrl());
        assertEquals(10, properties.getRequestTimeoutSeconds());

        properties.setApiKey(API_KEY);
        properties.setCashierSigningKey("cashier-secret-value");
        assertFalse(properties.toString().contains(API_KEY));
        assertFalse(properties.toString().contains("cashier-secret-value"));
    }

    @Test
    void createBuildsNativeRequestWithMd5SignatureAndRequiredFields() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedXml = new AtomicReference<>();
        NativeWechatClient client = client((path, xml) -> {
            capturedPath.set(path);
            capturedXml.set(xml);
            return signedResponse("SUCCESS", MD5, map(
                    "code_url", "weixin://wxpay/bizpayurl?pr=test",
                    "prepay_id", "prepay-test"));
        });

        Prepay prepay = client.create(new Order(
                "ORDER-100", 123L, "挂号费 & 服务费", Instant.parse("2030-01-02T03:04:05Z")));

        assertEquals("/pay/unifiedorder", capturedPath.get());
        assertEquals("weixin://wxpay/bizpayurl?pr=test", prepay.codeUrl());
        assertEquals("prepay-test", prepay.prepayId());
        Map<String, String> request = fields(capturedXml.get());
        assertEquals(APPID, request.get("appid"));
        assertEquals(MCH_ID, request.get("mch_id"));
        assertEquals("123", request.get("total_fee"));
        assertEquals("ORDER-100", request.get("product_id"));
        assertEquals("ORDER-100", request.get("out_trade_no"));
        assertEquals("挂号费 & 服务费", request.get("body"));
        assertEquals("NATIVE", request.get("trade_type"));
        assertEquals("20300102110405", request.get("time_expire"));
        assertEquals("http://127.0.0.1:18080/api/payments/wechat/notify", request.get("notify_url"));
        assertEquals(signature(request, MD5), request.get("sign"));
    }

    @Test
    void createRejectsNonPositiveOverflowAndMissingNativeFieldsBeforeTransport() {
        AtomicInteger calls = new AtomicInteger();
        NativeWechatClient client = client((path, xml) -> {
            calls.incrementAndGet();
            return signedResponse("SUCCESS", MD5, map("code_url", "url", "prepay_id", "id"));
        });
        assertGateway("INVALID_REQUEST", false, () -> client.create(
                new Order("ORDER-1", 0L, "挂号费", Instant.now().plusSeconds(60))));
        assertGateway("INVALID_REQUEST", false, () -> client.create(
                new Order("ORDER-2", (long) Integer.MAX_VALUE + 1, "挂号费", Instant.now().plusSeconds(60))));
        assertGateway("INVALID_REQUEST", false, () -> client.create(
                new Order("ORDER-3", 1L, " ", Instant.now().plusSeconds(60))));
        assertGateway("INVALID_REQUEST", false, () -> client.create(
                new Order("ORDER-4", 1L, "挂号费", null)));
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsHmacSha256ResponseButSignsRequestWithMd5() {
        AtomicReference<String> requestXml = new AtomicReference<>();
        NativeWechatClient client = client((path, xml) -> {
            requestXml.set(xml);
            return signedResponse("SUCCESS", "HMAC-SHA256", map(
                    "out_trade_no", "ORDER-HMAC",
                    "transaction_id", "420000001",
                    "trade_state", "SUCCESS",
                    "total_fee", "500",
                    "time_end", "20300102110405",
                    "openid", "openid-test"));
        });

        Trade trade = client.query("ORDER-HMAC");

        assertEquals("SUCCESS", trade.tradeState());
        assertEquals(500L, trade.totalFee());
        assertEquals("420000001", trade.transactionId());
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), trade.successTime());
        Map<String, String> request = fields(requestXml.get());
        assertEquals(signature(request, MD5), request.get("sign"));
    }

    @Test
    void rejectsTamperedAndMissingResponseSignatures() {
        NativeWechatClient tampered = client((path, xml) -> signedResponse("SUCCESS", MD5, map(
                "out_trade_no", "ORDER-T",
                "transaction_id", "420000001",
                "trade_state", "SUCCESS",
                "total_fee", "500")).replace("<total_fee>500</total_fee>", "<total_fee>501</total_fee>"));
        NativeWechatClient unsigned = client((path, xml) -> unsignedResponse("SUCCESS", map(
                "out_trade_no", "ORDER-U",
                "transaction_id", "420000002",
                "trade_state", "SUCCESS",
                "total_fee", "500")));

        assertGateway("SIGN_INVALID", true, () -> tampered.query("ORDER-T"));
        assertGateway("INVALID_RESPONSE", true, () -> unsigned.query("ORDER-U"));
    }

    @Test
    void rejectsMismatchedMerchantAndIncompleteSuccessfulPayment() {
        NativeWechatClient mismatched = client((path, xml) -> {
            Map<String, String> response = baseResponse("SUCCESS", map(
                    "out_trade_no", "ORDER-M",
                    "transaction_id", "420000003",
                    "trade_state", "SUCCESS",
                    "total_fee", "500"));
            response.put("appid", "wxffffffffffffffff");
            return xmlWithSign(response, MD5);
        });
        NativeWechatClient incomplete = client((path, xml) -> signedResponse("SUCCESS", MD5, map(
                "out_trade_no", "ORDER-I",
                "trade_state", "SUCCESS",
                "total_fee", "500")));

        assertGateway("MERCHANT_MISMATCH", true, () -> mismatched.query("ORDER-M"));
        assertGateway("INVALID_RESPONSE", true, () -> incomplete.query("ORDER-I"));
    }

    @Test
    void rejectsDoctypeAndExternalEntityResponses() {
        NativeWechatClient client = client((path, xml) -> {
            throw new AssertionError("parseNotification 不应调用 Transport");
        });
        String response = "<!DOCTYPE xml [<!ENTITY xxe SYSTEM \"file:///tmp/xxe\">]>"
                + "<xml><return_code>SUCCESS</return_code><result_code>SUCCESS</result_code>"
                + "<appid>&xxe;</appid></xml>";

        GatewayException exception = assertGateway("INVALID_RESPONSE", true,
                () -> client.parseNotification(response));

        assertFalse(exception.getMessage().contains("file:///tmp/xxe"));
    }

    @Test
    void queryReturnsNotPayAndNotFoundWithoutInventingPaymentEvidence() {
        NativeWechatClient notPay = client((path, xml) -> signedResponse("SUCCESS", MD5, map(
                "out_trade_no", "ORDER-NOTPAY",
                "trade_state", "NOTPAY",
                "total_fee", "500")));
        NativeWechatClient notFound = client((path, xml) -> signedResponse("FAIL", MD5, map(
                "err_code", "ORDERNOTEXIST",
                "err_code_des", "not found")));

        Trade pending = notPay.query("ORDER-NOTPAY");
        Trade missing = notFound.query("ORDER-MISSING");

        assertEquals("NOTPAY", pending.tradeState());
        assertNull(pending.transactionId());
        assertEquals(500L, pending.totalFee());
        assertEquals("NOT_FOUND", missing.tradeState());
        assertNull(missing.totalFee());
    }

    @Test
    void closeMapsTerminalRaceStates() {
        AtomicInteger calls = new AtomicInteger();
        NativeWechatClient client = client((path, xml) -> {
            String code = switch (calls.getAndIncrement()) {
                case 0 -> "ORDERPAID";
                case 1 -> "ORDERCLOSED";
                default -> "ORDERNOTEXIST";
            };
            return signedResponse("FAIL", MD5, map("err_code", code));
        });

        Closed paid = client.close("ORDER-1");
        Closed closed = client.close("ORDER-2");
        Closed missing = client.close("ORDER-3");

        assertEquals("PAID", paid.state());
        assertEquals("CLOSED", closed.state());
        assertEquals("NOT_FOUND", missing.state());
        assertEquals(3, calls.get());
    }

    @Test
    void notificationRequiresValidSignatureMerchantAndPaymentEvidence() {
        NativeWechatClient client = client((path, xml) -> {
            throw new AssertionError("parseNotification 不应调用 Transport");
        });
        String response = signedResponse("SUCCESS", MD5, map(
                "out_trade_no", "ORDER-N",
                "transaction_id", "420000009",
                "total_fee", "888",
                "time_end", "20300102110405",
                "openid", "openid-notify"));

        Trade trade = client.parseNotification(response);

        assertEquals("ORDER-N", trade.outTradeNo());
        assertEquals("420000009", trade.transactionId());
        assertEquals(888L, trade.totalFee());
        assertEquals("SUCCESS", trade.tradeState());
        assertEquals("openid-notify", trade.payerOpenid());
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), trade.successTime());
        assertGateway("INVALID_RESPONSE", true, () -> client.parseNotification(
                signedResponse("SUCCESS", MD5, map(
                        "out_trade_no", "ORDER-BAD",
                        "trade_state", "SUCCESS",
                        "total_fee", "1"))));
    }

    @Test
    void returnCodeFailWithoutSignatureIsRejected() {
        String response = "<xml><return_code>FAIL</return_code><return_msg>签名失败</return_msg></xml>";
        NativeWechatClient client = client((path, xml) -> response);

        assertGateway("RETURN_CODE_FAIL", true, () -> client.query("ORDER-FAIL"));
    }

    @Test
    void transportFailureIsReportedAsUncertainWithoutLeakingTransportMessage() {
        NativeWechatClient client = client((path, xml) -> {
            throw new IllegalStateException("secret request body");
        });

        GatewayException exception = assertGateway("TRANSPORT_ERROR", true,
                () -> client.query("ORDER-TRANSPORT"));

        assertFalse(exception.getMessage().contains("secret request body"));
    }

    @Test
    void closeRejectsSignedResponseFromOtherMerchant() {
        NativeWechatClient client=client((path,xml)->signedResponse("SUCCESS",MD5,map("mch_id","1234567890")));
        assertGateway("MERCHANT_MISMATCH",true,()->client.close("ORDER-OTHER-MERCHANT"));
    }

    @Test
    void unifiedOrderSystemErrorRemainsUncertain() {
        NativeWechatClient client=client((path,xml)->signedResponse("FAIL",MD5,map("err_code","SYSTEMERROR")));
        assertGateway("SYSTEMERROR",true,()->client.create(new Order("ORDER-SYSTEM-ERROR",1,"Synthetic",Instant.now().plusSeconds(300))));
    }

    @Test
    void responseBodyDeadlineIncludesSlowBodyAfterHeaders() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow",exchange->{
            try { exchange.sendResponseHeaders(200,100); exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
                release.await(5,java.util.concurrent.TimeUnit.SECONDS);
            } catch(Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        long started=System.nanoTime();
        try {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow")).build();
            assertGateway("TRANSPORT_TIMEOUT",true,()->NativeWechatClient.sendWithDeadline(java.net.http.HttpClient.newHttpClient(),request,1));
            org.junit.jupiter.api.Assertions.assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<3500,"响应体读取须有截止时间");
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void streamingBodyLimitCancelsOversizedResponse() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/large",exchange->{
            try { byte[] data=new byte[70*1024]; exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/large")).build();
            assertGateway("RESPONSE_TOO_LARGE",true,()->NativeWechatClient.sendWithDeadline(java.net.http.HttpClient.newHttpClient(),request,2));
        } finally { server.stop(0); }
    }

    private NativeWechatClient client(NativeWechatClient.Transport transport) {
        NativeWechatProperties properties = new NativeWechatProperties();
        properties.setEnabled(true);
        properties.setAppid(APPID);
        properties.setMchId(MCH_ID);
        properties.setApiKey(API_KEY);
        return new NativeWechatClient(properties, transport);
    }

    private static GatewayException assertGateway(String code, boolean uncertain, Executable executable) {
        GatewayException exception = assertThrows(GatewayException.class, executable);
        assertEquals(code, exception.getCode());
        assertEquals(uncertain, exception.isUncertain());
        return exception;
    }

    private static String signedResponse(String resultCode, String signType, Map<String, String> values) {
        return xmlWithSign(baseResponse(resultCode, values), signType);
    }

    private static String unsignedResponse(String resultCode, Map<String, String> values) {
        return toXml(baseResponse(resultCode, values));
    }

    private static Map<String, String> baseResponse(String resultCode, Map<String, String> values) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_code", "SUCCESS");
        fields.put("result_code", resultCode);
        fields.put("appid", APPID);
        fields.put("mch_id", MCH_ID);
        fields.putAll(values);
        return fields;
    }

    private static String xmlWithSign(Map<String, String> values, String signType) {
        Map<String, String> fields = new LinkedHashMap<>(values);
        if (signType != null) {
            fields.put("sign_type", signType);
        }
        fields.put("sign", signature(fields, signType == null ? MD5 : signType));
        return toXml(fields);
    }

    private static Map<String, String> fields(String xml) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = ELEMENT.matcher(xml.substring("<xml>".length(), xml.lastIndexOf("</xml>")));
        while (matcher.find()) {
            fields.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return fields;
    }

    private static String signature(Map<String, String> fields, String signType) {
        String canonical = fields.entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&")) + "&key=" + API_KEY;
        try {
            byte[] digest;
            if (MD5.equals(signType)) {
                digest = MessageDigest.getInstance("MD5").digest(canonical.getBytes(StandardCharsets.UTF_8));
            } else {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(API_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String toXml(Map<String, String> fields) {
        StringBuilder xml = new StringBuilder("<xml>");
        fields.forEach((key, value) -> xml.append('<').append(key).append('>')
                .append(escape(value)).append("</").append(key).append('>'));
        return xml.append("</xml>").toString();
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String unescape(String value) {
        return value.replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&");
    }
}
