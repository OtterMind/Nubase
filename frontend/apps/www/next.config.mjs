import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ['@nubase/ui', '@nubase/config'],
  // Self-contained server bundle for `node server.js` deploys (traced from the monorepo root).
  output: 'standalone',
  experimental: {
    outputFileTracingRoot: path.join(__dirname, '../../'),
  },
};

export default nextConfig;
