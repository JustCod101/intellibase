#!/usr/bin/env node
import crypto from 'node:crypto';

const secretBase64 = process.env.JWT_SECRET_BASE64
  || 'Y2hhbmdlLXRoaXMtdG8tYS1sb25nLXNlY3VyZS1rZXktaW4tcHJvZHVjdGlvbi0yMDI2';
const userId = String(process.env.JWT_USER_ID || '91001');
const username = process.env.JWT_USERNAME || 'benchmark-user';
const role = process.env.JWT_ROLE || 'ADMIN';
const tenantId = Number(process.env.JWT_TENANT_ID || userId);
const ttlSeconds = Number(process.env.JWT_TTL_SECONDS || 24 * 60 * 60);
const now = Math.floor(Date.now() / 1000);

function base64url(input) {
  return Buffer.from(input)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

const header = { alg: 'HS256', typ: 'JWT' };
const payload = {
  sub: userId,
  username,
  role,
  tenantId,
  iat: now,
  exp: now + ttlSeconds,
};
const encodedHeader = base64url(JSON.stringify(header));
const encodedPayload = base64url(JSON.stringify(payload));
const signingInput = `${encodedHeader}.${encodedPayload}`;
const signature = crypto
  .createHmac('sha256', Buffer.from(secretBase64, 'base64'))
  .update(signingInput)
  .digest('base64')
  .replace(/=/g, '')
  .replace(/\+/g, '-')
  .replace(/\//g, '_');

console.log(`${signingInput}.${signature}`);
