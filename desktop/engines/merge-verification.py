#!/usr/bin/env python3
"""Adds the components of a freshly written verification-metadata.xml to the committed one
without dropping any (Gradle's --write-verification-metadata forgets what a run did not use,
such as Android's own dependencies when only the desktop module was built)."""
import re, sys
old, new, out = sys.argv[1:4]
o, n = open(old).read(), open(new).read()
comp = re.compile(r'( *<component group="([^"]+)" name="([^"]+)" version="([^"]+)">\n.*?</component>\n)', re.S)
art = re.compile(r'( *<artifact name="([^"]+)">\n.*?</artifact>\n)', re.S)
ocomp = {(g, nm, v): blk for blk, g, nm, v in comp.findall(o)}
added = 0
for blk, g, nm, v in comp.findall(n):
    key = (g, nm, v)
    if key not in ocomp:
        ocomp[key] = blk; added += 1
    else:
        have = {a for _, a in art.findall(ocomp[key])}
        extra = [b for b, a in art.findall(blk) if a not in have]
        if extra:
            ocomp[key] = ocomp[key].replace('      </component>\n', ''.join(extra) + '      </component>\n'); added += 1
start = o.index('<components>') + len('<components>\n'); end = o.index('   </components>')
body = ''.join(ocomp[k] for k in sorted(ocomp))
open(out, 'w').write(o[:start] + body + o[end:])
print('components added or extended:', added)
