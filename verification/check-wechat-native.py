"""读取参考项目配置，向微信官方查询不存在的订单；不创建订单、不触发扣款。"""
from pathlib import Path
import argparse
import hashlib
import hmac
import json
import re
import ssl
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("D:/xwechat_files/wxid_4edrp1ydd4qw22_b9ef/msg/file/2026-11/qy150-weixin-pay/qy150-weixin-pay/src/main/resources/application.properties")
ENDPOINT = "https://api.mch.weixin.qq.com/pay/orderquery"


def signature(fields, secret):
    values = [f"{name}={fields[name]}" for name in sorted(fields) if name != "sign" and fields[name]]
    return hashlib.md5(("&".join(values) + "&key=" + secret).encode("utf-8")).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default=str(ROOT / ".runtime/wechat-native-check/result.json"))
    parser.add_argument("--native", action="store_true", help="生成不展示的短时Native验证单并尝试关闭；不会扣款")
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if not output.is_relative_to(ROOT / ".runtime"):
        raise SystemExit("检查结果只允许保存到当前项目 .runtime。")
    settings = dict(re.findall(r"^\s*([^#\s=]+)\s*=\s*(.*?)\s*$", SOURCE.read_text(encoding="utf-8-sig"), re.M))
    appid, merchant, secret = (settings.get(name, "") for name in ("weixin.appid", "weixin.mch_id", "weixin.api_key"))
    if not (re.fullmatch(r"wx[a-zA-Z0-9]{16}", appid) and re.fullmatch(r"[0-9]{6,20}", merchant) and len(secret) == 32):
        raise SystemExit("参考商户参数格式不符合预期；未发送请求。")
    fields = {"appid": appid, "mch_id": merchant, "nonce_str": uuid.uuid4().hex,
              "out_trade_no": "AHCHECK" + uuid.uuid4().hex[:24], "sign_type": "MD5"}
    fields["sign"] = signature(fields, secret)
    root = ET.Element("xml")
    for name, value in fields.items():
        ET.SubElement(root, name).text = value
    request = urllib.request.Request(ENDPOINT, data=ET.tostring(root, encoding="utf-8"),
                                     headers={"Content-Type": "text/xml; charset=utf-8", "User-Agent": "AIhealthcare-Native-Credential-Check/1.0"}, method="POST")
    result = {"endpoint": ENDPOINT, "operation": "query_nonexistent_order", "creates_order": False,
              "charges_money": False, "tls_verification": True, "credential_values_saved": False}
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=20, context=ssl.create_default_context()) as response:
            result["http_status"] = response.status
            body = response.read(65537)
        if len(body) > 65536 or b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
            raise ValueError("invalid response format")
        decoded = {child.tag: child.text or "" for child in ET.fromstring(body)}
        for name in ("return_code", "return_msg", "result_code", "err_code", "err_code_des"):
            value = decoded.get(name, "")
            for sensitive in (secret, merchant, appid):
                value = value.replace(sensitive, "[REDACTED]")
            result[name] = value
        supplied = decoded.get("sign", "")
        result["response_signature_present"] = bool(supplied)
        result["response_signature_valid"] = bool(supplied) and hmac.compare_digest(supplied.upper(), signature(decoded, secret))
        result["merchant_matches"] = decoded.get("mch_id") == merchant
        result["appid_matches"] = decoded.get("appid") == appid
        result["credential_check_passed"] = (decoded.get("return_code") == "SUCCESS"
                and result["response_signature_valid"] and result["merchant_matches"] and result["appid_matches"]
                and decoded.get("err_code") == "ORDERNOTEXIST")
    except urllib.error.HTTPError as error:
        result.update(http_status=error.code, error="official endpoint HTTP error", credential_check_passed=False)
    except Exception as error:
        result.update(error_type=type(error).__name__, error="网络、TLS或响应解析检查未通过；未输出异常原文或凭据。", credential_check_passed=False)
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    if args.native and result.get("credential_check_passed"):
        def call(path, data):
            data = {**data, "appid": appid, "mch_id": merchant, "nonce_str": uuid.uuid4().hex, "sign_type": "MD5"}
            data["sign"] = signature(data, secret)
            element = ET.Element("xml")
            for name, value in data.items():
                ET.SubElement(element, name).text = str(value)
            req = urllib.request.Request("https://api.mch.weixin.qq.com" + path,
                    data=ET.tostring(element, encoding="utf-8"), headers={"Content-Type": "text/xml; charset=utf-8"}, method="POST")
            with urllib.request.urlopen(req, timeout=20, context=ssl.create_default_context()) as response:
                body = response.read(65537)
            if len(body) > 65536 or b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
                raise ValueError("invalid response")
            decoded = {child.tag: child.text or "" for child in ET.fromstring(body)}
            clean = {name: decoded.get(name, "") for name in ("return_code", "return_msg", "result_code", "err_code", "err_code_des")}
            clean["response_signature_valid"] = bool(decoded.get("sign")) and hmac.compare_digest(decoded["sign"].upper(), signature(decoded, secret))
            clean["merchant_matches"] = decoded.get("mch_id") == merchant
            clean["appid_matches"] = decoded.get("appid") == appid
            return decoded, clean
        native_no = "AHCHECK" + uuid.uuid4().hex[:24]
        now = datetime.now(timezone(timedelta(hours=8)))
        try:
            native, clean = call("/pay/unifiedorder", {"body": "AIhealthcare教学配置验证", "out_trade_no": native_no,
                    "total_fee": "1", "spbill_create_ip": "127.0.0.1", "trade_type": "NATIVE", "product_id": "AHCHECK01",
                    "notify_url": "http://127.0.0.1:18080/api/payments/wechat/notify",
                    "time_start": now.strftime("%Y%m%d%H%M%S"), "time_expire": (now + timedelta(minutes=5)).strftime("%Y%m%d%H%M%S")})
            clean.update(code_url_received=bool(native.get("code_url")), prepay_identifier_received=bool(native.get("prepay_id")),
                         qr_displayed=False, amount_fen=1, order_number=native_no)
            result["native"] = clean
            result["native_permission_passed"] = (native.get("result_code") == "SUCCESS" and clean["response_signature_valid"]
                    and clean["merchant_matches"] and clean["appid_matches"] and clean["code_url_received"])
            if result["native_permission_passed"]:
                result["creates_order"] = True
                _, close = call("/pay/closeorder", {"out_trade_no": native_no})
                result["close"] = close
        except Exception as error:
            result["native"] = {"error_type": type(error).__name__, "error": "Native验证或关闭请求未完成；未输出敏感响应。"}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
