// Vector generator for EthereumSignerTest — run with the web's own ethers so
// the Kotlin signer is locked to what the bridge actually produces today.
import { createRequire } from 'module';
const require = createRequire('c:/Users/v-f-r/Desktop/Pombo/Pombo Web/package.json');
const { ethers } = require('ethers');

const keys = [
    '0x2222222222222222222222222222222222222222222222222222222222222222',
    '0x4444444444444444444444444444444444444444444444444444444444444444',
    '0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98',
];

const digests = [
    '0x' + '11'.repeat(32),
    '0x' + 'fe'.repeat(32),
    ethers.keccak256(ethers.toUtf8Bytes('pombo digest vector')),
    ethers.keccak256(new Uint8Array([0, 1, 2, 3, 255])),
];

// EIP-191 / Streamr magic cases: utf8, empty, binary, and a large binary
// payload (Streamr signs whole piece payloads — length prefix is the BYTE
// length in decimal).
const big = new Uint8Array(1000);
for (let i = 0; i < big.length; i++) big[i] = (i * 7 + 3) & 0xff;
const messages = [
    { name: 'utf8', bytes: ethers.toUtf8Bytes('olá pombo — assina isto') },
    { name: 'empty', bytes: new Uint8Array(0) },
    { name: 'binary', bytes: new Uint8Array([0, 159, 146, 150, 255, 1]) },
    { name: 'big1000', bytes: big },
];

const out = { keys: [], digestVectors: [], messageVectors: [] };

for (const pk of keys) {
    const sk = new ethers.SigningKey(pk);
    out.keys.push({
        privateKey: pk,
        compressedPublicKey: sk.compressedPublicKey,
        address: ethers.computeAddress(pk),
    });
    for (const d of digests) {
        out.digestVectors.push({ privateKey: pk, digest: d, signature: sk.sign(d).serialized });
    }
    for (const m of messages) {
        const digest = ethers.hashMessage(m.bytes);
        out.messageVectors.push({
            privateKey: pk,
            name: m.name,
            payloadHex: ethers.hexlify(m.bytes),
            hash: digest,
            signature: sk.sign(digest).serialized,
        });
    }
}

console.log(JSON.stringify(out, null, 2));
