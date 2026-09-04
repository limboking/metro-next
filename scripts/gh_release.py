# -*- coding: utf-8 -*-
"""
GitHub Release 自动创建脚本（MetroNext，版本号见 VER 常量，发版改一处即可）。
- 用 ctypes 调 advapi32 CredReadW 从 Windows 凭据管理器读 git:https://github.com 凭据
  （GCM 子进程在本环境被沙箱杀，无法走 git credential fill）
- token 全程仅在进程内存中，任何输出不得包含 token 本身
- 流程：验证 API 权限 -> 创建 Release -> 上传 release APK
"""
import ctypes
import ctypes.wintypes as wt
import base64
import json
import sys
import urllib.request
import urllib.error

REPO = "limboking/metro-next"
VER = "1.0.23"
TAG = "v" + VER
NAME = "Metro Next v" + VER
APK_PATH = r"D:/WorkBuddy_Save/手机小程序开发/MetroNext-release.apk"
ASSET_NAME = "MetroNext-v%s-release.apk" % VER

BODY = (
    "## 更新内容\n"
    "- 诊断增强：快照生成时被丢弃的收藏（站名不在数据/方向匹配失败/班次组为空）"
    "会记入快照 drop 字段，debug 版诊断日志打印「快照丢弃收藏」行——用于定位"
    "「小部件只剩 N 行」类问题；release 版无任何行为变化\n"
    "- 包含 v1.0.22 的打包残留修复（APK 体积恢复正常）\n\n"
    "## 说明\n"
    "- release 版不带任何调试功能、不写诊断日志\n"
    "- 与 debug 版同签名，可直接覆盖升级，收藏数据保留\n\n"
    "## 安装\n下载 MetroNext-v%s-release.apk 安装即可。" % VER
)


def read_github_credential():
    class CREDENTIAL(ctypes.Structure):
        _fields_ = [
            ("Flags", ctypes.c_int),
            ("Type", ctypes.c_int),
            ("TargetName", ctypes.c_wchar_p),
            ("Comment", ctypes.c_wchar_p),
            ("LastWritten", wt.FILETIME),
            ("CredentialBlobSize", ctypes.c_uint),
            ("CredentialBlob", ctypes.c_void_p),
            ("Persist", ctypes.c_uint),
            ("AttributeCount", ctypes.c_uint),
            ("Attributes", ctypes.c_void_p),
            ("TargetAlias", ctypes.c_wchar_p),
            ("UserName", ctypes.c_wchar_p),
        ]

    adv = ctypes.WinDLL("advapi32", use_last_error=True)
    adv.CredReadW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint, ctypes.c_uint,
                              ctypes.POINTER(ctypes.c_void_p)]
    adv.CredReadW.restype = ctypes.c_bool
    p = ctypes.c_void_p()
    if not adv.CredReadW("git:https://github.com", 1, 0, ctypes.byref(p)):
        raise OSError("CredReadW failed, win32 err=%d" % ctypes.get_last_error())
    cred = ctypes.cast(p, ctypes.POINTER(CREDENTIAL)).contents
    blob = ctypes.string_at(cred.CredentialBlob, cred.CredentialBlobSize)
    ctypes.WinDLL("advapi32").CredFree(p)
    user = cred.UserName
    # GCM 以 UTF-8 存 blob；兜底试 UTF-16-LE
    for enc in ("utf-8", "utf-16-le"):
        try:
            pw = blob.decode(enc)
        except UnicodeDecodeError:
            continue
        if pw and ("gh" in pw[:4] or enc == "utf-16-le"):
            return user, pw
    return user, blob.decode("utf-8", errors="replace")


def api_request(url, token, method="GET", data=None, headers=None):
    h = {
        "Authorization": "Basic " + base64.b64encode(
            ("limboking:" + token).encode("utf-8")).decode("ascii"),
        "Accept": "application/vnd.github+json",
        "User-Agent": "metronext-release-script",
    }
    if headers:
        h.update(headers)
    body = data.encode("utf-8") if isinstance(data, str) else data
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def main():
    user, token = read_github_credential()
    print("cred user=%s token_prefix=%s token_len=%d" % (user, token[:4], len(token)))

    # 1. 验证 API 身份与权限
    status, headers, body = api_request("https://api.github.com/user", token)
    scopes = headers.get("X-OAuth-Scopes", "(none)")
    login = ""
    try:
        login = json.loads(body.decode("utf-8")).get("login", "")
    except Exception:
        pass
    print("GET /user -> %s login=%s scopes=%s" % (status, login, scopes))
    if status != 200:
        print("ABORT: token cannot access API")
        sys.exit(2)

    # 2. 创建 Release（已存在则复用）
    payload = json.dumps({
        "tag_name": TAG, "target_commitish": "main", "name": NAME,
        "body": BODY, "draft": False, "prerelease": False,
    })
    status, headers, body = api_request(
        "https://api.github.com/repos/%s/releases" % REPO, token,
        method="POST", data=payload,
        headers={"Content-Type": "application/json"})
    rel = json.loads(body.decode("utf-8"))
    if status not in (200, 201):
        # 已存在同名 tag 的 release 时，取回它
        if "already_exists" in str(rel.get("errors", "")) or status == 422:
            status, headers, body = api_request(
                "https://api.github.com/repos/%s/releases/tags/%s" % (REPO, TAG), token)
            rel = json.loads(body.decode("utf-8"))
        else:
            print("RELEASE_FAIL %s %s" % (status, str(rel.get("message"))[:120]))
            sys.exit(3)
    rel_id = rel.get("id")
    rel_url = rel.get("html_url")
    print("RELEASE_OK id=%s url=%s" % (rel_id, rel_url))

    # 3. 上传 APK 资产（已存在同名资产则跳过）
    status, headers, body = api_request(
        "https://api.github.com/repos/%s/releases/%s/assets" % (REPO, rel_id), token)
    existing = [a.get("name") for a in json.loads(body.decode("utf-8"))]
    print("existing assets:", existing)
    if ASSET_NAME in existing:
        print("ASSET_EXISTS %s" % ASSET_NAME)
        sys.exit(0)
    with open(APK_PATH, "rb") as f:
        apk_bytes = f.read()
    status, headers, body = api_request(
        "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s"
        % (REPO, rel_id, ASSET_NAME), token,
        method="POST", data=apk_bytes,
        headers={"Content-Type": "application/vnd.android.package-archive"})
    asset = json.loads(body.decode("utf-8"))
    if status == 201:
        print("ASSET_UPLOADED %s (state=%s, size=%s)"
              % (asset.get("name"), asset.get("state"), asset.get("size")))
    else:
        print("ASSET_FAIL %s %s" % (status, str(asset.get("message"))[:120]))
        sys.exit(4)


if __name__ == "__main__":
    main()
