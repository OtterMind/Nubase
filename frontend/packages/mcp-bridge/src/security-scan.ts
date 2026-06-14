import { basename } from 'node:path';

export interface SecurityFinding {
  file: string;
  rule: string;
  message: string;
}

const SECRET_PATTERNS: Array<{ rule: string; pattern: RegExp; message: string }> = [
  {
    rule: 'private_key',
    pattern: /-----BEGIN (?:RSA |EC |OPENSSH |DSA |)?PRIVATE KEY-----/,
    message: 'Private key material must not be uploaded.',
  },
  {
    rule: 'nubase_service_role',
    pattern: /(?:NUBASE_PROJECT_KEY|NUBASE_API_KEY|SUPABASE_SERVICE_ROLE_KEY|service[_-]?role)[\w\s"'`:=.-]{0,40}(?:eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,})/i,
    message: 'A service-role-looking JWT was found.',
  },
  {
    rule: 'openai_api_key',
    pattern: /\bsk-[A-Za-z0-9_-]{24,}\b/,
    message: 'An OpenAI-style API key was found.',
  },
  {
    rule: 'anthropic_api_key',
    pattern: /\bsk-ant-[A-Za-z0-9_-]{20,}\b/,
    message: 'An Anthropic-style API key was found.',
  },
  {
    rule: 'aws_access_key',
    pattern: /\bAKIA[0-9A-Z]{16}\b/,
    message: 'An AWS access key id was found.',
  },
];

const SENSITIVE_FILENAMES = new Set([
  '.env',
  '.env.local',
  '.env.production',
  '.env.development',
  '.npmrc',
  '.pypirc',
]);

export function scanUploadContent(file: string, content: Buffer | string): SecurityFinding[] {
  const findings: SecurityFinding[] = [];
  const name = basename(file);
  if (SENSITIVE_FILENAMES.has(name)) {
    findings.push({
      file,
      rule: 'sensitive_filename',
      message: `${name} should not be uploaded.`,
    });
  }
  const text = typeof content === 'string' ? content : content.toString('utf8');
  for (const rule of SECRET_PATTERNS) {
    if (rule.pattern.test(text)) {
      findings.push({ file, rule: rule.rule, message: rule.message });
    }
  }
  return findings;
}

export async function assertNoSecurityFindings(scan: () => Promise<SecurityFinding[]> | SecurityFinding[]) {
  const findings = await scan();
  if (findings.length === 0) return;
  const summary = findings
    .slice(0, 8)
    .map((finding) => `${finding.file}: ${finding.rule} (${finding.message})`)
    .join('; ');
  const more = findings.length > 8 ? `; +${findings.length - 8} more` : '';
  throw new Error(`Security scan blocked upload: ${summary}${more}`);
}
