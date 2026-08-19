// Vector generator for AccountBackup — a deterministic pombo-account-backup
// v1 file built with the web's own primitives (ethers Keystore V3 writer +
// node scrypt + AES-256-GCM), so the Kotlin port is locked to files the web
// produces and vice versa. Low scrypt N keeps the unit tests fast; real files
// carry their params, so the reader honors any cost.
import { createRequire } from 'module';
import { scryptSync, createCipheriv } from 'crypto';
const require = createRequire('c:/Users/v-f-r/Desktop/Pombo/Pombo Web/package.json');
const { ethers } = require('ethers');

const privateKey = '0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98';
const address = ethers.computeAddress(privateKey);
const password = 'pombo-backup-vector';
const N = 4096, r = 8, p = 1, dkLen = 32;

// Keystore V3 with pinned salt/iv/uuid — ethers accepts them via options.
const ksSalt = '0x' + 'aa'.repeat(32);
const ksIv = '0x' + 'bb'.repeat(16);
const uuid = '0x' + 'cc'.repeat(16);
const keystore = JSON.parse(await ethers.encryptKeystoreJson(
    { address, privateKey }, password,
    { scrypt: { N, r, p }, salt: ksSalt, iv: ksIv, uuid }
));

// encryptedData: the bridge exportBackup container (scrypt + AES-256-GCM).
const dataSalt = Buffer.alloc(32, 0xdd);
const dataIv = Buffer.alloc(16, 0xee);
const payload = {
    version: 1,
    exportedAt: '2026-08-19T00:00:00.000Z',
    address,
    data: { channels: [{ streamId: 'x/chan-1', name: 'Vector Channel' }], username: 'vector' },
    imageBlobs: [{ imageId: 'img1', streamId: 'x/chan-1', data: 'data:image/png;base64,AAAA' }]
};
const dk = scryptSync(password, dataSalt, dkLen, { N, r, p, maxmem: 512 * 1024 * 1024 });
const cipher = createCipheriv('aes-256-gcm', dk, dataIv);
const pt = Buffer.from(JSON.stringify(payload), 'utf8');
const ct = Buffer.concat([cipher.update(pt), cipher.final(), cipher.getAuthTag()]);

const backup = {
    format: 'pombo-account-backup',
    version: 1,
    exportedAt: payload.exportedAt,
    keystore,
    encryptedData: {
        kdf: 'scrypt',
        kdfparams: { n: N, r, p, dklen: dkLen, salt: '0x' + dataSalt.toString('hex') },
        cipher: 'aes-256-gcm',
        cipherparams: { iv: '0x' + dataIv.toString('hex') },
        ciphertext: '0x' + ct.toString('hex')
    }
};

console.log(JSON.stringify({
    password,
    privateKey,
    address,
    backup,
    // The export side re-derives these exact bytes with the same fixed inputs.
    keystoreCiphertext: keystore.Crypto ? keystore.Crypto.ciphertext : keystore.crypto.ciphertext,
    keystoreMac: keystore.Crypto ? keystore.Crypto.mac : keystore.crypto.mac,
    dataCiphertext: backup.encryptedData.ciphertext
}, null, 2));
