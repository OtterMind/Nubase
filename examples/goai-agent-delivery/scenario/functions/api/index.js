function response(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname.endsWith('/health')) {
      return response({
        ok: true,
        service: 'goai-delivery-api',
        mode: 'contest-sandbox',
      });
    }

    return response({ ok: false, error: 'not_found' }, 404);
  },
};
