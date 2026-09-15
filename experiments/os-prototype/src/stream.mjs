import { spawn } from 'node:child_process';

/** Trusted transport. A single long-lived wrapper avoids per-request JVM startup. */
export class WrapperStream {
  constructor(cli) {
    this.sequence = 0;
    this.pending = new Map();
    this.buffer = '';
    this.failed = null;
    this.closing = false;
    // Keep terminal Ctrl-C on the host; it needs the wrapper alive to cancel its work.
    this.child = spawn(cli, ['agent', 'tools', 'stream'], { stdio: ['pipe', 'pipe', 'pipe'], detached: true });
    this.child.stdout.setEncoding('utf8');
    this.child.on('error', error => this.fail(error));
    this.child.stdin.on('error', error => this.fail(error));
    this.child.stderr.on('data', () => {});
    this.child.on('exit', code => { if (!this.closing) this.fail(Error(`wrapper_stream_exited: ${code}`)); });
    this.child.stdout.on('data', chunk => {
      try {
        this.buffer += chunk.toString('utf8');
        if (this.buffer.length > 524288) throw Error('wrapper_response_limit');
        let end;
        while ((end = this.buffer.indexOf('\n')) >= 0) {
          const line = this.buffer.slice(0, end).replace(/\r$/, '');
          this.buffer = this.buffer.slice(end + 1);
          if (['status: ok', 'command: agent tools stream', 'protocol: airicraft-tools-jsonl-v1'].includes(line)) continue;
          if (!line.startsWith('response: ')) throw Error(`unexpected_wrapper_output: ${line.slice(0, 200)}`);
          const response = JSON.parse(line.slice(10));
          const pending = this.pending.get(response.id);
          if (!pending) throw Error('unknown_wrapper_response');
          clearTimeout(pending.timer);
          this.pending.delete(response.id);
          response.ok ? pending.resolve(response.payload) : pending.reject(Error(`wrapper_request_failed: ${response.error}`));
        }
      } catch (error) { this.fail(error); }
    });
  }
  fail(error) {
    this.failed ??= error;
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(this.failed); }
    this.pending.clear();
    this.child.kill('SIGTERM');
  }
  request(body) {
    if (this.failed) return Promise.reject(this.failed);
    if (this.pending.size >= 8) return Promise.reject(Error('wrapper_pending_limit'));
    const id = String(++this.sequence);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(Error('wrapper_request_timeout')), 25000);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, ...body }) + '\n');
    });
  }
  async close() {
    this.closing = true;
    this.child.stdin.end();
    if (this.child.exitCode !== null || this.child.signalCode !== null) return;
    await new Promise(resolve => {
      const timer = setTimeout(() => { this.child.kill('SIGTERM'); resolve(); }, 2000);
      this.child.once('exit', () => { clearTimeout(timer); resolve(); });
    });
  }
}
