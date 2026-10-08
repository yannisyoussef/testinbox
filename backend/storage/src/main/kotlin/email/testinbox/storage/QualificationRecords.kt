package email.testinbox.storage

import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.StorageBackendIdentity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reads the ADR-035 §9a qualification records shipped INSIDE this artifact:
 * `adr035-qualification/index.txt` names one JSON file per record, and each
 * file is the machine-readable compatibility contract `DeploymentSafety`
 * compares a declared backend identity against.
 *
 * Strict by construction: a record missing a required member, or carrying a
 * member of the wrong type, fails to load, and a non-OFF deployment then has
 * no record to match and refuses to start. A malformed record can never read
 * as "qualified" (TI-STORAGE-006 §53: malformed qualification records).
 */
object QualificationRecords {
    const val INDEX = "adr035-qualification/index.txt"
    const val SCHEMA_VERSION = 1

    private val mapper = JsonMapper.builder().build()

    /** Every record the artifact ships, in index order. */
    fun load(loader: ClassLoader = QualificationRecords::class.java.classLoader): List<QualificationRecord> {
        val index = loader.getResource(INDEX) ?: return emptyList()
        return index
            .readText()
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { name ->
                val resource =
                    loader.getResource("adr035-qualification/$name")
                        ?: throw QualificationRecordException("qualification index names '$name', which is not in the artifact")
                parse(resource.readText(), name)
            }.toList()
    }

    fun parse(
        json: String,
        source: String = "<inline>",
    ): QualificationRecord {
        val root =
            runCatching {
                mapper.readTree(
                    json,
                )
            }.getOrElse { throw QualificationRecordException("$source: not valid JSON: ${it.message}") }
        val reader = Reader(source, root)
        val version = reader.int("schemaVersion")
        if (version != SCHEMA_VERSION) throw QualificationRecordException("$source: schemaVersion $version is not $SCHEMA_VERSION")
        val minio = reader.node("minio")
        val host = reader.node("host")
        val network = reader.node("network")
        val qualification = reader.node("qualification")
        val runtime = minio.node("runtimeConfig")
        return QualificationRecord(
            recordId = reader.string("recordId"),
            qualifiedAt = reader.string("qualifiedAt"),
            identity =
                StorageBackendIdentity(
                    imageIndexDigest = minio.string("imageIndexDigest"),
                    platformMemberDigest = minio.string("platformMemberDigest"),
                    release = minio.string("release"),
                    commitId = minio.string("commitId"),
                    mode = minio.string("mode"),
                    driveCount = minio.int("driveCount"),
                    timeoutEnvironment = minio.stringMap("timeoutEnvironment"),
                    timeoutCliFlags = minio.stringList("timeoutCliFlags"),
                    runtimeConfigHash = runtime.nullableString("hash"),
                    kernelRelease = host.string("kernelRelease"),
                    filesystemType = host.string("filesystemType"),
                    mountOptions = host.string("mountOptions"),
                    storageClassInlineDefaults = minio.string("storageClassInlineDefaults"),
                    directPath = network.boolean("directPath"),
                    proxy = network.string("proxy"),
                ),
            uploadImplementationVersion = reader.string("uploadImplementationVersion"),
            platformClass = qualification.string("platformClass"),
            evidenceReference = qualification.string("evidenceReference"),
            scenarios = qualification.stringList("scenarios"),
            slowWExecuted = qualification.boolean("slowWExecuted"),
            enablementEligible = reader.boolean("enablementEligible"),
            ineligibilityReasons = reader.stringList("ineligibilityReasons"),
        )
    }

    /** Typed, required-by-default access: an absent or mistyped member is an error naming its path. */
    private class Reader(
        private val path: String,
        private val node: JsonNode,
    ) {
        private fun member(name: String): JsonNode =
            node.get(name)?.takeUnless { it.isNull } ?: throw QualificationRecordException("$path: '$name' is required")

        fun node(name: String): Reader = Reader("$path.$name", member(name).also { if (!it.isObject) fail(name, "an object") })

        fun string(name: String): String =
            member(name)
                .also {
                    if (!it.isString) fail(name, "a string")
                }.asString()
                .ifBlank { fail(name, "a non-blank string") }

        fun nullableString(name: String): String? {
            val value = node.get(name) ?: return null
            if (value.isNull) return null
            if (!value.isString) fail(name, "a string or null")
            return value.asString().ifBlank { null }
        }

        fun int(name: String): Int = member(name).also { if (!it.isIntegralNumber) fail(name, "an integer") }.asInt()

        fun boolean(name: String): Boolean = member(name).also { if (!it.isBoolean) fail(name, "a boolean") }.asBoolean()

        fun stringList(name: String): List<String> {
            val array = member(name).also { if (!it.isArray) fail(name, "an array of strings") }
            return array
                .iterator()
                .asSequence()
                .map { if (!it.isString) fail(name, "an array of strings") else it.asString() }
                .toList()
        }

        fun stringMap(name: String): Map<String, String> {
            val obj = member(name).also { if (!it.isObject) fail(name, "an object of string values") }
            return obj.properties().associate { (key, value) ->
                if (!value.isString) fail(name, "an object of string values")
                key to value.asString()
            }
        }

        private fun fail(
            name: String,
            expected: String,
        ): Nothing = throw QualificationRecordException("$path.$name must be $expected")
    }
}

class QualificationRecordException(
    message: String,
) : RuntimeException(message)
