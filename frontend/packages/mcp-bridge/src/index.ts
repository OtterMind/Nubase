#!/usr/bin/env node
import { defaultConfigPath } from './auth-config.js';
import { authorize, parseAuthorizeArgs } from './authorize.js';
import { loadConfigAsync } from './config.js';
import { installSkills, parseInstallArgs } from './install-skills.js';
import { runAssetsCommand } from './assets.js';
import { runCronCommand } from './cron.js';
import { runDeployAppCommand } from './deploy-app.js';
import { resolveExitCode, runFunctionsCommand } from './functions.js';
import { McpStdioServer } from './mcp-stdio.js';
import { NubaseClient } from './nubase-client.js';
import { callTool, TOOLS } from './tools.js';

const CLI_VERSION = '0.2.0';

if (process.argv[2] === 'install-skills') {
  const options = parseInstallArgs(process.argv.slice(3));
  const installed = await installSkills(options);
  for (const file of installed) {
    if (file.endsWith('.mcp.json')) {
      console.error(`Registered Nubase MCP server config: ${file}`);
    } else if (file.endsWith('.codex/config.toml')) {
      console.error(`Registered Nubase Codex MCP config: ${file}`);
    } else if (file.endsWith('.gitignore')) {
      console.error(`Ensured Nubase local config is ignored by git: ${file}`);
    } else if (file.includes(`${defaultPathSep()}.nubase${defaultPathSep()}mcp-bridge${defaultPathSep()}`)) {
      console.error(`Installed Nubase local MCP bridge: ${file}`);
    } else {
      console.error(`Installed Nubase skill: ${file}`);
    }
  }
  if (options.authorize) {
    console.error('');
    const saved = await authorize(parseAuthorizeArgs(options.authArgs));
    console.error(`Nubase CLI authorized for ${saved.projectName || saved.projectRef || 'selected project'}.`);
    console.error(`Config saved to ${defaultConfigPath()}`);
  } else {
    console.error('');
    console.error('Authorization skipped. Run nubase_cli authorize when you are ready.');
  }
  process.exit(0);
}

if (process.argv[2] === 'authorize') {
  const saved = await authorize(parseAuthorizeArgs(process.argv.slice(3)));
  console.error(`Nubase CLI authorized for ${saved.projectName || saved.projectRef || 'selected project'}.`);
  console.error(`Config saved to ${defaultConfigPath()}`);
  process.exit(0);
}

const config = await loadConfigAsync();
const client = new NubaseClient(config);

if (process.argv[2] === 'functions') {
  try {
    const result = await runFunctionsCommand(process.argv.slice(3), config, client);
    console.log(JSON.stringify(result, null, 2));
    process.exit(resolveExitCode(result));
  } catch (err) {
    console.error(JSON.stringify({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    }, null, 2));
    process.exit(1);
  }
}

if (process.argv[2] === 'cron') {
  try {
    const result = await runCronCommand(process.argv.slice(3), config, client);
    console.log(JSON.stringify(result, null, 2));
    process.exit(resolveExitCode(result));
  } catch (err) {
    console.error(JSON.stringify({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    }, null, 2));
    process.exit(1);
  }
}

if (process.argv[2] === 'assets') {
  try {
    const result = await runAssetsCommand(process.argv.slice(3), config, client);
    console.log(JSON.stringify(result, null, 2));
    process.exit(resolveExitCode(result));
  } catch (err) {
    console.error(JSON.stringify({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    }, null, 2));
    process.exit(1);
  }
}

if (process.argv[2] === 'app') {
  try {
    const result = await runDeployAppCommand(process.argv.slice(3), config, client);
    console.log(JSON.stringify(result, null, 2));
    process.exit(resolveExitCode(result));
  } catch (err) {
    console.error(JSON.stringify({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    }, null, 2));
    process.exit(1);
  }
}

const server = new McpStdioServer(async (request) => {
  switch (request.method) {
    case 'initialize':
      return {
        protocolVersion: request.params?.protocolVersion ?? '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'nubase_cli', version: CLI_VERSION },
      };
    case 'notifications/initialized':
      return null;
    case 'tools/list':
      return {
        tools: TOOLS.map((tool) => ({
          name: tool.name,
          description: tool.description,
          inputSchema: tool.inputSchema,
        })),
      };
    case 'tools/call': {
      const name = request.params?.name;
      const args = request.params?.arguments ?? {};
      const result = await callTool(name, args, config, client);
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(result, null, 2),
          },
        ],
      };
    }
    default:
      throw new Error(`Unsupported method: ${request.method}`);
  }
});

server.start();

function defaultPathSep() {
  return process.platform === 'win32' ? '\\' : '/';
}
