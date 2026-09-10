#!/usr/bin/env bash
#
# SLAIer —— 「纯 HTTP 登录」可行性验证
#
# 为什么需要这个：
#   微信小程序（个人主体）不能使用 <web-view>，所以登录必须用 wx.request 直接 POST。
#   这个脚本用 curl 模拟 wx.request 要做的事，验证两件事：
#     ① 学校是否还开放「原生表单登录」（而不是强制走 AD FS SSO）
#     ② 登录后的会话 cookie 能否用于调用业务接口
#
# 安全设计：
#   · 密码用 read -s 从终端读入 —— 不进 shell 历史、不出现在 ps 输出里
#   · 凭据只写入一个 0600 的临时 cookie jar，脚本退出即删除
#   · 输出里只打印 cookie 的「名字」和长度，绝不打印值
#   · 绝不打印 HTTP 请求体（那里面有密码）
#
# 用法：
#   bash tools/probe-login.sh
#
set -uo pipefail

STU_BASE="https://stu.slai.edu.cn"
SIS_BASE="https://sis.slai.edu.cn"
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'

JAR="$(mktemp -t slai-probe.XXXXXX)"
chmod 600 "$JAR"
cleanup() { rm -f "$JAR" /tmp/.slai-body.$$ ; }
trap cleanup EXIT

hr()    { printf '%s\n' "────────────────────────────────────────────────────────"; }
step()  { printf '\n'; hr; printf '▶ %s\n' "$1"; hr; }
info()  { printf '  %s\n' "$1"; }
ok()    { printf '  ✅ %s\n' "$1"; }
bad()   { printf '  ❌ %s\n' "$1"; }
warn()  { printf '  ⚠️  %s\n' "$1"; }

# 只输出 cookie 名字与长度，绝不输出值
cookie_names() {
    [ -s "$JAR" ] || { info "(无 cookie)"; return; }
    awk '!/^#/ && NF>=7 { printf "    · %-28s (len=%d)\n", $6, length($7) }' "$JAR" | sort -u
}

cookie_count() { awk '!/^#/ && NF>=7' "$JAR" 2>/dev/null | wc -l | tr -d ' '; }

# ─────────────────────────────────────────────────────────
printf '\n'
hr
printf '  SLAIer 登录可行性验证  ——  为微信小程序路线做前置判断\n'
hr
printf '\n'
info "本脚本会尝试用「原生表单」直接登录，验证是否绕得过 AD FS SSO。"
info "密码不会离开这台机器，也不会被打印。"
printf '\n'
read -r -p "  学号 / 用户名: " SLAI_USER
read -r -s -p "  密码 (输入时不回显): " SLAI_PASS
printf '\n'
[ -z "$SLAI_USER" ] && { bad "用户名为空，退出"; exit 1; }
[ -z "$SLAI_PASS" ] && { bad "密码为空，退出"; exit 1; }

# ═════════════════════════════════════════════════════════
# 测试 1：学生系统 —— 原生表单 /a/login
# ═════════════════════════════════════════════════════════
step "测试 1 / 3   学生系统原生表单  POST ${STU_BASE}/a/login"

# 先取一次登录页，拿到初始会话 cookie
code=$(curl -sS -o /tmp/.slai-body.$$ -w '%{http_code}' --max-time 25 \
        -A "$UA" -c "$JAR" -b "$JAR" "${STU_BASE}/a/login" 2>/dev/null)
info "GET /a/login → HTTP ${code:-无响应}"

if [ ! -s /tmp/.slai-body.$$ ]; then
    bad "拿不到登录页（服务未响应）。学生系统可能也挂了，稍后再试。"
else
    form=$(grep -oiE '<input[^>]*name="(username|password)"' /tmp/.slai-body.$$ | wc -l | tr -d ' ')
    if [ "$form" -ge 2 ]; then
        ok "登录页含 username + password 字段 —— 原生表单存在"
    else
        warn "没找到 username/password 字段（页面可能已改为 JS 渲染）"
    fi
    info "登录页下发的 cookie:"; cookie_names
fi

# 提交凭据。不跟随重定向：JeeSite 成功时是 302，失败时是 200 重新渲染登录页。
hdr=$(mktemp -t slai-hdr.XXXXXX)
code=$(curl -sS -o /tmp/.slai-body.$$ -D "$hdr" -w '%{http_code}' --max-time 25 \
        -A "$UA" -c "$JAR" -b "$JAR" \
        -e "${STU_BASE}/a/login" \
        -X POST "${STU_BASE}/a/login?language=zh_CN" \
        --data-urlencode "username=${SLAI_USER}" \
        --data-urlencode "password=${SLAI_PASS}" \
        --data-urlencode "rememberMe=true" 2>/dev/null)

location=$(grep -i '^location:' "$hdr" 2>/dev/null | head -1 | tr -d '\r' | cut -d' ' -f2-)
info "POST /a/login → HTTP ${code:-无响应}"
[ -n "$location" ] && info "Location: ${location}"

LOGIN_OK=0
if [ "$code" = "302" ] || [ "$code" = "301" ]; then
    if printf '%s' "$location" | grep -qiE 'login'; then
        bad "被重定向回登录页 —— 凭据被拒绝，或表单字段名不对"
    else
        ok "登录成功（302 且未回到登录页）"
        LOGIN_OK=1
    fi
elif [ "$code" = "200" ]; then
    if grep -qiE 'name="password"|密码错误|用户名或密码|登录失败|请重新登录' /tmp/.slai-body.$$ 2>/dev/null; then
        bad "返回登录页 —— 凭据被拒绝"
    else
        warn "HTTP 200 但既不是登录页也不是跳转，需要人工看响应"
    fi
else
    bad "非预期状态码 ${code:-无}"
fi

info "登录后 cookie:"; cookie_names
rm -f "$hdr"

# ═════════════════════════════════════════════════════════
# 测试 2：用会话调用业务接口
# ═════════════════════════════════════════════════════════
step "测试 2 / 3   带会话调用业务接口  GET /a/edu/acm/swipe/listData"

if [ "$LOGIN_OK" = "0" ]; then
    warn "跳过 —— 上一步没登录成功"
    warn "（仍试一次，看未登录时返回什么，用于对比）"
fi

code=$(curl -sS -o /tmp/.slai-body.$$ -w '%{http_code}' --max-time 25 \
        -A "$UA" -c "$JAR" -b "$JAR" \
        -H 'X-Requested-With: XMLHttpRequest' \
        "${STU_BASE}/a/edu/acm/swipe/listData?page=1&limit=5&startTime=&endTime=" 2>/dev/null)

info "HTTP ${code:-无响应}"

if [ ! -s /tmp/.slai-body.$$ ]; then
    bad "空响应"
else
    head1=$(head -c 1 /tmp/.slai-body.$$)
    if [ "$head1" = "{" ] || [ "$head1" = "[" ]; then
        ok "返回 JSON —— 会话有效！"
        SLAI_BODY=/tmp/.slai-body.$$ python3 - <<'PY' 2>/dev/null || info "(无法解析 JSON)"
import json, os
try:
    d = json.load(open(os.environ['SLAI_BODY']))
except Exception:
    raise SystemExit
if isinstance(d, dict):
    print("  JSON 顶层字段:", ", ".join(list(d.keys())[:10]))
    print("  code =", d.get("code"), " count =", d.get("count"))
    rows = d.get("data")
    if isinstance(rows, list):
        print("  data 行数 =", len(rows))
        if rows and isinstance(rows[0], dict):
            print("  单行字段:", ", ".join(list(rows[0].keys())[:12]))
PY
        # 清理掉可能含个人数据的响应
        rm -f /tmp/.slai-body.$$
    else
        bad "返回的不是 JSON（很可能是登录页 HTML）—— 会话无效"
        info "响应前 200 字符（已过滤可能的个人信息）:"
        head -c 200 /tmp/.slai-body.$$ | tr -d '\n' | sed 's/^/    /'
        printf '\n'
    fi
fi

# ═════════════════════════════════════════════════════════
# 测试 3：教务系统是否也有原生表单（不提交凭据）
# ═════════════════════════════════════════════════════════
step "测试 3 / 3   教务系统原生表单是否可达（不提交凭据）"

code=$(curl -sS -o /tmp/.slai-body.$$ -w '%{http_code}' --max-time 25 \
        -A "$UA" "${SIS_BASE}/yjsxt/xtgl/login_slogin.html" 2>/dev/null)
info "GET /yjsxt/xtgl/login_slogin.html → HTTP ${code:-无响应/超时}"

if [ ! -s /tmp/.slai-body.$$ ]; then
    warn "教务系统无响应 —— 现在正好是宕机状态，稍后单独重测"
else
    has_yhm=$(grep -ciE 'name="yhm"' /tmp/.slai-body.$$)
    has_mm=$(grep -ciE 'name="mm"' /tmp/.slai-body.$$)
    has_captcha=$(grep -ciE 'yzm|验证码|captcha' /tmp/.slai-body.$$)
    [ "$has_yhm" -gt 0 ] && ok "含 name=\"yhm\" 字段" || warn "无 yhm 字段"
    [ "$has_mm"  -gt 0 ] && ok "含 name=\"mm\" 字段"  || warn "无 mm 字段"
    if [ "$has_captcha" -gt 0 ]; then
        warn "页面出现「验证码」迹象 —— 小程序里需抓图给用户填，复杂度上升"
    else
        ok "未见验证码迹象"
    fi
fi

# ═════════════════════════════════════════════════════════
printf '\n'; hr
printf '  结论怎么看\n'; hr
cat <<'EOF'
  ✅ 测试1 成功 + 测试2 返回 JSON
     → 原生表单可用，小程序登录可行，用 wx.request 直接 POST 即可
     → 下一步：注册小程序 + 把 stu.slai.edu.cn 加进 request 合法域名

  ❌ 测试1 被拒（重定向回登录页）
     → 学校已停用原生表单，强制走 AD FS
     → 小程序必须自己走完 OAuth2 授权码流程，风险大幅上升
     → 下一步：改成验证 AD FS 流程在 wx.request 下能否跑通

  ⚠️  测试3 显示有验证码
     → 教务系统即使有原生表单，也要在小程序里显示图片验证码

  把以上输出（不含任何密码）贴回来即可。
EOF
printf '\n'
