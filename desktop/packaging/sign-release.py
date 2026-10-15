#!/usr/bin/env python3
"""SHA256SUMS for a desktop release's files, and SHA256SUMS.sig: the BIP-340 signature of
SHA-256(SHA256SUMS) by the release key the app pins (UpdateCheck.RELEASE_PUBKEY).
Usage: sign-release.py OUTDIR FILE...   (key from ~/.config/kilowallet-desktop/release-key.env)"""
import hashlib, os, sys
import coincurve

out, files = sys.argv[1], sys.argv[2:]
env = dict(l.strip().split('=', 1) for l in open(os.path.expanduser('~/.config/kilowallet-desktop/release-key.env')) if '=' in l and not l.startswith('#'))
sk = coincurve.PrivateKey(bytes.fromhex(env['RELEASE_SECRET']))
assert sk.public_key_xonly.format().hex() == env['RELEASE_PUBKEY']
lines = []
for f in files:
    h = hashlib.sha256()
    with open(f, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''): h.update(chunk)
    lines.append(f"{h.hexdigest()}  {os.path.basename(f)}\n")
sums = ''.join(lines).encode()
sig = sk.sign_schnorr(hashlib.sha256(sums).digest(), os.urandom(32))
assert coincurve.PublicKeyXOnly(bytes.fromhex(env['RELEASE_PUBKEY'])).verify(sig, hashlib.sha256(sums).digest())
open(os.path.join(out, 'SHA256SUMS'), 'wb').write(sums)
open(os.path.join(out, 'SHA256SUMS.sig'), 'w').write(sig.hex() + '\n')
print(sums.decode(), end=''); print('signed by', env['RELEASE_PUBKEY'])
