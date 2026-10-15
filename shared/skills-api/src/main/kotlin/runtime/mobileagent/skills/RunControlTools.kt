// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

/**
 * The single spelling of the Run-owned control tool names.  Job and batch
 * executors, the app's routing and the confirmation policy all read these, so
 * a rename cannot leave one site behind.
 */
object RunControlTools {
    const val JOB_START = "job_start"
    const val JOB_OUTPUT = "job_output"
    const val JOB_LIST = "job_list"
    const val JOB_CANCEL = "job_cancel"
    const val READ_BATCH = "read_batch"

    val JOB_NAMES: Set<String> = setOf(JOB_START, JOB_OUTPUT, JOB_LIST, JOB_CANCEL)

    /** Never eligible as a job or batch child, so control tools cannot nest. */
    val ALL: Set<String> = JOB_NAMES + READ_BATCH
}
