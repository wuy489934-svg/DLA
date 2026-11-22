// Android 宿主桥接：补齐 WebView 缺失的下载、新窗口、外部链接与深浅色同步。
(function () {
  if (window.__hostBridgeReady) return;
  window.__hostBridgeReady = true;
  var host = window.AndroidHost;
  if (!host) return;

  function send(method, name, blob) {
    var reader = new FileReader();
    reader.onloadend = function () {
      var result = String(reader.result || '');
      var comma = result.indexOf(',');
      if (comma < 0) {
        host.toast('读取文件失败');
        return;
      }
      host[method](name, blob.type || 'application/octet-stream', result.slice(comma + 1));
    };
    reader.onerror = function () {
      host.toast('读取文件失败');
    };
    reader.readAsDataURL(blob);
  }

  // 导出历史用 a[download] + blob:，WebView 不触发 DownloadListener，这里手动交给原生
  var nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.getAttribute('href') || '';
    if (this.hasAttribute('download') && href.indexOf('blob:') === 0) {
      var anchor = this;
      fetch(href)
        .then(function (response) {
          return response.blob();
        })
        .then(function (blob) {
          send('saveDownload', anchor.getAttribute('download') || 'download', blob);
        })
        .catch(function () {
          host.toast('下载失败');
        });
      return;
    }
    return nativeClick.call(this);
  };

  // 图片 / PDF 的 target="_blank" 由原生 onCreateWindow 接管后回调这里
  window.__hostOpenBlob = function (url) {
    fetch(url)
      .then(function (response) {
        return response.blob();
      })
      .then(function (blob) {
        var type = blob.type || '';
        send('viewBlob', type === 'application/pdf' ? 'document.pdf' : 'image', blob);
      })
      .catch(function () {
        host.toast('无法打开文件');
      });
  };

  // http(s) 的新窗口一律交给系统浏览器
  document.addEventListener(
    'click',
    function (event) {
      var element = event.target;
      while (element && element.tagName !== 'A') {
        element = element.parentElement;
      }
      if (!element) return;
      var href = element.getAttribute('href') || '';
      if (element.target === '_blank' && href.indexOf('blob:') !== 0 && /^https?:/i.test(href)) {
        event.preventDefault();
        host.openUrl(href);
      }
    },
    true
  );

  // 应用内切换深浅色时同步状态栏图标与窗口底色
  var syncTheme = function () {
    host.setDarkMode(document.body.hasAttribute('data-ds-dark-theme'));
  };
  new MutationObserver(syncTheme).observe(document.body, {
    attributes: true,
    attributeFilter: ['data-ds-dark-theme']
  });
  syncTheme();
})();
