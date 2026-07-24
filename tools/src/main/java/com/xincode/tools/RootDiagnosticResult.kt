package com.xincode.tools

/** Result of root diagnostic checks. */
data class RootDiagnosticResult(
    val id: String = "",
    val whoami: String = "",
    val lsSdcard: String = "",
    val catSystemBuild: String = "",
    val lsDataData: String = "",
    val errors: List<String> = emptyList()
)