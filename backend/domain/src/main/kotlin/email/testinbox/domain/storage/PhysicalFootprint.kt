package email.testinbox.domain.storage

/**
 * The physical-footprint bound of the filesystem-containment contract
 * (ADR-035 amendment proposal, part 2, §3; TI-STORAGE-006E).
 *
 * A payload of *p* bytes does not cost *p* bytes on the filesystem MinIO
 * writes to. It costs its block rounding, a fixed per-object overhead
 * (directories, `xl.meta`, an extent-tree block) and, for large part files,
 * extent-tree blocks proportional to its size. The contract bounds one object
 * by
 *
 * ```
 * φ(p) = ⌈p⌉_B · (1 + ε) + O_max
 * ```
 *
 * and a set of *N* objects of total payload *P* by the closed form
 *
 * ```
 * F(P, N) = (P + N · (B − 1)) · (1 + ε) + N · (O_max + 1)
 * ```
 *
 * which needs only the payload sum and the object count — the two quantities
 * the database ledger carries — and never under-counts `Σ φ(p_i)`: each
 * `⌈p_i⌉_B ≤ p_i + B − 1`, and the `+ N` absorbs the per-object rounding of
 * the ε allowance. `FootprintModelTest` proves the inequality by property.
 *
 * [blockSizeBytes] (*B*) and [objectOverheadMaxBytes] (*O_max*) are
 * properties of ONE storage combination, declared by the deployment and
 * qualified for it (the record carries the measured maximum); ε is the
 * constant `1 / fragmentationDenominator`, covering one ext4 extent-tree leaf
 * block per 340 data blocks in the worst case of one extent per block.
 *
 * All arithmetic is checked: an overflow is a configuration failure, never a
 * wrapped bound. Every division rounds UP.
 */
data class FootprintModel(
    val blockSizeBytes: Long,
    val objectOverheadMaxBytes: Long,
    val fragmentationDenominator: Long = maxFragmentationDenominator(blockSizeBytes),
) {
    init {
        require(blockSizeBytes in SUPPORTED_BLOCK_SIZES) { "block size must be one of $SUPPORTED_BLOCK_SIZES, was $blockSizeBytes" }
        require(objectOverheadMaxBytes > 0 && objectOverheadMaxBytes % blockSizeBytes == 0L) {
            "O_max must be a positive multiple of the block size, was $objectOverheadMaxBytes"
        }
        // The inode argument (§3.5): an object occupies at least O_max / B blocks
        // and at most INODES_PER_OBJECT_MAX inodes, so with one inode per block
        // inodes cannot run out before blocks. A model that cannot make that
        // argument is not constructible.
        require(objectOverheadMaxBytes / blockSizeBytes >= INODES_PER_OBJECT_MAX) {
            "O_max must cover at least $INODES_PER_OBJECT_MAX blocks (one per inode an object can take), was $objectOverheadMaxBytes"
        }
        // A larger denominator is a SMALLER allowance: ε may only grow. Below the
        // qualified 1/256 needs its own accepted qualification and a code change,
        // never a value a caller or a deployment can pass (owner review, §3).
        // The cap depends on B: a leaf block holds (B − 12)/12 extents, so the
        // worst-case ratio is 1/339 at 4 KiB but 1/168 at 2 KiB and 1/83 at 1 KiB.
        val max = maxFragmentationDenominator(blockSizeBytes)
        require(fragmentationDenominator in MIN_FRAGMENTATION_DENOMINATOR..max) {
            "the fragmentation denominator must be in $MIN_FRAGMENTATION_DENOMINATOR..$max for $blockSizeBytes B blocks " +
                "(ε ≥ 1/$max), was $fragmentationDenominator"
        }
    }

    /** `K = (B − 1) · (1 + ε) + O_max + 1`: the most one object costs above its payload under [bound]. */
    val perObjectCeilingBytes: Long = Math.addExact(withFragmentation(blockSizeBytes - 1), Math.addExact(objectOverheadMaxBytes, 1))

    /** `φ(p)`: the footprint bound of one object of [payloadBytes]. */
    fun ofObject(payloadBytes: Long): Long {
        require(payloadBytes >= 0) { "payload must not be negative, was $payloadBytes" }
        val rounded = Math.multiplyExact(ceilDiv(payloadBytes, blockSizeBytes), blockSizeBytes)
        return Math.addExact(withFragmentation(rounded), objectOverheadMaxBytes)
    }

    /** `F(P, N)`: the footprint bound of [objects] objects whose payloads sum to [payloadBytes]. */
    fun bound(
        payloadBytes: Long,
        objects: Long,
    ): Long {
        require(payloadBytes >= 0) { "payload must not be negative, was $payloadBytes" }
        require(objects >= 0) { "object count must not be negative, was $objects" }
        val base = Math.addExact(payloadBytes, Math.multiplyExact(objects, blockSizeBytes - 1))
        return Math.addExact(withFragmentation(base), Math.multiplyExact(objects, Math.addExact(objectOverheadMaxBytes, 1)))
    }

    /**
     * `H_F = processes × writes × F(maxObjectBytes, 1)`: the finalize budget of
     * ADR-035 §9 in footprint units. `F(p, 1)`, not φ(p): a late object that
     * leaves the uncovered class is charged the closed form's increment when
     * its debt row is written, and that increment is at most `F(p, 1)`
     * (contract §2.4, Lemma 3).
     */
    fun finalizeBudgetBytes(
        declaredMaxIngestionProcesses: Int,
        maxConcurrentWrites: Int,
        maxObjectBytes: Long,
    ): Long {
        require(declaredMaxIngestionProcesses > 0) { "declaredMaxIngestionProcesses must be positive" }
        require(maxConcurrentWrites > 0) { "maxConcurrentWrites must be positive" }
        return Math.multiplyExact(
            Math.multiplyExact(declaredMaxIngestionProcesses.toLong(), maxConcurrentWrites.toLong()),
            bound(maxObjectBytes, 1),
        )
    }

    /**
     * The fewest inodes a filesystem of [capacityBytes] usable bytes needs so
     * that TestInbox's objects exhaust blocks before inodes (§3.5): one per
     * block. `mkfs.ext4 -i 4096` at a 4 KiB block size.
     */
    fun minimumInodes(capacityBytes: Long): Long {
        require(capacityBytes >= 0) { "capacity must not be negative, was $capacityBytes" }
        return ceilDiv(capacityBytes, blockSizeBytes)
    }

    /** `x · (1 + ε)`, rounded up. */
    private fun withFragmentation(bytes: Long): Long = Math.addExact(bytes, ceilDiv(bytes, fragmentationDenominator))

    companion object {
        /** `ε = 1/256 > 1/339`, the worst-case extent-tree ratio (§3.4). */
        const val DEFAULT_FRAGMENTATION_DENOMINATOR: Long = 256

        /** Below this the allowance would exceed the extent worst case by an order of magnitude: a typo, not a policy. */
        const val MIN_FRAGMENTATION_DENOMINATOR: Long = 16

        /** ε ≥ 1/256 is what the qualified ext4 combination relies on (§3.4); a larger denominator is less conservative. */
        const val MAX_FRAGMENTATION_DENOMINATOR: Long = 256

        /** Bytes of an ext4 extent-tree header, and of one extent entry. */
        private const val EXTENT_ENTRY_BYTES: Long = 12

        /**
         * `min(256, ⌊(B − 12)/12⌋ − 1)`: 256 at 4 KiB, 168 at 2 KiB, 83 at 1 KiB
         * (§3.4). Strictly below the extents per leaf block, so ε exceeds the
         * worst-case proportional metadata at every supported block size.
         */
        fun maxFragmentationDenominator(blockSizeBytes: Long): Long {
            require(blockSizeBytes in SUPPORTED_BLOCK_SIZES) { "block size must be one of $SUPPORTED_BLOCK_SIZES, was $blockSizeBytes" }
            return minOf(MAX_FRAGMENTATION_DENOMINATOR, (blockSizeBytes - EXTENT_ENTRY_BYTES) / EXTENT_ENTRY_BYTES - 1)
        }

        /** Three or five files plus amortized directories (§3.5). */
        const val INODES_PER_OBJECT_MAX: Long = 6

        val SUPPORTED_BLOCK_SIZES: Set<Long> = setOf(1024, 2048, 4096)

        private const val KIB = 1024L

        /**
         * The reference model: ext4 at 4 KiB blocks and the proposed
         * `O_max` = 24 KiB (six blocks: `{message}/`, `{key}/`, `{uuid}/`,
         * `xl.meta`, one extent block, one block of slack). It is what an
         * `OFF` deployment observes with until a qualification record
         * declares the combination's own values.
         */
        val REFERENCE = FootprintModel(blockSizeBytes = 4 * KIB, objectOverheadMaxBytes = 24 * KIB)

        /** `⌈a / b⌉` for `a ≥ 0`, `b > 0`. */
        fun ceilDiv(
            a: Long,
            b: Long,
        ): Long {
            require(a >= 0 && b > 0) { "ceilDiv needs a ≥ 0 and b > 0, was $a / $b" }
            return Math.addExact(a, b - 1) / b
        }
    }
}

/**
 * The static containment condition of the contract (§2.1, I-C):
 *
 * ```
 * G_F + D_budget + M + R_ops ≤ C_fs
 * I_fs ≥ C_fs / B
 * ```
 *
 * Every term is a declaration; the application validates the inequality and
 * Ops verifies the filesystem (gate F). Pure arithmetic, checked.
 */
object FilesystemContainment {
    const val GIB: Long = 1024L * 1024 * 1024

    /** The least the filesystem must offer for the declared budgets: I-C, `G_F + D_budget + P_F + M + R_ops`. */
    fun requiredCapacityBytes(
        globalFootprintLimitBytes: Long,
        deletionDebtBudgetBytes: Long,
        metadataBudgetBytes: Long,
        operationalReserveBytes: Long,
        probeBudgetBytes: Long = 0,
    ): Long {
        require(probeBudgetBytes >= 0) { "P_F must not be negative, was $probeBudgetBytes" }
        require(globalFootprintLimitBytes > 0) { "G_F must be positive, was $globalFootprintLimitBytes" }
        require(deletionDebtBudgetBytes > 0) { "D_budget must be positive, was $deletionDebtBudgetBytes" }
        require(metadataBudgetBytes > 0) { "M must be positive, was $metadataBudgetBytes" }
        require(operationalReserveBytes > 0) { "R_ops must be positive, was $operationalReserveBytes" }
        return Math.addExact(
            Math.addExact(Math.addExact(globalFootprintLimitBytes, deletionDebtBudgetBytes), probeBudgetBytes),
            Math.addExact(metadataBudgetBytes, operationalReserveBytes),
        )
    }

    /** `max(5 % of C_fs, 2 GiB)`: what the recovery runbook needs, never planned for use. */
    fun minimumOperationalReserveBytes(capacityBytes: Long): Long {
        require(capacityBytes > 0) { "C_fs must be positive, was $capacityBytes" }
        return maxOf(capacityBytes / 20, 2 * GIB)
    }

    /** Whether a filesystem of [capacityBytes] and [inodes] satisfies I-C for [model] and the declared budgets. */
    fun holds(
        model: FootprintModel,
        capacityBytes: Long,
        inodes: Long,
        globalFootprintLimitBytes: Long,
        deletionDebtBudgetBytes: Long,
        metadataBudgetBytes: Long,
        operationalReserveBytes: Long,
    ): Boolean {
        require(inodes >= 0) { "I_fs must not be negative, was $inodes" }
        val required =
            requiredCapacityBytes(globalFootprintLimitBytes, deletionDebtBudgetBytes, metadataBudgetBytes, operationalReserveBytes)
        return required <= capacityBytes &&
            operationalReserveBytes >= minimumOperationalReserveBytes(capacityBytes) &&
            inodes >= model.minimumInodes(capacityBytes)
    }
}
