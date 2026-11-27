/**
 * AgentForge FIM 补全演示。
 *
 * 两个核心点：
 *
 * 1）光标处渲染：textarea 无法单独给补全文本着色，因此在它下面铺一层同字体/同间距的幽灵层。
 *    有补全时 textarea 的文字暂时透明（光标仍可见），由幽灵层统一排版「前文 + 补全 + 后文」，
 *    因此流式补全增长时后文会真实下移，不会与补全重叠。
 *
 * 2）案例即上下文：每个案例用 {{CURSOR}} 标记缺口位置，选中后自动把光标停在那里，
 *    用户不需要自己找位置，可以直接输入或按 Tab 看效果。
 */
(function () {
  'use strict';

  // ==================== DOM ====================
  var editor = document.getElementById('editor');
  var ghostLayer = document.getElementById('ghostLayer');
  var gutter = document.getElementById('gutter');
  var statusbar = document.querySelector('.statusbar');
  var statusText = document.getElementById('statusText');
  var cursorInfo = document.getElementById('cursorInfo');
  var latencyInfo = document.getElementById('latencyInfo');
  var completionBox = document.getElementById('completionBox');
  var logList = document.getElementById('logList');
  var meta = document.getElementById('meta');
  var configList = document.getElementById('configList');
  var autoTrigger = document.getElementById('autoTrigger');
  var triggerBtn = document.getElementById('triggerBtn');
  var resetBtn = document.getElementById('resetBtn');
  var probeBtn = document.getElementById('probeBtn');
  var probeResult = document.getElementById('probeResult');
  var caseList = document.getElementById('caseList');
  var langBadge = document.getElementById('langBadge');

  // ==================== 状态 ====================
  var ghostText = '';
  var busy = false;
  var currentSource = null;
  var debounceTimer = null;
  var lastRequestId = 0;
  var activeCase = null;

  var DEBOUNCE_MS = 500;
  var MAX_PREFIX = 3000;

  // ==================== 案例集 ====================
  // {{CURSOR}} 标记缺口位置：加载后会被移除，光标停在该处。
  var CASES = [
    {
      id: 'py-add',
      label: 'Python 函数体',
      lang: 'python',
      text:
        'import os\n' +
        'import json\n' +
        '\n' +
        'def add(a, b):\n' +
        '    {{CURSOR}}\n' +
        '\n' +
        'assert add(2, 3) == 5\n' +
        'assert add(-1, 1) == 0\n'
    },
    {
      id: 'py-branch',
      label: 'Python 分支',
      lang: 'python',
      text:
        'import os\n' +
        'import json\n' +
        '\n' +
        'def load_config(path):\n' +
        '    if not os.path.exists(path):\n' +
        '        {{CURSOR}}\n' +
        '    with open(path) as f:\n' +
        '        return json.load(f)\n'
    },
    {
      id: 'py-long',
      label: 'Python 长上下文',
      lang: 'python',
      text:
        'import requests\n' +
        'import logging\n' +
        '\n' +
        'logger = logging.getLogger(__name__)\n' +
        '\n' +
        'DEFAULT_TIMEOUT = 10\n' +
        'MAX_RETRY = 3\n' +
        'BASE_URL = "https://api.example.com"\n' +
        '\n' +
        '\n' +
        'def fetch_user(user_id):\n' +
        '    url = f"{BASE_URL}/users/{user_id}"\n' +
        '    {{CURSOR}}\n' +
        '\n' +
        'if __name__ == "__main__":\n' +
        '    print(fetch_user(1))\n'
    },
    {
      id: 'java-add',
      label: 'Java 方法体',
      lang: 'java',
      text:
        'public class MathUtil {\n' +
        '\n' +
        '    public static int add(int a, int b) {\n' +
        '        {{CURSOR}}\n' +
        '    }\n' +
        '\n' +
        '    public static int sub(int a, int b) {\n' +
        '        return a - b;\n' +
        '    }\n' +
        '}\n'
    },
    {
      id: 'go-sum',
      label: 'Go 切片求和',
      lang: 'go',
      text:
        'package main\n' +
        '\n' +
        'import "fmt"\n' +
        '\n' +
        'func Sum(nums []int) int {\n' +
        '    {{CURSOR}}\n' +
        '}\n' +
        '\n' +
        'func main() {\n' +
        '    fmt.Println(Sum([]int{1, 2, 3}))\n' +
        '}\n'
    },
    {
      id: 'sql-join',
      label: 'SQL 补 JOIN',
      lang: 'sql',
      text:
        'SELECT u.name, COUNT(o.id) AS cnt\n' +
        'FROM users u\n' +
        '        {{CURSOR}}\n' +
        'GROUP BY u.name;\n'
    }
  ];

  // ==================== 工具 ====================

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function log(message, level) {
    var li = document.createElement('li');
    var time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    li.innerHTML =
      '<span class="t">' + time + '</span>' +
      '<span class="' + (level || 'info') + '">' + escapeHtml(message) + '</span>';
    logList.insertBefore(li, logList.firstChild);
    while (logList.children.length > 60) {
      logList.removeChild(logList.lastChild);
    }
  }

  function setStatus(text, kind) {
    statusText.textContent = text;
    statusbar.className = 'statusbar' + (kind ? ' ' + kind : '');
  }

  /**
   * 只对编辑器中的 Ghost Text 做展示级清理。
   *
   * 某些 coder 模型在到达 max_tokens 前会输出训练语料分隔符，
   * 或者连续输出尾部空行。这些内容不应撑高虚拟文档，否则后文会被推到很远，
   * 视觉上就像编辑器滚动后变成空白。
   */
  function normalizeGhostText(raw) {
    var text = String(raw || '');
    var boundary = text.search(/<\|?file_sep\|?>|<\|?fim_(?:prefix|suffix|middle|pad)\|?>/);
    if (boundary >= 0) {
      text = text.substring(0, boundary);
    }
    // 中间最多保留一个空白行；流式尾部空白不参与占位。
    return text.replace(/\n[ \t]*\n(?:[ \t]*\n)+/g, '\n\n').replace(/\s+$/, '');
  }

  // ==================== 渲染 ====================

  function renderGutter() {
    var virtualValue = editor.value;
    if (ghostText) {
      var cursor = editor.selectionStart;
      virtualValue =
        virtualValue.substring(0, cursor) + ghostText + virtualValue.substring(cursor);
    }
    var lines = virtualValue.split('\n').length;
    var out = [];
    for (var i = 1; i <= Math.max(lines, 1); i++) {
      out.push(i);
    }
    gutter.textContent = out.join('\n');
    gutter.scrollTop = editor.scrollTop;
  }

  /**
   * 渲染幽灵补全。
   *
   * 把补全插在光标位置，前后文本用透明色占位 —— 文本流完全相同，
   * 因此换行、缩进都会自动对齐，不会出现错位。
   */
  function renderGhost() {
    var value = editor.value;
    var pos = editor.selectionStart;

    if (!ghostText) {
      editor.classList.remove('ghost-active');
      editor.style.paddingBottom = '';
      ghostLayer.innerHTML =
        '<span style="color:transparent">' + escapeHtml(value) + '</span>';
      ghostLayer.style.transform = '';
      return;
    }

    var before = value.substring(0, pos);
    var after = value.substring(pos);
    var virtualText = before + ghostText + after;
    var virtualLines = virtualText.split('\n').length;

    // textarea 仍接管输入、选区与光标，但文字交给幽灵层统一排版。
    // 只补齐「虚拟文档实际高度 - textarea 原始滚动高度」，不能直接按补全行数加 padding。
    // textarea 内容较少时 scrollHeight 至少等于一整个视口，直接累加会导致可滚出一整屏空白。
    editor.classList.add('ghost-active');
    editor.style.paddingBottom = '';
    var virtualHeight = virtualLines * 21 + 28;
    var missingHeight = Math.max(0, virtualHeight - editor.scrollHeight);
    editor.style.paddingBottom = missingHeight ? missingHeight + 'px' : '';

    ghostLayer.innerHTML =
      '<span class="source-text">' + escapeHtml(before) + '</span>' +
      '<span class="ghost-text">' + escapeHtml(ghostText) + '</span>' +
      '<span class="source-text">' + escapeHtml(after) + '</span>';

    ghostLayer.style.transform =
      'translate(' + (-editor.scrollLeft) + 'px, ' + (-editor.scrollTop) + 'px)';
  }

  function renderCursorInfo() {
    var pos = editor.selectionStart;
    var before = editor.value.substring(0, pos);
    var lines = before.split('\n');
    cursorInfo.textContent = '行 ' + lines.length + '，列 ' + (lines[lines.length - 1].length + 1);
    renderGutter();
  }

  function clearGhost() {
    ghostText = '';
    renderGhost();
  }

  // ==================== 案例加载 ====================

  function buildCaseChips() {
    CASES.forEach(function (item) {
      var chip = document.createElement('button');
      chip.className = 'case-chip';
      chip.textContent = item.label;
      chip.dataset.id = item.id;
      chip.addEventListener('click', function () {
        loadCase(item.id);
      });
      caseList.appendChild(chip);
    });
  }

  /**
   * 加载案例：把 {{CURSOR}} 替换掉，并把光标精确停在该位置。
   *
   * 这样用户选中案例后不需要自己找缺口，直接输入或按 Tab 即可。
   */
  function loadCase(id) {
    var item = null;
    for (var i = 0; i < CASES.length; i++) {
      if (CASES[i].id === id) {
        item = CASES[i];
        break;
      }
    }
    if (!item) {
      return;
    }

    abortCurrent();
    activeCase = item;

    var marker = '{{CURSOR}}';
    var raw = item.text;
    var cursorPos = raw.indexOf(marker);
    var content = cursorPos >= 0
      ? raw.substring(0, cursorPos) + raw.substring(cursorPos + marker.length)
      : raw;
    if (cursorPos < 0) {
      cursorPos = content.length;
    }

    editor.value = content;
    editor.setSelectionRange(cursorPos, cursorPos);
    langBadge.textContent = item.lang;

    Array.prototype.forEach.call(caseList.children, function (chip) {
      chip.classList.toggle('active', chip.dataset.id === id);
    });

    clearGhost();
    renderCursorInfo();
    renderGhost();
    setStatus('就绪', '');
    completionBox.className = 'completion empty';
    completionBox.textContent = '光标已停在缺口处，按 Tab 或停止输入触发补全';
    editor.focus();

    log('加载案例：' + item.label + '（' + item.lang + '）');
  }

  // ==================== 补全请求 ====================

  function abortCurrent() {
    if (currentSource) {
      currentSource.close();
      currentSource = null;
    }
    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    busy = false;
  }

  /**
   * 光标位置或文本一旦变化，立即使旧请求失效。
   *
   * 不仅关闭 SSE，还递增 requestId：即使浏览器队列中已有旧 chunk，
   * 回调也会因 requestId 不匹配而被丢弃，不会闪回新光标位置。
   */
  function invalidateCompletion() {
    lastRequestId++;
    abortCurrent();
    clearGhost();
  }

  function buildRequest() {
    var value = editor.value;
    var pos = editor.selectionStart;
    var prefix = value.substring(0, pos);
    // 本地 7B 上下文有限，行内补全只需紧邻光标的上下文
    if (prefix.length > MAX_PREFIX) {
      prefix = '...' + prefix.substring(prefix.length - MAX_PREFIX);
    }
    return {
      prefix: prefix,
      suffix: value.substring(pos),
      language: activeCase ? activeCase.lang : 'plaintext'
    };
  }

  function requestCompletion() {
    var req = buildRequest();
    if (!req.prefix.trim() && !req.suffix.trim()) {
      setStatus('内容为空，跳过', 'warn');
      return;
    }

    abortCurrent();

    var requestId = ++lastRequestId;
    busy = true;
    clearGhost();
    setStatus('请求中…', 'busy');
    latencyInfo.textContent = '';

    var start = performance.now();
    var url =
      '/api/fim/stream?prefix=' + encodeURIComponent(req.prefix) +
      '&suffix=' + encodeURIComponent(req.suffix) +
      '&language=' + encodeURIComponent(req.language);

    var source = new EventSource(url);
    currentSource = source;
    var received = '';

    source.addEventListener('chunk', function (e) {
      // 丢弃过期响应：用户已继续输入，旧结果不能覆盖新状态
      if (requestId !== lastRequestId) {
        return;
      }
      var data = JSON.parse(e.data);
      received += data.delta || '';
      ghostText = normalizeGhostText(received);
      renderGhost();
      renderGutter();
      setStatus('流式接收中…', 'busy');
      latencyInfo.textContent =
        Math.round(performance.now() - start) + 'ms · ' + received.length + ' 字符';
    });

    source.addEventListener('done', function (e) {
      var data = JSON.parse(e.data);
      source.close();
      currentSource = null;
      if (requestId !== lastRequestId) {
        return;
      }
      busy = false;
      ghostText = normalizeGhostText(data.completion || received);
      renderGhost();
      renderGutter();

      if (!ghostText) {
        setStatus('补全为空（检查 Token 模板）', 'err');
        completionBox.className = 'completion empty';
        completionBox.textContent =
          '模型返回空补全。\nfinish=' + data.finishReason + '\n\n' +
          '常见原因：fim.template 与部署 tokenizer 不匹配。\n点右侧「探测 Token 形态」确认。';
        log('补全为空，finish=' + data.finishReason, 'err');
        return;
      }

      setStatus('完成，按 Tab 接受', 'ok');
      latencyInfo.textContent = 'TTFT ' + data.ttftMs + 'ms · 总计 ' + data.wallMs + 'ms';
      completionBox.className = 'completion';
      completionBox.textContent = ghostText;
      log('完成：' + ghostText.length + ' 字符，TTFT ' + data.ttftMs + 'ms', 'ok');
    });

    source.addEventListener('error', function (e) {
      if (e && e.data) {
        try {
          log('错误：' + JSON.parse(e.data).message, 'err');
          setStatus('请求失败', 'err');
        } catch (ignore) {
          // 非 JSON 错误体
        }
      }
      source.close();
      currentSource = null;
      if (requestId !== lastRequestId) {
        return;
      }
      busy = false;
      if (!ghostText) {
        setStatus('连接失败或超时', 'err');
      }
    });

    log('发起补全：' + req.language + '，prefix ' + req.prefix.length + ' 字符');
  }

  /** Tab 接受：把幽灵文本真正插入光标处。 */
  function acceptGhost() {
    if (!ghostText) {
      return false;
    }
    var pos = editor.selectionStart;
    var value = editor.value;
    editor.value = value.substring(0, pos) + ghostText + value.substring(pos);
    var newPos = pos + ghostText.length;
    editor.setSelectionRange(newPos, newPos);
    clearGhost();
    renderCursorInfo();
    completionBox.className = 'completion empty';
    completionBox.textContent = '已接受补全，可继续输入触发下一次';
    setStatus('已接受补全', 'ok');
    log('接受补全：' + ghostText.length + ' 字符', 'ok');
    editor.focus();
    return true;
  }

  // ==================== 事件绑定 ====================

  editor.addEventListener('input', function () {
    invalidateCompletion();
    renderCursorInfo();
    if (autoTrigger.checked) {
      if (debounceTimer) {
        clearTimeout(debounceTimer);
      }
      debounceTimer = setTimeout(requestCompletion, DEBOUNCE_MS);
    }
  });

  editor.addEventListener('keydown', function (e) {
    if (e.key === 'Tab') {
      e.preventDefault();
      if (ghostText) {
        acceptGhost();
      } else {
        requestCompletion();
      }
      return;
    }
    if (e.key === 'Escape' && ghostText) {
      e.preventDefault();
      clearGhost();
      setStatus('已放弃补全', 'warn');
      log('放弃补全', 'warn');
      return;
    }
    if (e.key === 'Enter') {
      clearGhost();
    }
  });

  editor.addEventListener('scroll', function () {
    renderGhost();
    gutter.scrollTop = editor.scrollTop;
  });

  editor.addEventListener('click', function () {
    invalidateCompletion();
    renderCursorInfo();
    setStatus('就绪', '');
  });

  editor.addEventListener('keyup', function (e) {
    var nav = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'];
    if (nav.indexOf(e.key) >= 0) {
      invalidateCompletion();
      renderCursorInfo();
      setStatus('就绪', '');
    }
  });

  // 拖选文本、Shift+方向键、触摸选区都会走 select；只取消，绝不自动请求。
  editor.addEventListener('select', function () {
    invalidateCompletion();
    renderCursorInfo();
  });

  triggerBtn.addEventListener('click', requestCompletion);

  resetBtn.addEventListener('click', function () {
    if (activeCase) {
      loadCase(activeCase.id);
    }
  });

  // ==================== 配置与探测 ====================

  function loadInfo() {
    fetch('/api/fim/info')
      .then(function (r) { return r.json(); })
      .then(function (info) {
        meta.innerHTML = escapeHtml(info.modelName) + '<br>' + escapeHtml(info.baseUrl);
        configList.innerHTML = '';
        [
          ['模型', info.modelName],
          ['模板', info.template],
          ['温度', info.temperature],
          ['maxTokens', info.maxTokens],
          ['超时', info.timeoutSeconds + 's'],
          ['流式', info.streaming ? '是' : '否']
        ].forEach(function (pair) {
          var div = document.createElement('div');
          div.innerHTML =
            '<dt>' + escapeHtml(String(pair[0])) + '</dt>' +
            '<dd>' + escapeHtml(String(pair[1])) + '</dd>';
          configList.appendChild(div);
        });
        log('配置已加载，模板=' + info.template);
      })
      .catch(function (err) {
        log('加载配置失败：' + err.message, 'err');
      });
  }

  probeBtn.addEventListener('click', function () {
    probeBtn.disabled = true;
    probeResult.innerHTML = '探测中…（对模型发 5 次极小请求）';
    log('开始探测 Token 形态');

    fetch('/api/fim/probe')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var html = '';
        (data.candidates || []).forEach(function (c) {
          var cls = c.isSpecialToken ? 'ok' : 'bad';
          var mark = c.isSpecialToken ? '✓' : '✗';
          html +=
            '<div class="row"><span>' + escapeHtml(c.template) + '</span>' +
            '<span class="' + cls + '">' + mark + ' ' +
            (c.promptTokens === null ? 'N/A' : c.promptTokens) + ' tokens</span></div>';
        });
        html += '<div class="rec">推荐模板：' +
          escapeHtml(data.recommendedTemplate || 'native-suffix') + '</div>';
        html += '<div style="margin-top:6px">' + escapeHtml(data.hint || '') + '</div>';
        probeResult.innerHTML = html;
        log('探测完成，推荐 ' + data.recommendedTemplate, 'ok');
      })
      .catch(function (err) {
        probeResult.innerHTML =
          '<span class="bad">探测失败：' + escapeHtml(err.message) + '</span>';
        log('探测失败：' + err.message, 'err');
      })
      .finally(function () {
        probeBtn.disabled = false;
      });
  });

  // ==================== 初始化 ====================
  buildCaseChips();
  loadInfo();
  loadCase('py-add');

  window.addEventListener('beforeunload', abortCurrent);
})();
