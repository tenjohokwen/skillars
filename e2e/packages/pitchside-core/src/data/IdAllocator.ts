export interface IdAllocatorOptions {
  /**
   * First id of the whole UI-suite range. Pick a range the app's own integration fixtures never
   * use, so a UI-seeded row can never collide with a backend test's.
   */
  base: bigint
  /** Ids per worker. Defaults to one million. */
  stride?: bigint
}

/**
 * Collision-free numeric ids per Playwright worker: `base + workerIndex * stride + counter`.
 *
 * Keyed on `workerIndex`, not `parallelIndex`: Playwright restarts a worker after a failure with
 * the same parallelIndex but a NEW workerIndex, and the replacement must not reuse ids whose rows
 * the dead worker already wrote.
 */
export class IdAllocator {
  private counter = 0n
  private readonly start: bigint
  private readonly stride: bigint

  constructor(
    readonly workerIndex: number,
    options: IdAllocatorOptions,
  ) {
    if (!Number.isInteger(workerIndex) || workerIndex < 0) {
      throw new Error(`workerIndex must be a non-negative integer, got ${workerIndex}`)
    }
    this.stride = options.stride ?? 1_000_000n
    if (this.stride <= 0n) throw new Error('stride must be positive')
    if (options.base < 0n) throw new Error('base must not be negative')
    this.start = options.base + BigInt(workerIndex) * this.stride
  }

  next(): bigint {
    if (this.counter >= this.stride) {
      throw new Error(`Worker ${this.workerIndex} exhausted its id range of ${this.stride}`)
    }
    const id = this.start + this.counter
    this.counter += 1n
    return id
  }

  /** As a decimal string — what a JSON body or a quoted cookie value needs. */
  nextString(): string {
    return this.next().toString()
  }

  /** Inclusive range owned by this worker, e.g. for a range-scoped cleanup query. */
  range(): { from: bigint; to: bigint } {
    return { from: this.start, to: this.start + this.stride - 1n }
  }

  owns(id: bigint): boolean {
    const { from, to } = this.range()
    return id >= from && id <= to
  }
}
