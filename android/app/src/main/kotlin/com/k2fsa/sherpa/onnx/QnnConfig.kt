// Copyright (c)  2023  Xiaomi Corporation (Apache-2.0), sherpa-onnx v1.13.8 kotlin-api
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
