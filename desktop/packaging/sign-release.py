#!/usr/bin/env python3
"""SHA256SUMS for a desktop release's files, and SHA256SUMS.sig: the BIP-340 signature of
SHA-256(SHA256SUMS) by the release key the app pins (UpdateCheck.RELEASE_PUBKEY).
Usage: sign-release.py OUTDIR FILE...   (key from ~/.config/kilowallet-desktop/release-key.env)"""
import hashlib, os, sys

# BIP-340 Schnorr in plain Python (the reference implementation's math): coincurve has no wheel
# for every Python, and signing one hash per release needs no speed.
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798, 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
def _add(a, b):
    if a is None: return b
    if b is None: return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0: return None
    l = (3 * a[0] * a[0] * pow(2 * a[1], P - 2, P)) % P if a == b else ((b[1] - a[1]) * pow(b[0] - a[0], P - 2, P)) % P
    x = (l * l - a[0] - b[0]) % P
    return (x, (l * (a[0] - x) - a[1]) % P)
def _mul(p, n):
    r = None
    for i in range(256):
        if (n >> i) & 1: r = _add(r, p)
        p = _add(p, p)
    return r
def _th(tag, m): t = hashlib.sha256(tag.encode()).digest(); return hashlib.sha256(t + t + m).digest()
def _b(x): return x.to_bytes(32, 'big')
def _lift(x):
    y = pow((pow(x, 3, P) + 7) % P, (P + 1) // 4, P)
    return (x, y if y % 2 == 0 else P - y)
def xonly(sk): return _b(_mul(G, sk)[0])
def sign(sk, msg, aux):
    pt = _mul(G, sk); d = sk if pt[1] % 2 == 0 else N - sk
    t = bytes(a ^ b for a, b in zip(_b(d), _th('BIP0340/aux', aux)))
    k0 = int.from_bytes(_th('BIP0340/nonce', t + _b(pt[0]) + msg), 'big') % N
    R = _mul(G, k0); k = k0 if R[1] % 2 == 0 else N - k0
    e = int.from_bytes(_th('BIP0340/challenge', _b(R[0]) + _b(pt[0]) + msg), 'big') % N
    return _b(R[0]) + _b((k + e * d) % N)
def verify(pub, msg, sig):
    Pt = _lift(int.from_bytes(pub, 'big')); r = int.from_bytes(sig[:32], 'big'); s = int.from_bytes(sig[32:], 'big')
    e = int.from_bytes(_th('BIP0340/challenge', sig[:32] + pub + msg), 'big') % N
    R = _add(_mul(G, s), _mul(Pt, N - e))
    return R is not None and R[1] % 2 == 0 and R[0] == r

out, files = sys.argv[1], sys.argv[2:]
env = dict(l.strip().split('=', 1) for l in open(os.path.expanduser('~/.config/kilowallet-desktop/release-key.env')) if '=' in l and not l.startswith('#'))
sk = int.from_bytes(bytes.fromhex(env['RELEASE_SECRET']), 'big')
assert xonly(sk).hex() == env['RELEASE_PUBKEY']
lines = []
for f in files:
    h = hashlib.sha256()
    with open(f, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''): h.update(chunk)
    lines.append(f"{h.hexdigest()}  {os.path.basename(f)}\n")
sums = ''.join(lines).encode()
sig = sign(sk, hashlib.sha256(sums).digest(), os.urandom(32))
assert verify(bytes.fromhex(env['RELEASE_PUBKEY']), hashlib.sha256(sums).digest(), sig)
open(os.path.join(out, 'SHA256SUMS'), 'wb').write(sums)
open(os.path.join(out, 'SHA256SUMS.sig'), 'w').write(sig.hex() + '\n')
print(sums.decode(), end=''); print('signed by', env['RELEASE_PUBKEY'])
