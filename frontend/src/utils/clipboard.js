/**
 * 复制到剪贴板，带一条非安全上下文的兜底。
 *
 * `navigator.clipboard` 只在 HTTPS 或 localhost 下可用；HTTP 局域网 IP 访问时它是
 * undefined，直接调会抛错、按钮永远停在「已复制」却什么都没复制。兜底走 textarea +
 * execCommand（已废弃，但仍是那一刻唯一能用的路）。
 *
 * 失败一律不抛：复制是锦上添花，卡住界面才是真问题。
 */
export async function copyToClipboard(text) {
  if (!text) return false
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      return ok
    } catch {
      return false
    }
  }
}