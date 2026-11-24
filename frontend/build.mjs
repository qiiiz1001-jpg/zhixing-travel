import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const [output] = process.argv.slice(2)
if (!output) throw new Error('Missing frontend output directory')

const lock = createHash('sha256').update(readFileSync('package-lock.json')).digest('hex')
const marker = 'node_modules/.frontend-lock'
if (!existsSync(marker) || readFileSync(marker,'utf8') !== lock || !existsSync('node_modules/vite')) {
  const args = ['ci','--cache','.npm-cache','--no-audit','--no-fund','--include=dev']
  const result = spawnSync('npm', args, { stdio:'inherit', shell:process.platform === 'win32' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status || 1)
  writeFileSync(marker,lock)
}

const { build } = await import('vite')
await build({ build:{ outDir:resolve(output), emptyOutDir:true } })

