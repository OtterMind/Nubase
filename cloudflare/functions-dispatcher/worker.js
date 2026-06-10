export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split('/').filter(Boolean);
    const projectRef = request.headers.get('x-nubase-project-ref') || parts[0];
    const functionSlug = request.headers.get('x-nubase-function-slug') || parts[1];
    const deploymentId = request.headers.get('x-nubase-deployment-id');

    if (!projectRef || !functionSlug || !deploymentId) {
      return json({ error: 'missing_function_context' }, 400);
    }

    const signatureOk = await verifySignature(request, env);
    if (!signatureOk) {
      return json({ error: 'invalid_signature' }, 401);
    }

    const target = env.NUBASE_DISPATCH.get(deploymentId);
    const suffix = '/' + parts.slice(2).join('/');
    const targetUrl = new URL(request.url);
    targetUrl.pathname = suffix === '/' ? '/' : suffix;

    const forwarded = new Request(targetUrl.toString(), request);
    return target.fetch(forwarded);
  },
};

async function verifySignature(request, env) {
  const secret = env.NUBASE_DISPATCHER_SECRET;
  const signature = request.headers.get('x-nubase-signature');
  const timestamp = request.headers.get('x-nubase-timestamp');
  const requestId = request.headers.get('x-nubase-request-id') || '';
  const projectRef = request.headers.get('x-nubase-project-ref') || '';
  const functionSlug = request.headers.get('x-nubase-function-slug') || '';

  if (!secret || !signature || !timestamp) return false;

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > 300) return false;

  const body = await request.clone().arrayBuffer();
  const hash = await sha256Hex(body);
  const url = new URL(request.url);
  const payload = [
    requestId,
    projectRef,
    functionSlug,
    request.method.toUpperCase(),
    url.pathname.replace(/^\/[^/]+\/[^/]+/, '') || '',
    url.search ? url.search.slice(1) : '',
    timestamp,
    hash,
  ].join('\n');
  const expected = await hmacHex(secret, payload);
  return timingSafeEqual(signature, expected);
}

async function sha256Hex(buffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return hex(digest);
}

async function hmacHex(secret, payload) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  return hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(payload)));
}

function hex(buffer) {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let out = 0;
  for (let i = 0; i < a.length; i += 1) out |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return out === 0;
}

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}
