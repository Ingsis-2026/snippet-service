package com.ingsis.snippet

/** Un texto como literal JSON, con las comillas y los saltos de línea escapados. */
fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
