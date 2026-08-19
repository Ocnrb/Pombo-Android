// Vector generator for SealedSenderCrypto's SEAL side — reproduces the web's
// dmCrypto seal primitives (ethers ECDH + HKDF-SHA256 + AES-256-GCM) with
// deterministic inputs, so the Kotlin port is locked to the bytes the web
// would produce. The open side is already locked by the port-brief vectors.
import { createRequire } from 'module';
import { hkdfSync, createCipheriv } from 'crypto';
const require = createRequire('c:/Users/v-f-r/Desktop/Pombo/Pombo Web/package.json');
const { ethers } = require('ethers');

const senderPk = '0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98';
const recipientPk = '0x2222222222222222222222222222222222222222222222222222222222222222';
const recipientAddress = new ethers.Wallet(recipientPk).address;
const recipientPub = new ethers.SigningKey(recipientPk).compressedPublicKey;
const ephemeralPk = '0x3333333333333333333333333333333333333333333333333333333333333333';
const epk = new ethers.SigningKey(ephemeralPk).compressedPublicKey;
const iv12 = Buffer.alloc(12, 0x07);

function sharedX(privHex, pubHex) {
    const sk = new ethers.SigningKey(privHex);
    return Buffer.from(ethers.getBytes(sk.computeSharedSecret(pubHex)).slice(1, 33));
}

function aesGcm(key, iv, pt) {
    const c = createCipheriv('aes-256-gcm', key, iv);
    return Buffer.concat([c.update(pt), c.final(), c.getAuthTag()]);
}

// v2 sealed key (dmCrypto._sealedKey): HKDF(x-coord, salt v2, info aes-256-gcm)
const sealedKey = Buffer.from(hkdfSync('sha256',
    sharedX(ephemeralPk, recipientPub),
    Buffer.from('pombo-dm-sealed-v2'), Buffer.from('aes-256-gcm'), 32));

// v1 pair key (dmCrypto.deriveSharedKey / bridge _dmKey): same shape, v1 salt
const pairKey = Buffer.from(hkdfSync('sha256',
    sharedX(recipientPk, new ethers.SigningKey(senderPk).compressedPublicKey),
    Buffer.from('pombo-dm-e2e-v1'), Buffer.from('aes-256-gcm'), 32));

// Bind digest + proof (dmCrypto.bindDigest + SigningKey.sign)
const digest = ethers.keccak256(ethers.toUtf8Bytes(
    'POMBO_DM_BIND_V2|' + recipientAddress.toLowerCase() + '|' + epk));
const proof = new ethers.SigningKey(senderPk).sign(digest).serialized;

// Binary wire (bridge _sealBinaryWith): [0x02][epk:33][iv:12][ct(proof65 ‖ payload)]
const payload = Buffer.from([0, 1, 2, 3, 4, 250, 251, 252, 253, 254, 255]);
const binaryPt = Buffer.concat([Buffer.from(ethers.getBytes(proof)), payload]);
const binaryCt = aesGcm(sealedKey, iv12, binaryPt);
const wire = Buffer.concat([Buffer.from([0x02]), Buffer.from(ethers.getBytes(epk)), iv12, binaryCt]);

// v1 chunk row (bridgePublishStorageChunk dm path): [iv:12][ct+tag]
const chunkPayload = Buffer.from('pombo chunk vector', 'utf8');
const chunkRow = Buffer.concat([iv12, aesGcm(pairKey, iv12, chunkPayload)]);

console.log(JSON.stringify({
    senderPk, senderAddress: new ethers.Wallet(senderPk).address,
    recipientPk, recipientAddress, recipientPub,
    ephemeralPk, epk,
    sealedKey: '0x' + sealedKey.toString('hex'),
    pairKey: '0x' + pairKey.toString('hex'),
    bindDigest: digest,
    proof,
    binaryPayload: '0x' + payload.toString('hex'),
    binaryWire: '0x' + wire.toString('hex'),
    chunkPayload: '0x' + chunkPayload.toString('hex'),
    chunkRow: '0x' + chunkRow.toString('hex')
}, null, 2));
