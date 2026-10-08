/**
 * Derive a clean OpenAI-style model alias from a GGUF filename:
 *   "Qwen3.6-35B-A3B-UD-Q4_K_XL.gguf" → "qwen3.6-35b-a3b"
 *   "Qwen3-Coder-30B-A3B-Instruct-Q6_K.gguf" → "qwen3-coder-30b-a3b-instruct"
 *   "Qwen3-0.6B-UD-Q8_K_XL.gguf" → "qwen3-0.6b"
 */
export function aliasFromPath(modelPath: string | null | undefined): string {
  if (!modelPath) return ''
  const filename = modelPath.split('/').pop() ?? ''
  const stem = filename.replace(/\.gguf$/i, '')
  const cleaned = stem
    // strip trailing "-UD-Q4_K_XL", "-IQ4_NL", "-Q6_K_M", etc.
    .replace(/[-_](UD[-_])?(I?Q\d+)[A-Z0-9_]*$/i, '')
    // strip trailing "-F16" / "-BF16" / "-F32"
    .replace(/[-_](F16|BF16|F32)$/i, '')
  return cleaned.toLowerCase()
}
