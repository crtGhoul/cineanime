/**
 * CineAnime 100% Free Encrypted Sync Relay (Cloudflare Worker + KV)
 * Free tier: 100,000 requests/day & 1,000 KV writes/day at $0/month.
 *
 * Zero-knowledge design: the client computes roomId = SHA-256(roomCode) and
 * encrypts/decrypts all watch history, watchlists, and addons with AES-256-GCM
 * BEFORE sending anything to this Worker.
 */

const ROOM_ID_RE = /^[a-f0-9]{32,64}$/i;
const MAX_PAYLOAD_BYTES = 512 * 1024; // 512 KB ceiling per encrypted vault

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin || '*',
    'Access-Control-Allow-Methods': 'GET, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age': '86400',
    'Cache-Control': 'no-store'
  };
}

function jsonResponse(body, status = 200, origin = '*') {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...corsHeaders(origin)
    }
  });
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin') || '*';
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }

    if (!env.SYNC_KV) {
      return jsonResponse({ error: 'SYNC_KV namespace binding is missing' }, 500, origin);
    }

    const url = new URL(request.url);
    if (url.pathname === '/' || url.pathname === '/health') {
      return jsonResponse({ ok: true, service: 'cineanime-sync' }, 200, origin);
    }

    const match = url.pathname.match(/^\/room\/([a-f0-9]{32,64})$/i);
    if (!match || !ROOM_ID_RE.test(match[1])) {
      return jsonResponse({ error: 'Invalid room path. Expected /room/<sha256-hex>' }, 400, origin);
    }

    const key = 'room:' + match[1].toLowerCase();

    if (request.method === 'GET') {
      const raw = await env.SYNC_KV.get(key);
      if (!raw) {
        return jsonResponse({ exists: false, data: null }, 404, origin);
      }
      return new Response(raw, {
        status: 200,
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          ...corsHeaders(origin)
        }
      });
    }

    if (request.method === 'PUT') {
      const raw = await request.text();
      if (!raw || raw.length > MAX_PAYLOAD_BYTES) {
        return jsonResponse({ error: 'Payload empty or exceeds 512 KB limit' }, 413, origin);
      }
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch {
        return jsonResponse({ error: 'Invalid JSON payload' }, 400, origin);
      }
      if (!parsed || typeof parsed.iv !== 'string' || typeof parsed.ciphertext !== 'string') {
        return jsonResponse({ error: 'Expected encrypted { iv, ciphertext } payload' }, 400, origin);
      }
      const record = JSON.stringify({
        v: Number(parsed.v) || 2,
        updatedAt: Number(parsed.updatedAt) || Date.now(),
        iv: parsed.iv,
        ciphertext: parsed.ciphertext
      });
      // Renew a 365-day rolling lease on every write so active vaults persist indefinitely
      // while abandoned rooms expire automatically to protect free KV storage limits.
      await env.SYNC_KV.put(key, record, { expirationTtl: 60 * 60 * 24 * 365 });
      return jsonResponse({ ok: true, updatedAt: Date.now() }, 200, origin);
    }

    if (request.method === 'DELETE') {
      await env.SYNC_KV.delete(key);
      return jsonResponse({ ok: true }, 200, origin);
    }

    return jsonResponse({ error: 'Method not allowed' }, 405, origin);
  }
};
