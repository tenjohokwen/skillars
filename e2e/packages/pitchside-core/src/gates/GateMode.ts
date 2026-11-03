/**
 * `strict`: a violation fails the test. `warn`: violations are attached to the report only.
 * `off`: the gate is not installed.
 */
export type GateMode = 'strict' | 'warn' | 'off'

export const GATE_MODES: readonly GateMode[] = ['strict', 'warn', 'off']

export function parseGateMode(value: string | undefined, fallback: GateMode): GateMode {
  if (value === undefined || value === '') return fallback
  if ((GATE_MODES as readonly string[]).includes(value)) return value as GateMode
  throw new Error(`Invalid gate mode "${value}": expected ${GATE_MODES.join(' | ')}`)
}

export class GateViolationError extends Error {
  constructor(gate: string, details: readonly string[]) {
    super(`${gate} gate: ${details.length} violation(s)\n  - ${details.join('\n  - ')}`)
    this.name = 'GateViolationError'
  }
}
