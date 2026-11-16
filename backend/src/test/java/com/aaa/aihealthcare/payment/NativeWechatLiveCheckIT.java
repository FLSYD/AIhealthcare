package com.aaa.aihealthcare.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 人工显式启用：生产Java客户端生成不展示二维码的1分验证单并立即关闭，绝不调用付款。 */
@EnabledIfSystemProperty(named="healthcare.native.live-check",matches="true")
class NativeWechatLiveCheckIT {
    @Test void productionClientCreatesQueriesAndClosesWithoutCharging() throws Exception {
        Properties input=new Properties();
        try(var stream=Files.newInputStream(Path.of("../.local/wechat-pay.properties"))) { input.load(stream); }
        NativeWechatProperties p=new NativeWechatProperties(); p.setEnabled(true);
        String prefix="healthcare.payment.wechat-native.";
        p.setAppid(input.getProperty(prefix+"appid")); p.setMchId(input.getProperty(prefix+"mch-id"));
        p.setApiKey(input.getProperty(prefix+"api-key")); p.setNotifyUrl(input.getProperty(prefix+"notify-url"));
        NativeWechatClient client=new NativeWechatClient(p);
        String orderNo="AHJAVA"+UUID.randomUUID().toString().replace("-","").substring(0,25);
        boolean created=false;
        try {
            var prepay=client.create(new NativeWechatGateway.Order(orderNo,1,"AIhealthcare教学通道验证",Instant.now().plusSeconds(300)));
            created=true;
            assertNotNull(prepay.prepayId()); assertTrue(prepay.codeUrl().startsWith("weixin://"));
            assertEquals("NOTPAY",client.query(orderNo).tradeState());
        } finally {
            if(created) assertEquals("CLOSED",client.close(orderNo).state(),"验证单须关闭");
        }
        System.out.println("NATIVE_LIVE_EVIDENCE javaClient=true,signedPrepay=true,signedQuery=NOTPAY,signedClose=CLOSED,qrDisplayed=false,charged=false,amountFen=1");
    }
}
