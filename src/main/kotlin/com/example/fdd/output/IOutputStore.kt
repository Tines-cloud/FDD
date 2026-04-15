package com.example.fdd.output

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.model.DriftReport
import jakarta.servlet.http.HttpServletRequest
import java.nio.file.Path

/**
 * Persists request/response artefacts (drift reports, FML code, coverage reports)
 * to timestamped directories for reproducibility and audit.
 */
interface IOutputStore {

    /**
     * Opaque context handle for a single request's output directory.
     */
    data class OutputContext(
        val directory: Path,
        val requestType: String
    )

    fun createContext(
        requestType: String,
        sourceLabel: String,
        targetLabel: String,
        requestPayload: Any
    ): OutputContext?

    fun attachToRequest(httpRequest: HttpServletRequest, context: OutputContext?)
    fun writeAnalyzeResult(context: OutputContext?, driftReport: DriftReport, responsePayload: Any)
    fun writeRepairResult(context: OutputContext?, response: RepairResponse)
    fun writeError(httpRequest: HttpServletRequest, error: ErrorResponse, ex: Exception)
}
