import { h } from 'vue'

function inline(text) {
  const parts = []
  const pattern = /\[([^\]]+)\]\(([^\s)]+)\)|\*\*([^*]+)\*\*|\x60([^\x60]+)\x60/g
  let offset = 0
  for (const match of text.matchAll(pattern)) {
    parts.push(text.slice(offset, match.index))
    if (match[1]) {
      let url
      try { url = new URL(match[2]) } catch { /* Display malformed links as text. */ }
      parts.push(url && ['https:', 'http:'].includes(url.protocol)
        ? h('a', { href: url.href, target: '_blank', rel: 'noopener noreferrer' }, match[1]) : match[1])
    } else parts.push(h(match[3] ? 'strong' : 'code', match[3] || match[4]))
    offset = match.index + match[0].length
  }
  parts.push(text.slice(offset))
  return parts
}

export default function MarkdownBlock({ text = '' }) {
  const blocks = []
  let code = null
  let list = null
  for (const line of String(text).split(/\r?\n/)) {
    if (line.trimStart().startsWith('\x60\x60\x60')) {
      if (code) code = null
      else {
        code = []
        blocks.push({ kind: 'pre', lines: code })
      }
      list = null
      continue
    }
    if (code) { code.push(line); continue }
    if (!line.trim()) { list = null; continue }
    const bullet = line.match(/^\s*([-*+]|\d+[.)])\s+(.+)$/)
    if (bullet) {
      const kind = /^\d/.test(bullet[1]) ? 'ol' : 'ul'
      if (list?.kind !== kind) { list = { kind, lines: [] }; blocks.push(list) }
      list.lines.push(bullet[2])
      continue
    }
    list = null
    const title = line.match(/^(?:#{1,6}\s+(.+)|【([^】]+)】)$/)
    const quote = line.match(/^>\s*(.+)$/)
    blocks.push({ kind: title ? 'h4' : quote ? 'blockquote' : 'p', text: title?.[1] || title?.[2] || quote?.[1] || line })
  }
  return h('div', { class: 'markdown' }, blocks.map(block => {
    if (block.kind === 'pre') return h('pre', [h('code', block.lines.join('\n'))])
    if (block.lines) return h(block.kind, block.lines.map(line => h('li', inline(line))))
    return h(block.kind, inline(block.text))
  }))
}
MarkdownBlock.props = ['text']

