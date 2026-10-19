/**
 * Developer word pack for the Dictionary page. Every entry's spelling is enforced
 * in the final text (see applyDictionary), so "Fastfetch" at the start of a
 * sentence still comes out as `fastfetch`.
 *
 * Deliberately left out: names that are also everyday English words (fish, bash,
 * yay, grim, react, next, cursor, node, go, cargo, make, prettier). Forcing their
 * case would mangle ordinary sentences.
 */
export interface VocabularyEntry {
  word: string
  correction?: string
}

/** Written exactly like this. Terminal tools keep their lowercase command name. */
const SPELLINGS = [
  // Terminal and system tools
  'fastfetch', 'neofetch', 'btop', 'htop', 'nvim', 'tmux', 'zsh', 'sudo', 'chmod', 'chown',
  'mkdir', 'grep', 'ripgrep', 'fzf', 'eza', 'zoxide', 'lazygit', 'lazydocker', 'jq', 'yq',
  'curl', 'wget', 'ssh', 'rsync', 'scp', 'systemctl', 'journalctl', 'hyprctl', 'kubectl',
  'pacman', 'paru', 'makepkg', 'wtype', 'wl-copy', 'wl-paste', 'mise', 'uv', 'venv',
  'rustup', 'rustc', 'gcc', 'cmake', 'git', 'gh', 'npm', 'npx', 'pnpm', 'pnpx', 'bun', 'bunx',
  'localhost', 'stdout', 'stderr', 'stdin', 'dotfiles', 'env', 'webpack', 'esbuild',
  // Desktop
  'Omarchy', 'Hyprland', 'Wayland', 'Ghostty', 'Neovim', 'Waybar', 'Obsidian',
  // Languages, frameworks, libraries
  'TypeScript', 'JavaScript', 'Node.js', 'Next.js', 'Nuxt', 'Vue.js', 'Svelte', 'SvelteKit',
  'Vite', 'Vitest', 'Playwright', 'ESLint', 'Tailwind', 'shadcn', 'Zod', 'tRPC', 'Prisma',
  'GraphQL', 'Electron', 'Tauri', 'Deno', 'Python', 'FastAPI', 'Django',
  // Services and platforms
  'GitHub', 'GitLab', 'Vercel', 'Supabase', 'Cloudflare', 'Docker', 'Kubernetes',
  'Terraform', 'PostgreSQL', 'Postgres', 'SQLite', 'Redis', 'OpenRouter', 'OpenAI',
  'Anthropic', 'Claude Code', 'Deepgram', 'DeepSeek', 'Hugging Face', 'macOS', 'iOS',
  // Acronyms
  'API', 'APIs', 'CLI', 'SDK', 'URL', 'UUID', 'OAuth', 'JWT', 'CORS', 'CSS', 'HTML',
  'HTTP', 'HTTPS', 'JSON', 'YAML', 'TOML', 'SQL', 'README', 'PR', 'CI', 'WebSocket',
  'nodaysidle', 'reverseuno'
]

/** Spoken forms speech-to-text tends to produce, mapped to the written one. */
const SPOKEN: [string, string][] = [
  ['no days idle', 'nodaysidle'],
  ['nodays idle', 'nodaysidle'],
  ['no daysidle', 'nodaysidle'],
  ["no day's idle", 'nodaysidle'],
  ['reverse uno', 'reverseuno'],
  ['omar key', 'Omarchy'],
  ['omarki', 'Omarchy'],
  ['omarky', 'Omarchy'],
  ['hyper land', 'Hyprland'],
  ['hyperland', 'Hyprland'],
  ['fast fetch', 'fastfetch'],
  ['neo fetch', 'neofetch'],
  ['b top', 'btop'],
  ['h top', 'htop'],
  ['neo vim', 'Neovim'],
  ['lazy git', 'lazygit'],
  ['ghost tea', 'Ghostty'],
  ['open router', 'OpenRouter'],
  ['deep gram', 'Deepgram'],
  ['deep seek', 'DeepSeek'],
  ['type script', 'TypeScript'],
  ['java script', 'JavaScript'],
  ['node js', 'Node.js'],
  ['next js', 'Next.js'],
  ['vue js', 'Vue.js'],
  ['super base', 'Supabase'],
  ['git hub', 'GitHub'],
  ['git lab', 'GitLab'],
  ['vs code', 'VS Code'],
  ['web socket', 'WebSocket'],
  ['local host', 'localhost'],
  ['dot files', 'dotfiles'],
  ['p n p m', 'pnpm'],
  ['n p m', 'npm'],
  ['n p x', 'npx'],
  ['post gres', 'Postgres'],
  ['es lint', 'ESLint']
]

export const DEVELOPER_VOCABULARY: VocabularyEntry[] = [
  ...SPELLINGS.map((word) => ({ word })),
  ...SPOKEN.map(([word, correction]) => ({ word, correction }))
]
