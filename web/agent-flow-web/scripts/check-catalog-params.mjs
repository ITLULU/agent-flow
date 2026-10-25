/**
 * 画布参数目录漂移检查：NODE_CATALOG 里每个表单字段 key 必须在同名的 node-adapters 执行器里出现。
 * 参数名对不上的字段等于在画布上摆了个填了没用的输入框（历史上 xhs.draft 的 tags、http.collect 的 method 就是这样）。
 *
 *   node web/agent-flow-web/scripts/check-catalog-params.mjs
 * 退出码非 0 表示有漂移；CI 未启用 web-build，所以本地自测时手动跑。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NODE_CATALOG } from '../src/nodeCatalog.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, process.argv[2] ?? '../../..');
const nodesDir = path.join(
  repoRoot,
  'server/agent-flow-server/node-adapters/src/main/java/com/agentflow/nodes',
);

function javaFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) javaFiles(p, out);
    else if (entry.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const sources = javaFiles(nodesDir).map((f) => ({ f, src: fs.readFileSync(f, 'utf8') }));
let drift = 0;

for (const node of NODE_CATALOG) {
  const matched = sources.filter((s) => s.src.includes(`"${node.type}"`));
  if (matched.length === 0) {
    console.log(`NO-BACKEND  ${node.type}：node-adapters 里没有这个 TYPE`);
    drift++;
    continue;
  }
  const merged = matched.map((s) => s.src).join('\n');
  const missing = node.fields.filter((fl) => !merged.includes(`"${fl.key}"`)).map((fl) => fl.key);
  if (missing.length) {
    console.log(
      `DRIFT       ${node.type}：表单字段 ${missing.join(', ')} 在后端找不到同名参数` +
        `（${matched.map((s) => path.basename(s.f)).join(' ')}）`,
    );
    drift++;
  }
}

if (drift) {
  console.log(`✗ ${drift} 个节点参数目录与后端不一致`);
  process.exit(1);
}
console.log(`✓ ${NODE_CATALOG.length} 个节点的表单字段全部对得上 node-adapters 参数名`);
