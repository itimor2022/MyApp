#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
直接在脚本顶部改多个落地页域名，然后一键生成：
1. config.json        -> 上传 OSS / CDN
2. dns_txt_value.txt  -> 填到 DNS TXT
3. plain_config.json  -> OSS 解密前明文，仅供检查
4. dns_payload.json   -> DNS 解密前明文，仅供检查

运行：
    python config.py

查看解密结果：
    python config.py decrypt config.json
    python config.py decrypt dns_txt_value.txt

依赖：
    pip install pycryptodome
"""

import json
import time
import base64
import hashlib
import hmac
import sys
from pathlib import Path
from typing import Dict, Any

from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes


CONFIG = {
    "output_dir": ".",

    # Base64 解码后必须是 16 / 24 / 32 字节
    "aes_key_base64": "DWdqhRaZDT2lUtGh5Um3r/lrGYrlg/JgJYdj6wb8WrY=",

    # HMAC 密钥
    "hmac_key": "2414de5e965ab76ef64cced8877f2634a39f145f626d7e27508ef0b32f43602c",

    # 版本号建议每次配置更新递增
    "version": 5,

    # 下面两个默认自动生成
    "timestamp": int(time.time()),
    "expire_at": int(time.time()) + 7 * 24 * 3600,

    # OSS 放完整落地页列表
    "domains": [
        {"url": "https://btc-web.qg1a.com", "weight": 10},
        {"url": "https://cips-admin.byphsz.cyou", "weight": 6},
    ],

    "feature_x": True,
    "gray_ratio": 20,

    # DNS TXT 推荐两种用法：
    # 1. backup_config_url 不为空：优先走备用 config 地址
    # 2. dns_domains 不为空：TXT 里直接也带一份候选落地页
    "backup_config_url": "",
    "dns_domains": [
        {"url": "https://my.vultr.com/", "weight": 10},
        {"url": "https://blog.51cto.com", "weight": 8}
    ],
    "dns_expire_at": int(time.time()) + 7 * 24 * 3600,
}


BLOCK_SIZE = 16


def b64e(data: bytes) -> str:
    return base64.b64encode(data).decode("utf-8")


def b64d(text: str) -> bytes:
    return base64.b64decode(text.encode("utf-8"))


def json_compact(data: Dict[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def pkcs7_pad(data: bytes) -> bytes:
    pad_len = BLOCK_SIZE - (len(data) % BLOCK_SIZE)
    return data + bytes([pad_len]) * pad_len


def pkcs7_unpad(data: bytes) -> bytes:
    if not data:
        raise ValueError("空数据无法去 padding")
    pad_len = data[-1]
    if pad_len < 1 or pad_len > BLOCK_SIZE:
        raise ValueError("非法 padding")
    if data[-pad_len:] != bytes([pad_len]) * pad_len:
        raise ValueError("padding 校验失败")
    return data[:-pad_len]


def aes_cbc_encrypt(plain_text: str, aes_key_base64: str):
    key = b64d(aes_key_base64)
    if len(key) not in (16, 24, 32):
        raise ValueError("AES Key 长度必须是 16/24/32 字节")

    iv = get_random_bytes(16)
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pkcs7_pad(plain_text.encode("utf-8")))
    return b64e(iv), b64e(encrypted)


def aes_cbc_decrypt(iv_b64: str, data_b64: str, aes_key_base64: str) -> str:
    key = b64d(aes_key_base64)
    if len(key) not in (16, 24, 32):
        raise ValueError("AES Key 长度必须是 16/24/32 字节")

    iv = b64d(iv_b64)
    if len(iv) != 16:
        raise ValueError("AES-CBC IV 长度必须是 16 字节")

    encrypted = b64d(data_b64)
    cipher = AES.new(key, AES.MODE_CBC, iv)
    plain = pkcs7_unpad(cipher.decrypt(encrypted))
    return plain.decode("utf-8")


def make_hmac_sign(data: str, ts: int, hmac_key: str) -> str:
    msg = (data + str(ts)).encode("utf-8")
    digest = hmac.new(hmac_key.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    return digest.lower()


def verify_hmac_sign(data: str, ts: int, sign: str, hmac_key: str) -> bool:
    local = make_hmac_sign(data, ts, hmac_key)
    return hmac.compare_digest(local.lower(), sign.lower())


def build_oss_plain_config(cfg: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "version": cfg["version"],
        "timestamp": cfg["timestamp"],
        "expire_at": cfg["expire_at"],
        "data": {
            "domains": cfg["domains"],
            "feature_x": cfg["feature_x"],
            "gray_ratio": cfg["gray_ratio"]
        }
    }


def build_dns_plain_payload(cfg: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "backup_config_url": cfg["backup_config_url"],
        "domains": cfg["dns_domains"],
        "expire_at": cfg["dns_expire_at"]
    }


def build_envelope(plain_obj: Dict[str, Any], aes_key_base64: str, hmac_key: str) -> Dict[str, Any]:
    plain_text = json_compact(plain_obj)
    ts = int(time.time())
    iv_b64, data_b64 = aes_cbc_encrypt(plain_text, aes_key_base64)
    sign = make_hmac_sign(data_b64, ts, hmac_key)
    return {
        "ts": ts,
        "iv": iv_b64,
        "data": data_b64,
        "sign": sign
    }


def generate_all() -> None:
    out_dir = Path(CONFIG["output_dir"]).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # 每次生成前更新时间，避免老时间戳
    CONFIG["timestamp"] = int(time.time())
    CONFIG["expire_at"] = int(time.time()) + 7 * 24 * 3600
    CONFIG["dns_expire_at"] = int(time.time()) + 7 * 24 * 3600

    aes_key_base64 = CONFIG["aes_key_base64"]
    hmac_key = CONFIG["hmac_key"]

    oss_plain = build_oss_plain_config(CONFIG)
    dns_plain = build_dns_plain_payload(CONFIG)

    oss_envelope = build_envelope(oss_plain, aes_key_base64, hmac_key)
    dns_envelope = build_envelope(dns_plain, aes_key_base64, hmac_key)

    config_json_path = out_dir / "config.json"
    dns_txt_path = out_dir / "dns_txt_value.txt"
    oss_plain_path = out_dir / "plain_config.json"
    dns_plain_path = out_dir / "dns_payload.json"

    # OSS：标准 JSON 对象
    config_json_path.write_text(
        json.dumps(oss_envelope, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )

    # DNS TXT：输出为可直接粘贴到 DNS 面板的字符串格式
    # 结果类似：
    # "{\"ts\":...,\"iv\":\"...\",\"data\":\"...\",\"sign\":\"...\"}"
    dns_txt_path.write_text(
        json.dumps(json_compact(dns_envelope), ensure_ascii=False),
        encoding="utf-8"
    )

    oss_plain_path.write_text(
        json.dumps(oss_plain, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )

    dns_plain_path.write_text(
        json.dumps(dns_plain, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )

    print("生成完成：")
    print(f"  OSS 配置文件: {config_json_path}")
    print(f"  DNS TXT 值  : {dns_txt_path}")
    print(f"  OSS 明文    : {oss_plain_path}")
    print(f"  DNS 明文    : {dns_plain_path}")
    print()
    print("使用说明：")
    print("1. 把 config.json 上传到你的 OSS / CDN")
    print("2. 把 dns_txt_value.txt 里的整行内容原样复制到 DNS TXT")
    print("3. 不要手动删最外层双引号")
    print("4. 不要自己再额外包一层双引号")


def decrypt_file(file_path: str) -> None:
    path = Path(file_path)
    raw = path.read_text(encoding="utf-8").strip()

    # 第一层解析
    envelope = json.loads(raw)

    # 如果第一层解析后还是字符串，说明这是 DNS TXT 的字符串形式
    # 需要再解析一层
    if isinstance(envelope, str):
        envelope = json.loads(envelope)

    if not isinstance(envelope, dict):
        raise ValueError("解析失败：内容不是有效的加密对象")

    for key in ("ts", "iv", "data", "sign"):
        if key not in envelope:
            raise ValueError(f"缺少字段: {key}")

    ok = verify_hmac_sign(
        data=envelope["data"],
        ts=int(envelope["ts"]),
        sign=envelope["sign"],
        hmac_key=CONFIG["hmac_key"]
    )
    print(f"验签结果：{'通过' if ok else '失败'}")

    if not ok:
        return

    plain_text = aes_cbc_decrypt(
        iv_b64=envelope["iv"],
        data_b64=envelope["data"],
        aes_key_base64=CONFIG["aes_key_base64"]
    )

    print("解密结果：")
    print(plain_text)


def main():
    if len(sys.argv) == 1:
        generate_all()
        return

    if len(sys.argv) == 3 and sys.argv[1] == "decrypt":
        decrypt_file(sys.argv[2])
        return

    print("用法：")
    print("  python config.py")
    print("  python config.py decrypt config.json")
    print("  python config.py decrypt dns_txt_value.txt")


if __name__ == "__main__":
    main()
