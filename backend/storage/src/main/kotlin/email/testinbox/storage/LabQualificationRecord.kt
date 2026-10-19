package email.testinbox.storage

import email.testinbox.application.QualificationProperties
import email.testinbox.application.storage.QualificationRecord
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Loads the lab-only qualification record a deployment points at, if any
 * (issue #102). Loading is strict ([QualificationRecords.loadLab]); whether
 * the record may be USED is `DeploymentSafety`'s decision, and it says no
 * everywhere but the `lab` environment. The log line is deliberately loud:
 * a lab record in a running process is never a quiet fact.
 */
object LabQualificationRecord {
    private val log = LoggerFactory.getLogger(LabQualificationRecord::class.java)

    fun load(properties: QualificationProperties): List<QualificationRecord> {
        val path = properties.labRecord ?: return emptyList()
        val record = QualificationRecords.loadLab(Path.of(path))
        log.warn(
            "LAB QUALIFICATION RECORD IN USE: '{}' from {} — a startup gate for the isolated lab only; refused unless " +
                "testinbox.deployment.environment is 'lab'; never enablement evidence (issue #102)",
            record.recordId,
            path,
        )
        return listOf(record)
    }
}
