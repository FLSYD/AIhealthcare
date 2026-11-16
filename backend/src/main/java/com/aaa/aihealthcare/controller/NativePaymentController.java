package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.AnonymousAccess;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.payment.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

@RestController
@RequireRoles("USER")
public class NativePaymentController {
    private final ObjectProvider<NativePaymentService> services;
    private final NativeWechatProperties properties;
    public NativePaymentController(ObjectProvider<NativePaymentService> services, NativeWechatProperties properties) { this.services=services; this.properties=properties; }
    private NativePaymentService service() {
        NativePaymentService value=services.getIfAvailable();
        if (value==null) throw new IllegalArgumentException("微信扫码支付尚未启用");
        return value;
    }
    @GetMapping("/api/payments/wechat/native/config")
    public Result config() { return Result.ok(Map.of("enabled",properties.isEnabled(),"mode",properties.isEnabled()?"NATIVE":"MOCK")); }

    @PostMapping("/api/payments/wechat/native")
    public Result create(@RequestAttribute("loginUserId") Long userId, @RequestBody NativePaymentService.CreateRequest request,
                         @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return Result.ok(service().create(userId,request,key));
    }
    @GetMapping("/api/payments/wechat/native/{id}")
    public Result status(@RequestAttribute("loginUserId") Long userId,@PathVariable Long id) { return Result.ok(service().ownerView(userId,id,true)); }

    @PostMapping("/api/payments/wechat/native/{id}/close")
    public Result close(@RequestAttribute("loginUserId") Long userId,@PathVariable Long id) { return Result.ok(service().close(userId,id)); }

    @AnonymousAccess
    @GetMapping("/api/payments/wechat/native/cashier/{token}")
    public Result cashier(@PathVariable String token) { return Result.ok(service().cashierView(token)); }

    @AnonymousAccess
    @GetMapping(value="/api/payments/wechat/native/cashier/{token}/qr",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String token) throws Exception {
        String data=service().cashierCode(token);
        var matrix=new QRCodeWriter().encode(data,BarcodeFormat.QR_CODE,320,320,Map.of(EncodeHintType.CHARACTER_SET,"UTF-8"));
        BufferedImage image=new BufferedImage(320,320,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<320;y++) for(int x=0;x<320;x++) image.setRGB(x,y,matrix.get(x,y)?0x000000:0xFFFFFF);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bytes.toByteArray());
    }

    @AnonymousAccess
    @PostMapping(value="/api/payments/wechat/notify",produces=MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> notifyPayment(@RequestBody String xml) {
        try { service().acceptNotification(xml); return ResponseEntity.ok("<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>"); }
        catch (RuntimeException error) { return ResponseEntity.badRequest().body("<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[unverified]]></return_msg></xml>"); }
    }
}
