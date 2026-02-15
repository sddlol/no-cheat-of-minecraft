import datetime
import re
import time
import requests
import warnings
warnings.filterwarnings("ignore")
from loguru import logger
import os
from datetime import datetime
import os
import re
import csv
import threading
import requests
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
from loguru import logger
# ==========================================
# 1. 全局初始化与目录创建 (必须放在最前面)
# ==========================================
file_lock = threading.Lock()    
file_write_lock = threading.Lock()  
def login(phone_number, pwd):
    

    session = requests.session()


    cookies = {
        'MicrosoftApplicationsTelemetryDeviceId': '576ace58-ed48-47ec-afad-2bf81d7fe66d',
        'MSFPC': 'GUID=a56e68256d4046f099f809df875f511a&HASH=a56e&LV=202404&V=4&LU=1713579079370',
        'mkt': 'zh-CN',
        'IgnoreCAW': '1',
        'MUID': '05D76AB7594E650B2D747ED058BC6445',
        'SDIDC': 'CjptyXmqYbhGQe!nLJP6uBC5QuVJG9cNJrMOVVmLWHp1PMRTXi1hPqbQUNbucC0*hpsXhME5FVt401ZDv1rvI*fWoF85qAexpcc9D4Yoi2yL7B1uoGMNr4sREXt36HdKzyVeM!QZv7nhVKaJre!EnsH8bmAxBxrYhMxtbvz!z9zKXSJwuv0hTqS7uaEqb4LxtzadLWU*s*PpsruNw0McRVuvVwCvFLcxnNpMyk14K0*xW7s72rVq7yrP7J5SGWOthORPCZzxb2XL1YG6KrkBBC9H8LuoGVSyhYToH7uXERd!ckezFHJxql32ENlJYerLbMZ!asQ3XH*uxGA3TFl6!CI4RINms9tJvDGBler29hxJHeEkhNWA0NJEMBBWlF288kv1rRUGPuKRdpuwb1Qz1S0pUq2hTr1EVZ1Or6MZQM5kEcwFpY261pL1qiBdVQsPouvrT1eHhjM!2CCY0qwa2ZALIhosIyfepum8d!lBohQ1ANOJTng!U5!shQ7Ib16a2SPsUz9KvEdWe!Zvgthvbi4$',
        'uaid': 'fc6b7450f145408fb5ec025d4dd0cecd',
        'MSPRequ': 'id=74335&lt=1714743335&co=0',
        'MSCC': '120.231.160.55-CN',
        'OParams': '11O.DiKJKWqhZFHPpelmp1yh20ee!ltshDnBupEcvybmOXtwu2c3Z7bkEDHAyx4Jrozp!8PP2Gy9Y9yiRPoZi965tA*xV9izmTV*nbZCl98G2hHvxwfLC0r6Mv!*tZBw9Gu59keSq*TjHde4WRIdECeyhtQiDW9lR4WjUzHFCFd05Iv4kgeDgcGPAmClyzJI!wgLsOB65qCPrgDTftrpTOZHZc1J3g6pUBEMVsBN3!ZCqm3rLaB!WiOFOA9eL*!8AShd4b5t1WMBHBDOjr0Hk55LjPh1!g4fSA9atxFyERWZm08q87I3O!wcwnBENOj8EVI0mR8EguM0heaDESXmDmpLN*Y9ly0WpKcoBLk1ezBR2cxr9OCZwcBP5UGa*ttn0yUm07j3UHRS*CGck!028Kmalob9TvParxSzTqdu7BeWbq8wd8AchTMKqnUCw7Vq27GQ13!GUsyeT2VHIbQlfTYk1E5DWGm!eqYrb8G8PHGjG!utyl4jwPFBH53jOileUJEDP7kQN2jKJL1!KcBDXidkYmqlmwn2vKW7b*5rmnLsMrJXYnzmN3HEr8k5Ku!qYV5KRQ$$',
        'ai_session': '0MRB66TIaI0KVCKoNXou8Z|1714743337384|1714743337384',
        'MSPOK': '$uuid-665b76cc-885d-443b-8ffb-9ab86f8626db$uuid-ad387ebd-001b-47c0-8693-7555a1e63327',
    }

    headers = {
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        'Cache-Control': 'max-age=0',
        'Connection': 'keep-alive',
        'Content-Type': 'application/x-www-form-urlencoded',
        'Origin': 'https://login.live.com',
        'Referer': 'https://login.live.com/login.srf?wa=wsignin1.0&rpsnv=151&ct=1714743335&rver=7.3.6960.0&wp=MBI_SSL&wreply=https%3a%2f%2fwww.microsoft.com%2frpsauth%2fv1%2faccount%2fSignInCallback%3fstate%3deyJSdSI6Imh0dHBzOi8vd3d3Lm1pY3Jvc29mdC5jb20vemgtY24vIiwiTGMiOiIyMDUyIiwiSG9zdCI6Ind3dy5taWNyb3NvZnQuY29tIn0&lc=2052&id=74335&aadredir=0',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'same-origin',
        'Sec-Fetch-User': '?1',
        'Upgrade-Insecure-Requests': '1',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'sec-ch-ua': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
        'sec-ch-ua-mobile': '?0',
        'sec-ch-ua-platform': '"Windows"',
    }

    params = {
        'id': '74335',
        'contextid': '234014DB41B61525',
        'opid': '1956E33996A4C9C4',
        'bk': '1714743335',
        'uaid': 'fc6b7450f145408fb5ec025d4dd0cecd',
        'pid': '0',
    }

    formatted_number = phone_number[:3] + "+" + phone_number[3:7] + "+" + phone_number[7:]

    data = f'ps=2&psRNGCDefaultType=&psRNGCEntropy=&psRNGCSLK=&canary=&ctx=&hpgrequestid=&PPFT=-DqrJf42zHrBGKJDrPFYIVqLmGmWJW7fcEZw*qF2uHRLgKfvHI4kF942GFl6AYz2MHhcwhEzUBsvd2SoHnRvfJFO0daLHDg5VKkr6sj*zFQX24i1Kq2CB2ikUrZvWVrA862xff8C1Zj7BN59FIyODz5GEIVk1gtmgAxeKF17q%21bFnBhwufbUqGhdFbPLLW8A8bMRrFweSUeViPum2S6DZhVo%24&PPSX=PassportRN&NewUser=1&FoundMSAs=&fspost=0&i21=0&CookieDisclosure=0&IsFidoSupported=1&isSignupPost=0&isRecoveryAttemptPost=0&i13=0&login={phone_number}&loginfmt=%2B86+{formatted_number}&type=11&LoginOptions=3&lrt=&lrtPartition=&hisRegion=&hisScaleUnit=&passwd={pwd}'

    response = session.get(
        'https://login.live.com/login.srf',
        cookies=cookies,
        headers=headers,
        verify=False
    )

    response = session.post(
        'https://login.live.com/ppsecure/post.srf',
        params=params,
        cookies=cookies,
        headers=headers,
        data=data,
        verify=False
    ).text

    if response == "Too Many Requests":
        print("垃圾代理")
        return 2

    pattern = r'CN~中国~86'
    match = re.search(pattern, response)
    if match:
        print("账号不存在或者密码错误：" + phone_number)
        return 1
    else:
        pattern = r'登录需要启用'
        match = re.search(pattern, response)
        if match:
            pass

    id_match = re.search(r'id=(\d+)', response)
    uaid_match = re.search(r'uaid=([a-f0-9]+)', response)
    pid_match = re.search(r'pid=(\d+)', response)
    opid_match = re.search(r'opid=([A-Z0-9]+)', response)
    route_match = re.search(r'route=([A-Z0-9_]+)', response)

    id_value = id_match.group(1) if id_match else ''
    uaid_value = uaid_match.group(1) if uaid_match else ''
    pid_value = pid_match.group(1) if pid_match else ''
    opid_value = opid_match.group(1) if opid_match else ''
    route_value = route_match.group(1) if route_match else ''

    params = {
        'id': id_value,
        'uaid': uaid_value,
        'pid': pid_value,
        'opid': opid_value,
        'route': route_value
    }

    response = requests.post("https://login.live.com/ppsecure/post.srf", data=params)

    logger.info('拿到cookie')

    return session.cookies
import requests
def try_account_with_live_cookie(session, phone_number):
    """
    穩健獲取授權：如果有 t 則提交跳轉；如果沒有 t 但 Cookie 已到位，則直接返回
    """
    logger.debug(f"[{phone_number}] 開始觸碰 Account 域")
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer": "https://login.live.com/",
    }

    try:
        # ① 請求 Account 主頁
        resp = session.get("https://account.microsoft.com/", headers=headers, timeout=20)
        
        # ② 提取跳轉參數
        params = {}
        for f in ['pprid', 'NAP', 'ANON', 't']:
            match = re.search(rf'name="{f}"\s+id="{f}"\s+value="([^"]+)"', resp.text)
            params[f] = match.group(1) if match else ""

        # ③ 【核心優化】判斷是否需要提交跳轉
        if not params.get('t'):
            # 如果沒有 t，檢查是否有 Account 域的關鍵 Cookie
            cookie_names = [c.name for c in session.cookies]
            if "AMC-MS-CV" in cookie_names or "MSCC" in cookie_names:
                logger.info(f"[{phone_number}] 檢測到已有 Account 授權 Cookie，跳過跳轉步驟")
                return True
            else:
                logger.warning(f"[{phone_number}] 既無跳轉參數也無授權 Cookie，可能需要驗證")
                return False

        # ④ 如果有 t，則正常執行最後的授權提交
        action_match = re.search(r'action="([^"]+)"', resp.text)
        if action_match:
            action_url = action_match.group(1)
            logger.info(f"[{phone_number}] 提交跳轉表單中...")
            session.post(action_url, data=params, headers=headers, timeout=20)
            return True
        
        return False

    except Exception as e:
        logger.error(f"[{phone_number}] 授權轉換異常: {e}")
        return False


import csv
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from loguru import logger
import os
from datetime import datetime
import threading
from datetime import datetime
import os
import re
import requests
from datetime import datetime
from urllib.parse import unquote
# --- 全局定義文件鎖 ---
# 這個鎖會被所有線程共享，確保同一時間只有一個線程在寫文件
# 用来收集“安全信息挂号”的结果
security_lock = threading.Lock()
security_results = []  # 每项：(datetime对象, 输出行字符串)
# 保持你之前要求的時間文件夾邏輯
task_time = datetime.now().strftime("%Y%m%d_%H%M%S")
save_dir = os.path.join("results", task_time)
result_dir = os.path.join("results", task_time)

def save_to_named_file(line, filename):
    if not os.path.exists(save_dir):
        os.makedirs(save_dir, exist_ok=True)

    file_path = os.path.join(save_dir, filename)
    with file_write_lock:
        with open(file_path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
def get_alias_page_final(session):
    target_url = "https://account.live.com/names/manage?mkt=zh-CN"
    res = session.get(target_url, verify=False, timeout=15)
    
    if "fmHF" in res.text:
        try:
            action = re.search(r'action="([^"]+)"', res.text).group(1)
            fields = re.findall(r'name="([^"]+)" id="[^"]+" value="([^"]*)"', res.text)
            relay_data = {name: value for name, value in fields}
            res = session.post(action, data=relay_data, headers={"Referer": res.url}, verify=False, timeout=15)
            
        except Exception:
            pass
    return res.text
def normalize_email(email: str):
    """
    把 @ / %40 / %2540 统一还原成 @
    """
    if not email:
        return None
    email = email.strip().lower()
    for _ in range(3):  # 防止多重编码
        email = unquote(email)
    return email
import re
def extract_itmail_from_html(html: str):
    """
    从微软页面 HTML 中提取 itmail.work 相关邮箱（明文 / 编码）
    返回一个 set，避免重复
    """
    html_lower = html.lower()
    results = set()

    # 1️⃣ 明文邮箱
    for m in re.findall(
        r'[a-z0-9._%+-]+@itmail\.work',
        html_lower
    ):
        results.add(m)

    # 2️⃣ 编码邮箱（@ -> %40）
    for m in re.findall(
        r'[a-z0-9._%+-]+%40itmail\.work',
        html_lower
    ):
        results.add(m)

    return results
def extract_backup_email(line: str):
    """
    新格式：
    账号:密码 | 邮箱 | Date: ... | 其它内容...
    只从第 2 段提取邮箱，避免后面字段干扰
    """
    # 只切前两段（最多切两次），后面再多字段也不影响
    parts = [p.strip() for p in line.split("|", 2)]
    if len(parts) < 2:
        return None

    candidate = parts[1]

    # 严格邮箱匹配，防止误抓
    m = re.search(r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', candidate)
    return m.group(0).lower() if m else None
def process_account(line):
    try:
        line = line.strip()
        if not line:
            return

        # 1. 提取账号密码
        account_part = line.split("|")[0].strip()
        if ":" not in account_part:
            return
        num, pwd = account_part.split(":", 1)

        # 2. 提取行内密保邮箱
        backup_email = extract_backup_email(line)
        normalized_backup = normalize_email(backup_email)

        
        # 3. 登录
        cookies = login(num, pwd)

        if cookies == 1:
            save_to_named_file(f"(密码错误)------{line}", "error.txt")
            logger.error(f"[{num}] 密码错误")
            return

        if cookies == 2:
            save_to_named_file(f"(2FA验证)------{line}", "error.txt")
            logger.warning(f"[{num}] 需要2FA或环境拦截")
            return

        if not cookies or isinstance(cookies, int):
            save_to_named_file(f"(登录失败)------{line}", "error.txt")
            logger.error(f"[{num}] 登录失败")
            return

        # 4. 构建 session
        session = requests.Session()
        session.cookies.update(cookies)
        session.headers.update({
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
            "Accept-Language": "zh-CN,zh;q=0.9"
        })

        # 5. 请求页面
        html = get_alias_page_final(session)
        html_lower = html.lower()

        # 6. 从页面中提取 itmail（明文 / 编码）
        raw_page_emails = set()
        raw_page_emails.update(
            re.findall(r'[a-z0-9._%+-]+@itmail\.work', html_lower)
        )
        raw_page_emails.update(
            re.findall(r'[a-z0-9._%+-]+%40itmail\.work', html_lower)
        )
        raw_page_emails.update(
            re.findall(r'[a-z0-9._%+-]+%2540itmail\.work', html_lower)
        )

        # 统一归一化
        page_emails = set()
        for e in raw_page_emails:
            ne = normalize_email(e)
            if ne:
                page_emails.add(ne)

        logger.info(f"[{num}] 微软页面提取 itmail 邮箱(归一化): {page_emails}")

        # DEBUG 文件
        save_to_named_file(
            f"[{num}] 行内:{normalized_backup} | 页面:{page_emails} || {line}",
            "debug.txt"
        )

        # 7. 判断是否安全信息挂号
        if "需要安全代码" in html_lower and "安全信息于" in html_lower:

            # 提取解封日期
            match = re.search(
                r"安全信息于\s*([0-9]{4}\s*/\s*[0-9]{1,2}\s*/\s*[0-9]{1,2})\s*更新后",
                html_lower
            )

            unlock_date = None
            unlock_date_str = None
            if match:
                unlock_date_str = match.group(1).replace(" ", "")
                unlock_date = datetime.strptime(unlock_date_str, "%Y/%m/%d")

            # 👉 正确的密保判断逻辑
            # 只有「页面真的展示了邮箱」时才比
            mail_changed = False
            if page_emails and normalized_backup:
                if normalized_backup not in page_emails:
                    mail_changed = True

            logger.info(
                f"[{num}] 密保比对 | 行内:{normalized_backup} | 页面:{page_emails} | mail_changed:{mail_changed}"
            )

            if mail_changed:
                logger.warning(
                    f"[{num}] 状态：安全信息挂号 | 密保已改 | 解封日期 {unlock_date_str}"
                )
                save_to_named_file(
                    f"(安全信息挂号-密保已改-解封:{unlock_date_str})------{line}",
                    "异常.txt"
                )
            else:
                logger.warning(
                    f"[{num}] 状态：安全信息挂号 | 密保未改 | 解封日期 {unlock_date_str}"
                )
                if unlock_date:
                    with security_lock:
                        security_results.append((
                            unlock_date,
                            f"(安全信息挂号-解封:{unlock_date_str})------{line}"
                        ))
                else:
                    with security_lock:
                        security_results.append((
                            datetime.min,
                            f"(安全信息挂号-无日期)------{line}"
                        ))
            return

        # 8. 正常账号
        logger.success(f"[{num}] 状态：正常")
        save_to_named_file(line, "取消.txt")

    except Exception as e:
        logger.error(f"[{line}] 执行崩溃: {e}")
if __name__ == "__main__":
    
    if not os.path.exists("cc.txt"):
        logger.error("找不到 cc.txt")
    else:
        with open("cc.txt", "r", encoding="utf-8") as f:
            lines = [l.strip() for l in f if ":" in l]

        logger.info(f"🚀 开始分类分析 | 目录: {result_dir} | 总数: {len(lines)}")
        
        with ThreadPoolExecutor(max_workers=50) as executor:
            executor.map(process_account, lines)

        # 🔥 关键新增：排序 + 写入
        if security_results:
            security_results.sort(key=lambda x: x[0])
            for _, line in security_results:
                save_to_named_file(line, "未取消.txt")

    logger.info("任务结束。")