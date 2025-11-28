package dev.lockedfog.streamllm.utils

object JsonSanitizer {
    fun sanitize(input: String): String{
        var clean = input.trim()

        if(clean.startsWith("```")) {
            val firstLineBreak = clean.indexOf('\n')
            if(firstLineBreak != -1) {
                clean = clean.substring(firstLineBreak+1)
            }
            if(clean.endsWith("```")){
                clean = clean.dropLast(3)
            }
        }

        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.indexOf('}')

        if(firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace){
            clean = clean.substring(firstBrace,lastBrace+1)
        }

        return clean.trim()
    }
}