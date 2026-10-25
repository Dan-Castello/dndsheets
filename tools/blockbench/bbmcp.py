"""Tiny stdio MCP client for the headless bbmodel server (the session did not load the MCP tools).
Usage: python bbmcp.py list | call <tool> <json-args-file>"""
import json, subprocess, sys, os, threading, queue

ROOT = r'C:\Users\DanCastello\Documents\GitHub\dndsheets\tools\blockbench'
CMD = ['npx.cmd', '-y', 'github:jasonjgardner/blockbench-mcp-plugin', '--root', ROOT]

class Client:
    def __init__(self):
        self.p = subprocess.Popen(CMD, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8')
        self.q = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.n = 0
        self.rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'cc', 'version': '1'}})
        self.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})

    def _read(self):
        for line in self.p.stdout:
            line = line.strip()
            if line.startswith('{'):
                self.q.put(json.loads(line))

    def send(self, m):
        self.p.stdin.write(json.dumps(m) + '\n'); self.p.stdin.flush()

    def rpc(self, method, params, timeout=300):
        self.n += 1; i = self.n
        self.send({'jsonrpc': '2.0', 'id': i, 'method': method, 'params': params})
        while True:
            m = self.q.get(timeout=timeout)
            if m.get('id') == i:
                return m

    def call(self, tool, args):
        return self.rpc('tools/call', {'name': tool, 'arguments': args})

    def close(self):
        self.p.stdin.close(); self.p.terminate()

if __name__ == '__main__':
    c = Client()
    try:
        if sys.argv[1] == 'list':
            r = c.rpc('tools/list', {})
            for t in r['result']['tools']:
                print(t['name'], '-', (t.get('description') or '').split('\n')[0][:110])
        elif sys.argv[1] == 'schema':
            r = c.rpc('tools/list', {})
            for t in r['result']['tools']:
                if t['name'] == sys.argv[2]:
                    print(json.dumps(t, indent=1)[:int(sys.argv[3]) if len(sys.argv) > 3 else 6000])
        else:
            args = json.load(open(sys.argv[3], encoding='utf-8'))
            r = c.call(sys.argv[2], args)
            out = json.dumps(r, indent=1)
            print(out[:6000])
    finally:
        c.close()
