#!/usr/bin/env python3
"""本机没有 Android/Kotlin 编译器时的**提交前静态自检**。

（构建机在远端、一轮构建要 1 分钟，所以能在这里拦住的错就别浪费一轮。）

检查项：
1. 括号平衡（`{` / `}`）
2. 重复 import、同名类 import 冲突
3. **可能缺失的 import** —— 已在别处踩过：`DemoTimetable` 用了 `LocalDateTime` 没 import，
   导致它自身类型错误、还把 `ClassReminder` 里 `.minusMinutes()` 一起带成"找不到方法"。
   做法：抽出行内大写开头的标识符，减掉 import / 本文件声明 / 同包声明 / 语言内置，剩下的报出来人工确认。
4. 布局里 `android:text` 出现未转义的双引号或 markdown 星号（XML 会炸 / 显示难看）

用法：`python3 tools/static-check.py`（在仓库根目录跑）
"""
import collections
import glob
import os
import subprocess
import xml.etree.ElementTree as ET
import re
import sys

# Kotlin 默认导入 + java.lang + 常见无参 import 的类型
BUILTIN = set("""String Int Long Double Float Boolean Char Byte Short Unit Any Nothing
List MutableList Set MutableSet Map MutableMap HashMap LinkedHashMap HashSet ArrayList ArrayDeque
Pair Triple Array IntArray LongArray FloatArray DoubleArray BooleanArray ByteArray CharArray
Sequence Iterable Collection Iterator Comparator Comparable Closable AutoCloseable
Exception Throwable RuntimeException IllegalStateException IllegalArgumentException IllegalAccessException
IndexOutOfBoundsException NullPointerException Error StringBuilder StringBuffer Number CharSequence
Void Object System Class Thread Runnable Math Integer Character Byte Short Boolean Iterable
JvmOverloads JvmStatic JvmField Volatile Synchronized Suppress Deprecated OptIn
R T E K V S A B C D M N
Charsets LinkedHashSet HashSet ArrayList StringBuilder HashMap LinkedHashMap Arrays Collections
MODE_PRIVATE MODE_APPEND NoSuchMethodException InvocationTargetException ProcessBuilder CLIPBOARD_SERVICE ACTIVITY_SERVICE NOTIFICATION_SERVICE ALARM_SERVICE START_STICKY START_NOT_STICKY STOP_FOREGROUND_REMOVE""".split())
# 说明：Charsets / LinkedHashSet 等属于 Kotlin 默认导入（kotlin.text.* / kotlin.collections.*）；
# MODE_PRIVATE 是 Activity 从 Context 继承来的常量，无需 import —— 这两类会误报，故显式忽略。

# 默认包（无需 import）
AUTO_PACKAGES = ("kotlin.", "kotlinx.", "java.lang.", "kotlin.jvm.")


def collect_declarations(text: str) -> set:
    names = set()
    names |= set(re.findall(r"\b(?:class|object|interface|typealias|enum class)\s+([A-Z]\w*)", text))
    names |= set(re.findall(r"\b(?:const\s+)?va[lr]\s+([A-Za-z_]\w*)", text))       # 常量通常是大写
    names |= set(re.findall(r"\bfun\s+([A-Za-z_]\w*)", text))
    # 枚举/伴生对象里的条目（LEFT, RIGHT, NONE, ROW, GRID…）
    for body in re.findall(r"enum class\s+\w+\s*\{(.*?)\}", text, re.S):
        names |= set(re.findall(r"\b([A-Z][A-Z0-9_]*)\b", body))
    return names



# ---------- 严格括号扫描（状态机：去注释、识别字符串与三引号原始串） ----------
def strict_balance(path):
    """返回 (line, char) 列表：未闭合的多余括号。空列表 = 平衡。"""
    s = open(path, encoding="utf-8").read()
    i, n, line = 0, len(s), 1
    stack = []
    in_str = in_char = in_line_c = in_block_c = in_raw = False
    while i < n:
        c = s[i]
        nxt = s[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1; in_line_c = False; i += 1; continue
        if in_line_c:
            i += 1; continue
        if in_block_c:
            if c == "*" and nxt == "/":
                in_block_c = False; i += 2; continue
            i += 1; continue
        if in_raw:
            if s[i:i + 3] == '"""':
                in_raw = False; i += 3; continue
            i += 1; continue
        if in_str:
            if c == "\\":
                i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if in_char:
            if c == "\\":
                i += 2; continue
            if c == "'":
                in_char = False
            i += 1; continue
        if c == "/" and nxt == "/":
            in_line_c = True; i += 2; continue
        if c == "/" and nxt == "*":
            in_block_c = True; i += 2; continue
        if s[i:i + 3] == '"""':
            in_raw = True; i += 3; continue
        if c == '"':
            in_str = True; i += 1; continue
        if c == "'":
            in_char = True; i += 1; continue
        if c in "({[":
            stack.append((line, c))
        elif c in ")}]":
            want = {"}": "{", ")": "(", "]": "["}[c]
            if stack and stack[-1][1] == want:
                stack.pop()
            else:
                stack.append((line, c))
        i += 1
    return stack

def strip_literals(text: str) -> str:
    """去掉注释/字符串/字符字面量 —— 只用于「结构类」判断（括号平衡、标识符收集）。

    用**逐字符状态机**而不是正则：正则版会把 `"` 配错对，把中间成片的代码一起吞掉，
    于是本来平衡的文件被报成"括号不平衡"（已踩）。Kotlin 的 `\"\"\"…\"\"\"` 原始串里常含 JSON 花括号，
    KDoc/行注释里也常有 `{`、引号，必须精确跳过。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        three = text[i:i + 3]
        if two == "//":                       # 行注释
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif two == "/*":                     # 块注释 / KDoc（Kotlin 支持**嵌套**块注释！）
            depth, j = 1, i + 2
            while j < n and depth > 0:
                if text[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif text[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            i = j
        elif three == '"""':                  # Kotlin 原始字符串
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif text[i] == '"' or text[i] == "'":  # 普通字符串 / 字符字面量
            quote = text[i]
            j = i + 1
            while j < n and text[j] != quote:
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)



def strip_comments_keep_strings(text):
    # 去注释，但保留字符串结构（引号原样留着），供"裸双引号"检查用。
    #
    # 不能用 line.split("//") 那种土办法：字符串里的 https:// 或 content:// 会被截断，
    # 剩下一个引号看起来就像"未转义引号"，全是误报（已踩）。
    # 三引号原始字符串的内部直接抹成空格，避免里面的内容干扰引号计数。
    TRIPLE = '"' * 3
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        three = text[i:i + 3]
        if state == "code":
            if three == TRIPLE:
                out.append(three)
                i += 3
                state = "raw"
                continue
            if two == "//":
                i += 2
                state = "line"
                continue
            if two == "/*":
                i += 2
                state = "block"
                continue
            out.append(c)
            i += 1
            if c == '"':
                state = "str"
            elif c == "'":
                state = "char"
        elif state == "str":
            if c == chr(92):
                out.append(text[i:i + 2])
                i += 2
                continue
            out.append(c)
            i += 1
            if c == '"':
                state = "code"
        elif state == "char":
            # 字符字面量（如 '"'）整个抹成空格：里面的引号不该参与计数
            if c == chr(92):
                i += 2
                continue
            i += 1
            if c == "'":
                out.append(" ")
                state = "code"
        elif state == "raw":
            if three == TRIPLE:
                out.append(three)
                i += 3
                state = "code"
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
        elif state == "line":
            if c == "\n":
                out.append(c)
                i += 1
                state = "code"
            else:
                i += 1
        else:  # block comment
            if two == "*/":
                i += 2
                state = "code"
                continue
            if c == "\n":
                out.append(c)
            i += 1
    return "".join(out)


def main() -> int:
    root = os.getcwd()
    # ---------- 严格控制字符平衡（严格扫描器；踩过：漏一个 ) 导致一串 Syntax error 白构建一轮） ----------
    for x in [f for f in glob.glob("**/*.kt", recursive=True) if "/build/" not in f]:
        bad = strict_balance(x)
        if bad:
            line, ch = bad[0]
            print("  ✗ %s:%d → 多余/未闭合的 %s（严格扫描器判定，会引发一串 Syntax error）" % (x, line, ch))
            ok = False

    kt_files = [f for f in glob.glob("**/*.kt", recursive=True) if "/build/" not in f]
    if not kt_files:
        print("没有找到 .kt 文件（请在仓库根目录运行）")
        return 1

    # AIDL 声明的接口会被生成为**同包**的类，无需 import —— 自检要知道这件事
    aidl_decls = set()
    for a in glob.glob("**/*.aidl", recursive=True):
        if "/build/" in a:
            continue
        aidl_decls |= set(re.findall(r"\b(?:interface|parcelable)\s+(\w+)", open(a, encoding="utf-8").read()))
    if aidl_decls:
        print("AIDL 生成接口（视为同包可见）:", ", ".join(sorted(aidl_decls)))

    # 同包声明集合（简化：同一目录视为同包）
    # 按 **package 声明** 分组，而不是按目录 ——
    # 变体源集（src/oss/kotlin、src/szk/kotlin）目录不同但 package 相同，按目录判会误报"缺 import"
    by_pkg = collections.defaultdict(set)
    pkg_of = {}
    for f in kt_files:
        text = open(f, encoding="utf-8").read()
        m = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        pkg_of[f] = m.group(1) if m else os.path.dirname(f)
        by_pkg[pkg_of[f]] |= collect_declarations(text)

    ok = True

    # ---- 1/2/3  Kotlin 文件
    for f in kt_files:
        raw = open(f, encoding="utf-8").read()
        problems = []

        # 括号平衡要在**剥掉注释与字符串之后**数 ——
        # 否则注释里的 `{`、字符串里的 "{"、startsWith("{") 都会造成误报（已踩）
        braces = strip_literals(raw).count("{") - strip_literals(raw).count("}")
        if braces != 0:
            problems.append("括号不平衡: " + str(braces))

        imports = [l.strip() for l in raw.splitlines() if l.strip().startswith("import ")]
        dup = [k for k, v in collections.Counter(imports).items() if v > 1]
        if dup:
            problems.append("重复 import: " + ", ".join(dup))
        simple = collections.Counter(i.split()[-1].split(".")[-1] for i in imports)
        clash = [k for k, v in simple.items() if v > 1]
        if clash:
            problems.append("同名 import 冲突: " + ", ".join(clash))

        imported = set()
        for i in imports:
            parts = i.split()
            if len(parts) < 2:
                continue
            imported.add(parts[1].split(".")[-1])          # import a.b.C  → C
            if len(parts) >= 4 and parts[2] == "as":
                imported.add(parts[3])                      # ... as D → D

        code = strip_literals(raw)
        used = set(re.findall(r"(?<![\w.])([A-Z][A-Za-z0-9_]{1,})", code))
        kotlin_defaults = "Regex Character StringBuilder Char ArrayDeque Pair Triple Lazy Result String Int Boolean".split()
        candidates = collect_declarations(raw) | by_pkg[pkg_of[f]] | BUILTIN | set(kotlin_defaults) | aidl_decls
        missing = sorted(n for n in used if n not in imported and n not in candidates)
        if missing:
            problems.append("可能缺 import（人工确认）: " + ", ".join(missing))

        if problems:
            ok = False
            print("✗", f)
            for p in problems:
                print("    -", p)

    # ---- 6  跨文件调用 private 函数：私有函数是**文件作用域**，必然编译失败
    #        （已踩：MainActivity 里写了 dp(18f)，但 dp() 只在 IslandOverlay/TimetableGridView 里声明）
    private_funs = collections.defaultdict(set)
    for f in kt_files:
        raw = open(f, encoding="utf-8").read()
        for m in re.finditer(r"\bprivate\s+fun\s+(?:[A-Za-z0-9_<>,.]+\s*\.\s*)?(\w+)", raw):
            private_funs[m.group(1)].add(f)
    for f in kt_files:
        raw = open(f, encoding="utf-8").read()
        code = strip_literals(raw)
        local = set(re.findall(r"\bfun\s+(?:[A-Za-z0-9_<>,.]+\s*\.\s*)?(\w+)", raw))
        for name, owners in sorted(private_funs.items()):
            if f in owners or name in local:
                continue
            if re.search(r"(?<![\w.])" + re.escape(name) + r"\s*\(", code):
                ok = False
                print("✗", f, "调用了别处的 private fun %s()（私有函数是文件作用域 → 编译失败）" % name)

    # ---- 5  Activity 里用到的 id，必须存在于它 setContentView 的那个布局
    #        （搬视图最容易踩：findViewById 返回 null → 赋值给 lateinit → 启动即崩）
    layout_ids = {}
    for x in glob.glob("**/res/layout/*.xml", recursive=True):
        if "/build/" in x:
            continue
        layout_ids[os.path.splitext(os.path.basename(x))[0]] = set(
            re.findall(r"@\+id/(\w+)", open(x, encoding="utf-8").read())
        )
    for f in kt_files:
        raw = open(f, encoding="utf-8").read()
        m = re.search(r"setContentView\(R\.layout\.(\w+)\)", raw)
        if not m:
            continue
        layout = m.group(1)
        declared = layout_ids.get(layout)
        if declared is None:
            ok = False
            print("✗", f, "→ setContentView(R.layout.%s) 找不到这个布局文件" % layout)
            continue
        missing = sorted(i for i in set(re.findall(r"R\.id\.(\w+)", raw)) if i not in declared)
        if missing:
            ok = False
            print("✗", f, "→ 布局 %s 里缺少这些 id: %s" % (layout, ", ".join(missing)))

    # ---- 4  布局 XML
    for x in glob.glob("**/res/layout/*.xml", recursive=True):
        if "/build/" in x:
            continue
        problems = []
        for idx, line in enumerate(open(x, encoding="utf-8").read().splitlines(), 1):
            if "android:text=" not in line:
                continue
            value = line.split("android:text=", 1)[1]
            if value.count('"') > 2 and not value.lstrip().startswith('"@'):
                problems.append("第 %d 行 android:text 里可能有未转义双引号" % idx)
            if "**" in value:
                problems.append("第 %d 行 android:text 里混进了 markdown 星号" % idx)
        if problems:
            ok = False
            print("✗", x)
            for p in problems:
                print("    -", p)

    # ---------- （撤掉的检查）Kotlin 字符串里的裸双引号 ----------
    # 试过用"一行里双引号个数为奇数"来判断字符串里混了未转义引号。结论：**撤掉**。
    #   · 抓不到真正那次 bug：文案里写 显示"校历周"和"教学周" 是**偶数个**裸引号（外层2+内层4），奇偶判据漏掉
    #   · 误报很多：URL 里的 // 、字符字面量 '"' 、三引号原始字符串的分隔行都会命中
    # 教训：这类错误只有 Kotlin 编译器能可靠判定 —— 所以改完字符串要**把那一行读回来确认**，
    #       而不是指望静态脚本。脚本留给"结构类"错误（括号/import/id/接口对齐/删函数残留）。

    # ---------- 中文字符串里的裸双引号（可靠判据） ----------
    # 合法 Kotlin 里不会出现「中文"中文」这种组合：字符串结束引号后面必然是
    # 空格/+/)/, 等，绝不可能是紧挨着的中文字符。所以这条判据几乎零误报，
    # 而且能抓到奇偶检查漏掉的情况（踩过两次：显示"校历周"、以及"本周"判断）。
    cjk = "\u4e00-\u9fff"
    cjk_quote_hits = 0
    for x in kt_files:
        lines = strip_comments_keep_strings(open(x, encoding="utf-8").read()).splitlines()
        for i, line in enumerate(lines, 1):
            for m in re.finditer(r'([' + cjk + r'])"([' + cjk + r'])', line):
                cjk_quote_hits += 1
                print("  ✗ %s:%d → 中文字符中间夹了裸双引号（应改成「」）: …%s\"…" % (x, i, m.group(1) + m.group(2)))
    if cjk_quote_hits:
        ok = False

    # ---------- 非空类型被赋 null（踩过：val note: String 赋成 null → 编译期报错，白费一轮构建） ----------
    # 只查"声明为 String/Int/Long/Boolean（没有 ?）"的字段/变量，后续被赋 null 的情况。
    null_hits = 0
    for x in kt_files:
        body = strip_comments_keep_strings(open(x, encoding="utf-8").read())
        declared = {}
        for i, line in enumerate(body.splitlines(), 1):
            for m in re.finditer(r'\b(?:val|var)\s+(\w+)\s*:\s*(String|Int|Long|Boolean)\b(?!\?)', line):
                declared.setdefault(m.group(1), i)
            for m in re.finditer(r'\b(\w+)\s*=\s*null\b', line):
                name = m.group(1)
                if name in declared:
                    null_hits += 1
                    print("  ✗ %s:%d → %s 声明为非空类型（String/Int/Long/Boolean），却赋了 null" % (x, i, name))
    if null_hits:
        ok = False

    # ---------- 横向 LinearLayout 里的 match_parent 子项（踩过：新按钮吃满整行，后面的全被挤出屏幕） ----------
    for x in sorted(glob.glob("**/res/layout/*.xml", recursive=True)):
        raw = open(x, encoding="utf-8", errors="ignore").read()
        # 逐元素扫描，记录每个元素的开标签属性
        stack = []
        for m in re.finditer(r"<[^>]+>", raw, re.S):
            token = m.group(0)
            if token.startswith("<!--") or token.startswith("<?"):
                continue
            closing = token.startswith("</")
            selfclose = token.rstrip().endswith("/>")
            name = re.match(r"</?([\w.]+)", token)
            if not name:
                continue
            if closing:
                if stack:
                    stack.pop()
                continue
            horizontal = bool(re.search(r'android:orientation="horizontal"', token))
            matchparent = bool(re.search(r'android:layout_width="match_parent"', token))
            if stack and stack[-1] and matchparent:
                line = raw[:m.start()].count("\n") + 1
                print("  ✗ %s:%d → 横向容器里的子项用了 match_parent 宽度（会吃满整行，后面的兄弟被挤出屏幕）" % (x, line))
                ok = False
            if not selfclose:
                stack.append(horizontal)

    # ---------- 同一作用域里重复的 override（踩过：给抽屉监听加回调时重复声明了 onDrawerClosed） ----------
    for x in kt_files:
        code = strip_comments_keep_strings(open(x, encoding="utf-8").read())
        seen_sig = {}
        for m in re.finditer(r"override\s+fun\s+(\w+)\s*\(([^)]*)\)", code):
            name, args = m.group(1), m.group(2)
            sig = name + "(" + ",".join(a.strip().split(":")[0].strip() for a in args.split(",") if a.strip()) + ")"
            if sig in seen_sig:
                line = code[:m.start()].count("\n") + 1
                print("  ✗ %s:%d → override %s 重复声明（首次在 %d 行，会报 Conflicting overloads）" % (x, line, sig, seen_sig[sig]))
                ok = False
            else:
                seen_sig[sig] = code[:m.start()].count("\n") + 1

    # ---------- post/postDelayed/runOnUiThread 里出现 it（收的是 Runnable，没有 it） ----------
    for x in kt_files:
        code = strip_comments_keep_strings(open(x, encoding="utf-8").read())
        for m in re.finditer(r"\.(post|postDelayed|runOnUiThread)\s*(?:\([^)]*\))?\s*\{([^{}]*)\}", code):
            body = m.group(2)
            if re.search(r"\bit\b", body):
                line = code[:m.start()].count("\n") + 1
                print("  ✗ %s:%d → %s{ … it … } 里用了 it（Runnable 没有 it，会 Unresolved reference）" % (x, line, m.group(1)))
                ok = False

    # ---------- dp()/sp() 传 Int 字面量（形参是 Float，Kotlin 不会自动转 → 编译失败） ----------
    for x in kt_files:
        code = strip_comments_keep_strings(open(x, encoding="utf-8").read())
        for m in re.finditer(r"\b(dp|sp)\(\s*\d+\s*\)", code):
            line = code[:m.start()].count("\n") + 1
            print("  ✗ %s:%d → %s() 传了 Int 字面量（形参是 Float，要写 %s(16f)）" % (x, line, m.group(1), m.group(1)))
            ok = False

    # ---------- 同一文件内重复声明常量（踩过：新加 PREFS 常量，文件里已有同名 → 编译失败） ----------
    dup_hits = 0
    for x in kt_files:
        body = strip_comments_keep_strings(open(x, encoding="utf-8").read())
        seen = {}
        for i, line in enumerate(body.splitlines(), 1):
            for m in re.finditer(r'\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]{2,})\b', line):
                name = m.group(1)
                if name in seen:
                    dup_hits += 1
                    print("  ✗ %s:%d → 常量 %s 在本文件重复声明（首次在 %d 行）" % (x, i, name, seen[name]))
                else:
                    seen[name] = i
    if dup_hits:
        ok = False

    # ---------- 变体接口对齐 ----------
    # oss 与 szk 里同名类（如 SzkBridge）：空实现变体必须补齐真实现变体的全部公开函数，
    # 否则共享代码（src/main）里一调用，就有一个变体编不过。踩过：forceRestore。
    variant_classes = {}
    for x in kt_files:
        norm = x.replace("\\", "/")
        m = re.match(r".*sample-timetable/src/(oss|szk)/(.*)$", norm)
        if m:
            variant_classes.setdefault(m.group(2).rsplit("/", 1)[-1], {})[m.group(1)] = x
    parity_hits = 0
    for cls, by_flavor in variant_classes.items():
        if len(by_flavor) < 2:
            continue
        api = {}
        for flavor, path in by_flavor.items():
            text = strip_literals(open(path, encoding="utf-8").read())
            api[flavor] = set(re.findall(r"^\s*(?!.*\b(?:private|internal|override)\b).*\bfun\s+(\w+)\s*\(", text, re.M))
        all_names = set().union(*api.values())
        for flavor, names in api.items():
            missing = all_names - names
            if missing:
                parity_hits += 1
                print("  ✗ %s（%s 变体）缺少其它变体有的公开函数: %s" % (cls, flavor, ", ".join(sorted(missing))))
    if parity_hits:
        ok = False

    # ---------- 被删/改名的函数是否还有调用点 ----------
    # 踩过：把 setXmsfNetworking 改名成 applyRule 时漏改了一处调用 → 一轮构建报废。
    # 用 git 窗口扫"消失的 fun 声明"，再回全仓找残留调用（名字仍被声明则跳过，避免误报）。
    try:
        diff = subprocess.run(
            ["git", "diff", "HEAD~3", "--unified=0", "--", "*.kt"],
            capture_output=True, text=True, timeout=40
        ).stdout
    except Exception:
        diff = ""
    removed_funcs = set()
    for line in diff.splitlines():
        if not line.startswith("-") or line.startswith("---"):
            continue
        m = re.search(r"^\-\s*(?:private\s+|internal\s+|public\s+|protected\s+|override\s+|open\s+|suspend\s+|@\w+\s+)*fun\s+(?:<[^>]+>\s*)?(\w+)", line)
        if m:
            removed_funcs.add(m.group(1))
    if removed_funcs:
        still_declared = set()
        for x in kt_files:
            still_declared |= collect_declarations(open(x, encoding="utf-8").read())
        hits = 0
        for name in sorted(removed_funcs - still_declared - BUILTIN):
            if len(name) < 4:      # 太短的名字容易撞上框架方法
                continue
            for x in kt_files:
                if re.search(r"(?<![\w.])" + name + r"\s*\(", strip_literals(open(x, encoding="utf-8").read())):
                    hits += 1
                    print("  ✗ %s → 调用了已删除/改名的函数 %s()" % (x, name))
        if hits:
            ok = False

    # ---------- XML 真解析（比正则可靠：注释里的 "--"、未闭合标签都能抓到） ----------
    print("XML 解析：")
    xml_bad = 0
    for x in sorted(glob.glob("**/src/**/res/**/*.xml", recursive=True) + glob.glob("**/src/**/AndroidManifest.xml", recursive=True)):
        if "/build/" in x:
            continue
        try:
            ET.parse(x)
        except Exception as e:
            xml_bad += 1
            print("  ✗ %s → %s" % (x, e))
    if xml_bad == 0:
        print("  ✅ 全部合法")
    else:
        ok = False

    # ---------- 跨类引用私有常量（全大写 const，局部变量不会同名遮蔽，误报低） ----------
    private_consts = {}
    for x in kt_files:
        for m in re.finditer(r"private\s+const\s+val\s+([A-Z][A-Z0-9_]{2,})", open(x, encoding="utf-8").read()):
            private_consts.setdefault(m.group(1), set()).add(x)
    # 同名跨类会误报（引用的是公开的那个）—— 只有当该名字**任何地方都没有非 private 声明**时才值得报
    has_public = set()
    for x in kt_files:
        for line in open(x, encoding="utf-8").read().splitlines():
            m = re.search(r"\b(?:const\s+)?(?:val|var)\s+([A-Z][A-Z0-9_]{2,})\b", line)
            if m and "private" not in line:
                has_public.add(m.group(1))
    private_consts = {k: v for k, v in private_consts.items() if k not in has_public}

    const_hits = 0
    for x in kt_files:
        raw_text = open(x, encoding="utf-8").read()
        # 必须剥掉注释与字符串再扫：否则注释里提到某个常量名就会误报（踩过：TimetableImport 注释里的 "BOM"）
        text = strip_literals(raw_text)
        declared = collect_declarations(raw_text)
        for name, owners in private_consts.items():
            if x in owners or name in declared:
                continue
            # 不限定 "前面不能是点"：`Foo.NAME` 这种全限定引用同样取不到别处的 private 成员
            if re.search(r"(?<![\w])" + name + r"(?![\w])", text):
                const_hits += 1
                print("  ✗ %s → 引用了他处 private const %s（应改 internal 或用全限定名）" % (x, name))
    if const_hits:
        ok = False

    print("✅ 静态自检通过" if ok else "❌ 静态自检发现问题（见上）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
