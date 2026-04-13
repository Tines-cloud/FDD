package com.example.fdd.output

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.model.DriftReport
import com.example.fdd.output.impl.OutputStore
import jakarta.servlet.http.HttpServletRequest

interface IOutputStore {
    fun createContext(
        requestType: String,
        sourceLabel: String,
        targetLabel: String,
        requestPayload: Any
    ): OutputStore.OutputContext?

    fun attachToRequest(httpRequest: HttpServletRequest, context: OutputStore.OutputContext?)
    fun writeAnalyzeResult(context: OutputStore.OutputContext?, driftReport: DriftReport, responsePayload: Any)
    fun writeRepairResult(context: OutputStore.OutputContext?, response: RepairResponse)
    fun writeError(httpRequest: HttpServletRequest, error: ErrorResponse, ex: Exception)
}
