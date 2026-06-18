'use client';

import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { Languages } from 'lucide-react';

export const LOCALE_KEY = 'nubase.studio.locale';

export type Locale = 'en' | 'zh-CN';

const messages = {
  en: {
    'language.switchToChinese': 'Switch language to Chinese',
    'language.switchToEnglish': 'Switch language to English',
    'language.english': 'English',
    'language.chinese': '中文',

    'auth.layout.copyright': '© {year} nubase. All rights reserved.',
    'auth.layout.quote': '“A Postgres-first backend that stays out of the way. We ship features, not glue code.”',
    'auth.layout.quoteBy': '— a happy team using nubase',
    'auth.email': 'Email',
    'auth.password': 'Password',
    'auth.fullName': 'Full name',
    'auth.optional': 'Optional',
    'auth.verificationCode': 'Verification code',
    'auth.confirmEmail.title': 'Confirm your email',
    'auth.confirmEmail.body': 'Enter the 6-digit code we emailed to {email}.',
    'auth.confirmEmail.sent': 'We sent a 6-digit code to {email}.',
    'auth.confirmEmail.resent': 'A new code is on its way.',
    'auth.confirmEmail.verify': 'Verify & continue',
    'auth.confirmEmail.verifying': 'Verifying…',
    'auth.confirmEmail.resend': "Didn't get it? Resend code",
    'auth.error.thirdParty': 'Third-party sign in failed. Please try again.',
    'auth.error.session': 'Sign in session could not be established. Please try again.',
    'auth.error.google': 'Google sign in failed.',
    'auth.error.signIn': 'Sign in failed.',
    'auth.error.signUp': 'Sign up failed.',
    'auth.error.verify': 'Verification failed.',
    'auth.login.title': 'Sign in to Studio',
    'auth.login.subtitle': 'Manage your nubase projects, databases and tenants.',
    'auth.login.google': 'Continue with Google',
    'auth.login.github': 'Continue with GitHub',
    'auth.login.or': 'or',
    'auth.login.submit': 'Sign in',
    'auth.login.submitting': 'Signing in…',
    'auth.login.noAccount': "Don't have an account?",
    'auth.login.signUp': 'Sign up',
    'auth.signup.closedTitle': 'Sign-ups are closed',
    'auth.signup.closedBody': "This workspace doesn't accept public sign-ups. Ask an existing super admin to invite you via the project members or platform users page.",
    'auth.signup.back': 'Back to sign in',
    'auth.signup.title': 'Create your Studio account',
    'auth.signup.subtitle': 'Platform admin access to manage all your nubase projects.',
    'auth.signup.passwordHelp': 'At least 8 characters.',
    'auth.signup.submit': 'Create account',
    'auth.signup.submitting': 'Creating account…',
    'auth.signup.hasAccount': 'Already have an account?',
    'auth.signup.signIn': 'Sign in',

    'shell.nav.allProjects': 'All projects',
    'shell.nav.newProject': 'New project',
    'shell.nav.account': 'Account',
    'shell.nav.home': 'Home',
    'shell.nav.tableEditor': 'Table Editor',
    'shell.nav.sqlEditor': 'SQL Editor',
    'shell.nav.authentication': 'Authentication',
    'shell.nav.storage': 'Storage',
    'shell.nav.assets': 'Assets',
    'shell.nav.memory': 'Memory',
    'shell.nav.aiGateway': 'AI Gateway',
    'shell.nav.functions': 'Functions',
    'shell.nav.cron': 'Cron',
    'shell.nav.connectAgent': 'Connect Agent',
    'shell.nav.logs': 'Logs',
    'shell.nav.settings': 'Settings',
    'shell.section.project': 'Project',
    'shell.section.workspace': 'Workspace',
    'shell.version': 'v0.1 · Self-hosted',
    'shell.expand': 'Expand sidebar',
    'shell.collapse': 'Collapse sidebar',
    'shell.workspace': 'Workspace',
    'shell.local': 'Local',
    'shell.ready': 'Ready',
    'shell.switchProject': 'Switch project',
    'shell.noProjects': 'No projects available.',
    'shell.projectWarning': 'Project is {status} — the underlying database is not provisioned yet. Database, Auth and Storage pages will be empty.',

    'theme.light': 'Switch to light theme',
    'theme.dark': 'Switch to dark theme',
    'user.menu': 'Account menu',
    'user.signedIn': 'Signed in',
    'user.platformUsers': 'Platform users',
    'user.platformSettings': 'Platform settings',
    'user.signOut': 'Sign out',

    'projects.scope': 'workspace',
    'projects.superAdmin': 'super admin',
    'projects.titleAll': 'All projects',
    'projects.titleOwn': 'Your projects',
    'projects.subtitle': 'Select a project to manage database, auth, storage, and memory. The list is filtered to the projects your platform key can access.',
    'projects.new': 'New project',
    'projects.total': 'Total',
    'projects.ready': 'Ready',
    'projects.pending': 'Pending',
    'projects.failed': 'Needs attention',
    'projects.search': 'Search projects by name, ref, or description',
    'projects.gridView': 'Grid view',
    'projects.listView': 'List view',
    'projects.grid': 'Grid',
    'projects.list': 'List',
    'projects.loadError': 'Failed to load projects.',
    'projects.emptyTitle': 'No projects yet',
    'projects.emptySearchTitle': 'No projects match your search',
    'projects.emptyBody': 'Create a project configuration to begin.',
    'projects.emptySearchBody': 'Try a different name, ref, or description.',
    'projects.createFirst': 'Create your first project',
    'projects.noDescription': 'No description provided.',
    'projects.schema': 'schema',
    'projects.project': 'Project',
    'projects.reference': 'Reference',
    'projects.status': 'Status',
    'projects.showing': 'Showing {start}-{end} of {total}',
    'projects.prev': 'Prev',
    'projects.next': 'Next',
    'projects.page': 'Page {page} of {total}',

    'newProject.title': 'New project',
    'newProject.subtitle': 'Save a project configuration. The Postgres database is provisioned automatically on the next screen.',
    'newProject.name': 'Project name',
    'newProject.namePlaceholder': 'My CRM',
    'newProject.nameHelp': 'Shown in the dashboard.',
    'newProject.ref': 'Reference',
    'newProject.refHelp': 'Lower-case, digits and underscores. Used as the API path segment, database name and JWT ref claim. Cannot be changed later.',
    'newProject.description': 'Description (optional)',
    'newProject.descriptionPlaceholder': 'Internal CRM data',
    'newProject.region': 'Region',
    'newProject.regionHelp': 'Visual only for now — self-hosted nubase runs against a single Postgres host.',
    'newProject.cancel': 'Cancel',
    'newProject.create': 'Create project',
    'newProject.creating': 'Creating…',
    'newProject.created': 'Project created',
    'newProject.initializing': 'Initializing the database…',
    'newProject.createFailed': 'Create failed',
    'newProject.failed': 'Failed to create project.',

    'account.title': 'Account',
    'account.subtitle': 'Your platform user profile.',
    'account.profile': 'Profile',
    'account.profileDescription': 'Identity used when calling the platform API.',
    'account.fullName': 'Full name',
    'account.role': 'Role',
    'account.userId': 'User ID',
    'account.roleSuper': 'You can see every project in this workspace.',
    'account.roleUser': 'You can only see projects you own or were invited to.',
    'account.security': 'Security',
    'account.securityDescription': "Change your password. We email a confirmation code to verify it's you.",
    'account.currentPassword': 'Current password',
    'account.newPassword': 'New password',
    'account.passwordOtpSent': 'We emailed you a 6-digit confirmation code.',
    'account.passwordOtpFailed': 'Could not start the password change.',
    'account.passwordUpdated': 'Password updated.',
    'account.passwordChangeFailed': 'Could not change the password.',
    'account.sendingCode': 'Sending code…',
    'account.continue': 'Continue',
    'account.confirmationCode': 'Confirmation code',
    'account.updating': 'Updating…',
    'account.changePassword': 'Change password',
    'account.cancel': 'Cancel',
  },
  'zh-CN': {
    'language.switchToChinese': '切换语言为中文',
    'language.switchToEnglish': '切换语言为英文',
    'language.english': 'English',
    'language.chinese': '中文',

    'auth.layout.copyright': '© {year} nubase。保留所有权利。',
    'auth.layout.quote': '“Postgres-first 后端平台，不打扰业务开发。我们交付功能，而不是胶水代码。”',
    'auth.layout.quoteBy': '— 一个正在使用 nubase 的团队',
    'auth.email': '邮箱',
    'auth.password': '密码',
    'auth.fullName': '姓名',
    'auth.optional': '选填',
    'auth.verificationCode': '验证码',
    'auth.confirmEmail.title': '确认邮箱',
    'auth.confirmEmail.body': '输入发送到 {email} 的 6 位验证码。',
    'auth.confirmEmail.sent': '我们已向 {email} 发送 6 位验证码。',
    'auth.confirmEmail.resent': '新的验证码已发送。',
    'auth.confirmEmail.verify': '验证并继续',
    'auth.confirmEmail.verifying': '正在验证…',
    'auth.confirmEmail.resend': '没有收到？重新发送验证码',
    'auth.error.thirdParty': '第三方登录失败，请重试。',
    'auth.error.session': '无法建立登录会话，请重试。',
    'auth.error.google': 'Google 登录失败。',
    'auth.error.signIn': '登录失败。',
    'auth.error.signUp': '注册失败。',
    'auth.error.verify': '验证失败。',
    'auth.login.title': '登录 Studio',
    'auth.login.subtitle': '管理你的 nubase 项目、数据库和租户。',
    'auth.login.google': '使用 Google 继续',
    'auth.login.github': '使用 GitHub 继续',
    'auth.login.or': '或',
    'auth.login.submit': '登录',
    'auth.login.submitting': '正在登录…',
    'auth.login.noAccount': '还没有账号？',
    'auth.login.signUp': '注册',
    'auth.signup.closedTitle': '注册已关闭',
    'auth.signup.closedBody': '当前工作区不接受公开注册。请联系已有 super admin 通过项目成员或平台用户页面邀请你。',
    'auth.signup.back': '返回登录',
    'auth.signup.title': '创建 Studio 账号',
    'auth.signup.subtitle': '用于管理所有 nubase 项目的平台管理员账号。',
    'auth.signup.passwordHelp': '至少 8 个字符。',
    'auth.signup.submit': '创建账号',
    'auth.signup.submitting': '正在创建账号…',
    'auth.signup.hasAccount': '已经有账号？',
    'auth.signup.signIn': '登录',

    'shell.nav.allProjects': '全部项目',
    'shell.nav.newProject': '新建项目',
    'shell.nav.account': '账号',
    'shell.nav.home': '首页',
    'shell.nav.tableEditor': '表编辑器',
    'shell.nav.sqlEditor': 'SQL 编辑器',
    'shell.nav.authentication': '认证',
    'shell.nav.storage': '存储',
    'shell.nav.assets': '静态资源',
    'shell.nav.memory': '记忆',
    'shell.nav.aiGateway': 'AI 网关',
    'shell.nav.functions': '函数',
    'shell.nav.cron': '定时任务',
    'shell.nav.connectAgent': '连接 Agent',
    'shell.nav.logs': '日志',
    'shell.nav.settings': '设置',
    'shell.section.project': '项目',
    'shell.section.workspace': '工作区',
    'shell.version': 'v0.1 · 自托管',
    'shell.expand': '展开侧边栏',
    'shell.collapse': '收起侧边栏',
    'shell.workspace': '工作区',
    'shell.local': '本地',
    'shell.ready': '就绪',
    'shell.switchProject': '切换项目',
    'shell.noProjects': '暂无可用项目。',
    'shell.projectWarning': '项目状态为 {status}，底层数据库尚未完成开通。数据库、认证和存储页面将为空。',

    'theme.light': '切换为浅色主题',
    'theme.dark': '切换为深色主题',
    'user.menu': '账号菜单',
    'user.signedIn': '已登录',
    'user.platformUsers': '平台用户',
    'user.platformSettings': '平台设置',
    'user.signOut': '退出登录',

    'projects.scope': '工作区',
    'projects.superAdmin': 'super admin',
    'projects.titleAll': '全部项目',
    'projects.titleOwn': '我的项目',
    'projects.subtitle': '选择一个项目来管理数据库、认证、存储和记忆。列表会按当前平台密钥权限过滤。',
    'projects.new': '新建项目',
    'projects.total': '总数',
    'projects.ready': '就绪',
    'projects.pending': '等待中',
    'projects.failed': '需要处理',
    'projects.search': '按名称、ref 或描述搜索项目',
    'projects.gridView': '网格视图',
    'projects.listView': '列表视图',
    'projects.grid': '网格',
    'projects.list': '列表',
    'projects.loadError': '加载项目失败。',
    'projects.emptyTitle': '暂无项目',
    'projects.emptySearchTitle': '没有匹配的项目',
    'projects.emptyBody': '创建一个项目配置即可开始。',
    'projects.emptySearchBody': '尝试其他名称、ref 或描述。',
    'projects.createFirst': '创建第一个项目',
    'projects.noDescription': '未提供描述。',
    'projects.schema': 'schema',
    'projects.project': '项目',
    'projects.reference': '引用标识',
    'projects.status': '状态',
    'projects.showing': '显示第 {start}-{end} 条，共 {total} 条',
    'projects.prev': '上一页',
    'projects.next': '下一页',
    'projects.page': '第 {page} 页，共 {total} 页',

    'newProject.title': '新建项目',
    'newProject.subtitle': '保存项目配置。Postgres 数据库会在下一页自动开通。',
    'newProject.name': '项目名称',
    'newProject.namePlaceholder': '我的 CRM',
    'newProject.nameHelp': '显示在仪表盘中的名称。',
    'newProject.ref': '引用标识',
    'newProject.refHelp': '小写字母、数字和下划线。用作 API 路径片段、数据库名称和 JWT ref claim。创建后不可修改。',
    'newProject.description': '描述（选填）',
    'newProject.descriptionPlaceholder': '内部 CRM 数据',
    'newProject.region': '区域',
    'newProject.regionHelp': '当前仅用于展示。自托管 nubase 使用单个 Postgres 主机。',
    'newProject.cancel': '取消',
    'newProject.create': '创建项目',
    'newProject.creating': '正在创建…',
    'newProject.created': '项目已创建',
    'newProject.initializing': '正在初始化数据库…',
    'newProject.createFailed': '创建失败',
    'newProject.failed': '创建项目失败。',

    'account.title': '账号',
    'account.subtitle': '你的平台用户资料。',
    'account.profile': '资料',
    'account.profileDescription': '调用平台 API 时使用的身份。',
    'account.fullName': '姓名',
    'account.role': '角色',
    'account.userId': '用户 ID',
    'account.roleSuper': '你可以查看当前工作区中的所有项目。',
    'account.roleUser': '你只能查看自己拥有或受邀加入的项目。',
    'account.security': '安全',
    'account.securityDescription': '修改密码。我们会通过邮件发送验证码来确认是你本人操作。',
    'account.currentPassword': '当前密码',
    'account.newPassword': '新密码',
    'account.passwordOtpSent': '我们已向你发送 6 位确认验证码。',
    'account.passwordOtpFailed': '无法开始密码修改流程。',
    'account.passwordUpdated': '密码已更新。',
    'account.passwordChangeFailed': '无法修改密码。',
    'account.sendingCode': '正在发送验证码…',
    'account.continue': '继续',
    'account.confirmationCode': '确认验证码',
    'account.updating': '正在更新…',
    'account.changePassword': '修改密码',
    'account.cancel': '取消',
  },
} as const;

export type MessageKey = keyof typeof messages.en;

interface I18nContextValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  tr: (key: MessageKey, values?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nContextValue | null>(null);

function isLocale(value: string | null): value is Locale {
  return value === 'en' || value === 'zh-CN';
}

export function t(locale: Locale, key: MessageKey, values: Record<string, string | number> = {}) {
  let text: string = messages[locale][key] ?? messages.en[key] ?? key;
  for (const [name, value] of Object.entries(values)) {
    text = text.replaceAll(`{${name}}`, String(value));
  }
  return text;
}

function initialLocale(): Locale {
  if (typeof window === 'undefined') return 'en';
  const stored = safeGetStoredLocale();
  if (isLocale(stored)) return stored;
  const browser = window.navigator.language;
  return browser.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en';
}

function safeGetStoredLocale() {
  try {
    return window.localStorage?.getItem(LOCALE_KEY) ?? null;
  } catch {
    return null;
  }
}

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>('en');

  useEffect(() => {
    setLocaleState(initialLocale());
  }, []);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const value = useMemo<I18nContextValue>(() => {
    const setLocale = (next: Locale) => {
      setLocaleState(next);
      try {
        window.localStorage?.setItem(LOCALE_KEY, next);
      } catch {
        // 私密模式或禁用存储时，语言选择仍在当前会话内生效。
      }
    };
    return {
      locale,
      setLocale,
      tr: (key, values) => t(locale, key, values),
    };
  }, [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}

export function LanguageToggle({ className }: { className?: string }) {
  const { locale, setLocale, tr } = useI18n();
  const next = locale === 'en' ? 'zh-CN' : 'en';
  const label = locale === 'en' ? tr('language.switchToChinese') : tr('language.switchToEnglish');
  return (
    <button
      type="button"
      onClick={() => setLocale(next)}
      className={
        'inline-flex items-center gap-1.5 rounded-md border border-transparent px-2 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-border hover:bg-accent hover:text-foreground ' +
        (className ?? '')
      }
      aria-label={label}
      title={label}
    >
      <Languages className="h-3.5 w-3.5" />
      <span>{locale === 'en' ? tr('language.chinese') : tr('language.english')}</span>
    </button>
  );
}
