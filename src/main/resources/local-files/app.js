'use strict';
// Older cards escaped separators as literal HTML entities. Normalize only known
// parameter separators, before parsing and before sending the signed query.
const query = location.search.replace(/&amp;(?=(?:workspace|path|line|column|editor|signature)=)/g, '&');
const params = new URLSearchParams(query);
const status = document.getElementById('status');
const retry = document.getElementById('retry');
const copy = document.getElementById('copy');
const path = params.get('path');
const line = params.get('line');
const column = params.get('column');
const editor = params.get('editor');
let busy = false;
const countdown = document.getElementById('countdown');
const cancelClose = document.getElementById('cancel-close');
let closeTimer = null;
let autoCloseCancelled = false;
function stopCountdown() {
  if (closeTimer !== null) clearInterval(closeTimer);
  closeTimer = null;
  cancelClose.disabled = true;
}
function startCountdown() {
  if (autoCloseCancelled) return;
  stopCountdown();
  const deadline = Date.now() + 10000;
  cancelClose.disabled = false;
  function updateCountdown() {
    const remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
    countdown.textContent = remaining + ' 秒后自动关闭此页面。';
    if (remaining === 0) {
      stopCountdown();
      countdown.textContent = '若页面未关闭，浏览器限制了自动关闭，请手动关闭此页面。';
      window.close();
    }
  }
  updateCountdown();
  closeTimer = setInterval(updateCountdown, 250);
}
cancelClose.addEventListener('click', () => {
  autoCloseCancelled = true;
  stopCountdown();
  countdown.textContent = '已取消自动关闭，可继续使用此页面。';
});
window.addEventListener('pagehide', stopCountdown);
async function openFile() {
  if (busy) return;
  stopCountdown();
  countdown.textContent = autoCloseCancelled ? '已取消自动关闭。' : '';
  busy = true;
  retry.disabled = true;
  status.textContent = '正在请求编辑器打开项目和文件…';
  try {
    const response = await fetch('/api/open' + query, {
      method: 'POST', headers: { 'X-Bridge-Open': '1' }, signal: AbortSignal.timeout(30000)
    });
    const messages = {
      200: '已请求 ' + (editor === 'trae' ? 'Trae CN' : 'VS Code') + ' 打开，请查看编辑器窗口。',
      400: '链接参数无效，请让机器人重新发送。',
      403: '链接已失效或校验失败，请让机器人重新发送。',
      404: '文件不存在或不是普通文件。',
      503: '无法启动编辑器，请检查本机是否已安装对应编辑器及 CLI。'
    };
    status.textContent = messages[response.status] || '打开失败，请重试。';
    if (response.status === 200) startCountdown();
  } catch (_) {
    status.textContent = '本地服务无响应。若服务已重启，请让机器人重新发送链接。';
  } finally { busy = false; retry.disabled = false; }
}
if (path && params.has('workspace') && params.has('signature') && ['trae', 'vscode'].includes(editor)) {
  document.getElementById('filename').textContent = path.split(/[\\/]/).pop();
  document.getElementById('path').textContent = path;
  document.getElementById('position').textContent = line ? '第 ' + line + ' 行' + (column ? ' · 第 ' + column + ' 列' : '') : '打开文件';
  copy.disabled = false;
  retry.addEventListener('click', openFile);
  copy.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(path + (line ? ':' + line : '') + (column ? ':' + column : '')); status.textContent = '路径已复制。'; }
    catch (_) { status.textContent = '无法复制，请手动选取上方路径。'; }
  });
  openFile();
} else { status.textContent = '链接不完整，请从机器人回复中的文件链接打开。'; }
