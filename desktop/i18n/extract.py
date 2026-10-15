#!/usr/bin/env python3
"""The shared code's user-facing strings, as templates ({0}, {1}… where Kotlin interpolates),
for the desktop's translation table (es.json). Prints one JSON list of templates."""
import json, re, sys, glob, os

SRC = os.path.join(os.path.dirname(__file__), '../../app/src/main/java/com/kilombino/pyblockwatch')

def literals(s):
    i, n = 0, len(s)
    while i < n:
        if s.startswith('"""', i):
            j = s.find('"""', i + 3); i = j + 3 if j >= 0 else n; continue
        if s.startswith('//', i):
            j = s.find('\n', i); i = j if j >= 0 else n; continue
        if s.startswith('/*', i):
            j = s.find('*/', i); i = j + 2 if j >= 0 else n; continue
        if s[i] == "'":
            j = i + 1
            while j < n and s[j] != "'":
                j += 2 if s[j] == '\\' else 1
            i = j + 1; continue
        if s[i] == '"':
            out, k, j = [], 0, i + 1
            while j < n and s[j] != '"':
                c = s[j]
                if c == '\\':
                    esc = s[j + 1]
                    out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}.get(esc, esc)); j += 2
                elif c == '$' and j + 1 < n and s[j + 1] == '{':
                    depth, j = 1, j + 2
                    while j < n and depth:
                        if s[j] == '{': depth += 1
                        elif s[j] == '}': depth -= 1
                        elif s[j] == '"':  # nested string inside the expression
                            j += 1
                            while j < n and s[j] != '"': j += 2 if s[j] == '\\' else 1
                        j += 1
                    out.append('{%d}' % k); k += 1
                elif c == '$' and j + 1 < n and (s[j + 1].isalpha() or s[j + 1] == '_'):
                    j += 1
                    while j < n and (s[j].isalnum() or s[j] == '_'): j += 1
                    out.append('{%d}' % k); k += 1
                elif c == '\n':
                    break
                else:
                    out.append(c); j += 1
            yield ''.join(out)
            i = j + 1; continue
        i += 1

def user_facing(t):
    if len(t) < 2 or t.startswith(('http', 'android.', 'com.', 'application/', 'wss:', 'ws:')): return False
    if re.fullmatch(r'[a-z0-9_.:/\-]+', t): return False          # keys, ids, paths
    if re.search(r'[A-Za-z]{2,} [A-Za-z]{2,}', t): return True     # words
    if re.fullmatch(r'[A-Z][A-Za-z]{2,}[.!?…]?', t): return True    # "Send", "Cancel"
    if re.fullmatch(r'[A-Z]{3,}( [A-Z0-9]+)*[.!?…]?', t): return True  # "SEND", "CHECK NOW"
    if re.fullmatch(r"[^a-z]*[A-Z]{3,}[^a-z]*", t) and re.search(r'[A-Z]{2,}[ \-(][A-Z(]', t): return True  # "NEW (DICE)", "WATCH-ONLY WALLET"
    return False

seen = {}
for f in sorted(glob.glob(SRC + '/**/*.kt', recursive=True)):
    if '/widget/' in f: continue
    for t in literals(open(f, encoding='utf-8').read()):
        if user_facing(t): seen.setdefault(t, os.path.relpath(f, SRC))
json.dump([{'en': k, 'file': v} for k, v in seen.items()], sys.stdout, ensure_ascii=False, indent=0)
