import { parentPort, workerData } from 'node:worker_threads';
import { DatabaseSync } from 'node:sqlite';
import { nativeIdKey as key } from './native-id.mjs';

const database = new DatabaseSync(workerData.path, { timeout: 1000 });
database.exec(`PRAGMA journal_mode=DELETE; PRAGMA synchronous=EXTRA; PRAGMA fullfsync=ON;
  PRAGMA max_page_count=16384; PRAGMA trusted_schema=OFF;
  CREATE TABLE IF NOT EXISTS effects (
    ordinal INTEGER PRIMARY KEY AUTOINCREMENT,
    request_key TEXT NOT NULL UNIQUE,
    intent TEXT NOT NULL,
    receipt TEXT,
    settled INTEGER NOT NULL DEFAULT 0 CHECK(settled>=0)
  ) STRICT;`);
const decode = row => row ? { intent: JSON.parse(row.intent), receipt: row.receipt === null ? null : JSON.parse(row.receipt), settled: row.settled > 0 } : null;
const inspect = id => decode(database.prepare('SELECT intent, receipt, settled FROM effects WHERE request_key=?').get(key(id)));
const transaction = work => {
  database.exec('BEGIN IMMEDIATE');
  try { const result = work(); database.exec('COMMIT'); return result; }
  catch (error) { database.exec('ROLLBACK'); throw error; }
};
const operations = {
  ready: () => ({ ready: true }),
  record: intent => transaction(() => {
    const existing = inspect(intent.id);
    if (existing) {
      if (JSON.stringify(existing.intent) !== JSON.stringify(intent)) throw Error('journal_request_conflict');
      return existing;
    }
    if (database.prepare('SELECT COUNT(*) AS count FROM effects WHERE settled=0').get().count >= 1024) throw Error('journal_capacity');
    database.prepare('INSERT INTO effects(request_key,intent) VALUES (?,?)').run(key(intent.id), JSON.stringify(intent));
    return inspect(intent.id);
  }),
  inspect,
  unfinished: () => database.prepare('SELECT intent, receipt, settled FROM effects WHERE settled=0 ORDER BY ordinal').all().map(decode),
  recent: () => database.prepare('SELECT intent, receipt, settled FROM effects WHERE settled>0 ORDER BY settled DESC LIMIT 256').all().map(decode),
  settle: ({ id, receipt }) => transaction(() => {
    const existing = inspect(id);
    if (!existing) throw Error('journal_request_unknown');
    if (existing.settled) {
      if (JSON.stringify(existing.receipt) !== JSON.stringify(receipt)) throw Error('journal_outcome_conflict');
      return existing;
    }
    const settled = receipt.released === true && receipt.accountingComplete === true
      ? database.prepare('SELECT COALESCE(MAX(settled),0)+1 AS next FROM effects').get().next : 0;
    database.prepare('UPDATE effects SET receipt=?,settled=? WHERE request_key=?').run(JSON.stringify(receipt), settled, key(id));
    database.exec('DELETE FROM effects WHERE settled>0 AND request_key NOT IN (SELECT request_key FROM effects WHERE settled>0 ORDER BY settled DESC LIMIT 256)');
    return inspect(id);
  }),
  close: () => { database.close(); return { closed: true }; }
};
parentPort.on('message', ({ sequence, operation, payload }) => {
  try { parentPort.postMessage({ sequence, result: operations[operation](payload) }); }
  catch (error) { parentPort.postMessage({ sequence, error: error.message }); }
});
