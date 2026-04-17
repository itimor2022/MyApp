import os
import base64
import secrets

aes_key = base64.b64encode(os.urandom(32)).decode()
hmac_key = secrets.token_hex(32)

print("=== 生成结果 ===")
print("AES_KEY_BASE64 =", aes_key)
print("HMAC_KEY =", hmac_key)
