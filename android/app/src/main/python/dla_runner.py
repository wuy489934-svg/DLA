"""DLA 的 Python 执行入口（Chaquopy 内嵌 CPython 3.12）。

Java 侧 [PythonTool] 调用 ``run(code, cwd, timeout_ms)``，返回 JSON 字符串：

    {"ok": bool, "stdout": str, "stderr": str, "error": str, "ms": int}

这里只做三件事：换掉 stdout/stderr、切工作目录、用 sys.settrace 在截止时间中断死循环。
Chaquopy 的 sys.stdout 默认重定向到 logcat，所以必须在执行前替换，执行后恢复。
"""

import io
import json
import os
import sys
import time
import traceback

MAX_OUTPUT = 32000


def _trim(text):
    if len(text) <= MAX_OUTPUT:
        return text
    return text[:MAX_OUTPUT] + "\n…（输出已截断，只保留前 32 KB）"


def run(code, cwd=None, timeout_ms=20000):
    started = time.monotonic()
    try:
        timeout_ms = int(timeout_ms)
    except (TypeError, ValueError):
        timeout_ms = 20000
    deadline = started + max(1, timeout_ms) / 1000.0

    out, err = io.StringIO(), io.StringIO()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    old_cwd = None
    result = {"ok": True, "stdout": "", "stderr": "", "error": "", "ms": 0}

    def tracer(frame, event, arg):
        # 每行/每次调用检查一次截止时间；纯 Python 死循环也能被打断
        if time.monotonic() > deadline:
            raise TimeoutError("__dla_timeout__")
        return tracer

    try:
        if cwd:
            old_cwd = os.getcwd()
            try:
                os.makedirs(cwd, exist_ok=True)
                os.chdir(cwd)
                os.environ["HOME"] = cwd
            except Exception:
                pass
        sys.stdout, sys.stderr = out, err
        sys.settrace(tracer)
        try:
            exec(compile(code, "<dla>", "exec"), {"__name__": "__main__"})
        except TimeoutError as timeout_error:
            if "__dla_timeout__" in str(timeout_error):
                result["ok"] = False
                result["error"] = "执行超时（%d ms），已中断" % timeout_ms
            else:
                raise
        except SystemExit:
            pass
        except BaseException:
            result["ok"] = False
            result["error"] = traceback.format_exc()
        finally:
            sys.settrace(None)
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr
        if old_cwd:
            try:
                os.chdir(old_cwd)
            except Exception:
                pass
        result["stdout"] = _trim(out.getvalue())
        result["stderr"] = _trim(err.getvalue())
        result["ms"] = int((time.monotonic() - started) * 1000)
    return json.dumps(result, ensure_ascii=False)
