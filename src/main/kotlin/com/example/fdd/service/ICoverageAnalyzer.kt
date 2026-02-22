package com.example.fdd.service

import com.example.fdd.model.CoverageReport
import com.example.fdd.model.DriftReport
import org.hl7.fhir.r4.model.StructureDefinition

interface ICoverageAnalyzer {
    fun analyze(driftReport: DriftReport, fml: String, targetSd: StructureDefinition? = null): CoverageReport
}
