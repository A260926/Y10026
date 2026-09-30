package de.jensklingenberg.ktorfit

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import de.jensklingenberg.ktorfit.model.KtorfitError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueryAnnotationsTest {
    @Test
    fun whenNotEncodedQueryAnnotationAndEncodedFound() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Query

interface TestService {

    @GET("posts")
    suspend fun test(@Query("name") testQuery: String, @Query(encoded = true) testQuery2: Int)
}
    """,
            )

        val expectedQueriesArgumentText =
            "url{\n" +
                "        takeFrom(_baseUrl + \"posts\")\n" +
                "        testQuery?.let{ parameter(\"name\", \"\$it\") }\n" +
                "        testQuery2?.let{ encodedParameters.append(\"testQuery2\", \"\$it\") }\n" +
                "        }"

        val compilation = getCompilation(listOf(source))
        val result = compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_TestServiceImpl.kt",
            )

        val actualSource = generatedFile.readText()
        assertTrue(actualSource.contains(expectedQueriesArgumentText))
    }

    @Test
    fun whenQueryAnnotationWithListFound() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Query

interface TestService {

    @GET("posts")
    suspend fun test(@Query("user",true) testQuery2: List<String>)
}
    """,
            )

        val expectedQueriesArgumentText =
            "url{\n" +
                "        takeFrom(_baseUrl + \"posts\")\n" +
                "        testQuery2?.filterNotNull()?.forEach { encodedParameters.append(\"user\", \"\$it\") }\n" +
                "        }"

        val compilation = getCompilation(listOf(source))
        val result = compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_TestServiceImpl.kt",
            )

        val actualSource = generatedFile.readText()
        assertTrue(actualSource.contains(expectedQueriesArgumentText))
    }

    @Test
    fun whenQueryNamesFound() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryName

interface TestService {
    @GET("posts")
    suspend fun test(@QueryName testQueryName: String, @QueryName testQueryName2: String)
}
    """,
            )

        val expectedQueriesArgumentText =
            "url{\n" +
                "        takeFrom(_baseUrl + \"posts\")\n" +
                "        parameters.appendAll(\"\$testQueryName\", emptyList())\n" +
                "        parameters.appendAll(\"\$testQueryName2\", emptyList())\n" +
                "        }"

        val compilation = getCompilation(listOf(source))
        val result = compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_TestServiceImpl.kt",
            )

        val actualSource = generatedFile.readText()
        assertTrue(actualSource.contains(expectedQueriesArgumentText))
    }

    @Test
    fun whenEncodedQueryNameCollectionFoundUseEncodedParameters() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryName

interface EncodedQueryNameTestApi {
    @GET("posts")
    suspend fun encodedList(@QueryName(true) names: List<String?>?)

    @GET("posts")
    suspend fun notEncodedList(@QueryName(false) names: List<String?>?)

    @GET("posts")
    suspend fun encodedArray(@QueryName(encoded = true) names: Array<String?>?)

    @GET("posts")
    suspend fun encodedScalar(@QueryName(encoded = true) name: String)
}
    """,
            )

        val compilation = getCompilation(listOf(source))
        compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_EncodedQueryNameTestApiImpl.kt",
            )

        val actualSource = generatedFile.readText()

        fun urlBlockOf(functionSignature: String): String {
            val start = actualSource.indexOf(functionSignature)
            assertTrue("generated function not found: $functionSignature", start >= 0)
            val blockStart = actualSource.indexOf("url{", start)
            val blockEnd = actualSource.indexOf("}", blockStart)
            return actualSource.substring(blockStart, blockEnd)
        }

        // Nullable List + encoded=true: null collection must be skipped, non-null members
        // must reach encodedParameters so '%' and '+' are not encoded a second time.
        val encodedListBlock =
            urlBlockOf("override suspend fun encodedList(names: List<String?>?)")
        assertTrue(encodedListBlock, encodedListBlock.contains("names?.filterNotNull()"))
        assertTrue(
            encodedListBlock,
            encodedListBlock.contains(
                "encodedParameters.appendAll(\"\$it\", emptyList())",
            ),
        )
        assertTrue(
            encodedListBlock,
            !encodedListBlock.contains("parameters.appendAll"),
        )

        // Nullable List + encoded=false stays on the regular parameters builder.
        val notEncodedListBlock =
            urlBlockOf("override suspend fun notEncodedList(names: List<String?>?)")
        assertTrue(
            notEncodedListBlock,
            notEncodedListBlock.contains(
                "parameters.appendAll(\"\$it\", emptyList())",
            ),
        )
        assertTrue(
            notEncodedListBlock,
            !notEncodedListBlock.contains("encodedParameters"),
        )

        // Nullable Array shares the collection branch and must use encodedParameters too.
        val encodedArrayBlock =
            urlBlockOf("override suspend fun encodedArray(names: Array<String?>?)")
        assertTrue(encodedArrayBlock, encodedArrayBlock.contains("names?.filterNotNull()"))
        assertTrue(
            encodedArrayBlock,
            encodedArrayBlock.contains(
                "encodedParameters.appendAll(\"\$it\", emptyList())",
            ),
        )

        // The scalar encoded branch was already correct and must stay unchanged.
        val encodedScalarBlock =
            urlBlockOf("override suspend fun encodedScalar(name: String)")
        assertTrue(
            encodedScalarBlock,
            encodedScalarBlock.contains(
                "encodedParameters.appendAll(\"\$name\", emptyList())",
            ),
        )
    }

    @Test
    fun whenNotEncodedQueryMapAnnotationAndEncodedFound() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                contents = """package com.example.api
            import de.jensklingenberg.ktorfit.http.GET
            import de.jensklingenberg.ktorfit.http.QueryMap
            
            interface TestService {
            
            @GET("posts")
            suspend fun test(@QueryMap testQueryMap: Map<String, String>,@QueryMap(true) testQueryMap2: Map<String, Int?>)
                
            }
                """,
            )

        val expectedQueriesArgumentText =
            "url{\n" +
                "        takeFrom(_baseUrl + \"posts\")\n" +
                "        testQueryMap?.forEach { entry -> entry.value?.let{ parameter(entry.key, \"\${entry.value}\") } }\n" +
                "        testQueryMap2?.forEach { entry -> entry.value?.let{ encodedParameters.append(entry.key, \"\${entry.value}\") } }\n" +
                "        }"

        val compilation = getCompilation(listOf(source))
        val result = compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_TestServiceImpl.kt",
            )

        val actualSource = generatedFile.readText()
        assertTrue(actualSource.contains(expectedQueriesArgumentText))
    }

    @Test
    fun testFunctionWithQueryAndQueryNameAndQueryMap() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryMap
import de.jensklingenberg.ktorfit.http.Query
import de.jensklingenberg.ktorfit.http.QueryName

interface TestService {
@GET("posts")
fun example(@Query("name") testQuery: String, @QueryName testQueryName: String, @QueryMap(true) name: Map<String, String>) 
}
    """,
            )

        val expectedQueriesArgumentText =
            "url{\n" +
                "        takeFrom(_baseUrl + \"posts\")\n" +
                "        testQuery?.let{ parameter(\"name\", \"\$it\") }\n" +
                "        parameters.appendAll(\"\$testQueryName\", emptyList())\n" +
                "        name?.forEach { entry -> entry.value?.let{ encodedParameters.append(entry.key, \"\${entry.value}\") } }\n" +
                "        }"

        val compilation = getCompilation(listOf(source))
        val result = compilation.compile()

        val generatedSourcesDir = compilation.kspSourcesDir
        val generatedFile =
            File(
                generatedSourcesDir,
                "/kotlin/com/example/api/_TestServiceImpl.kt",
            )

        val actualSource = generatedFile.readText()
        assertTrue(actualSource.contains(expectedQueriesArgumentText))
    }

    @Test
    fun whenQueryMapTypeIsNotMap_ThrowCompilationError() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryMap

interface TestService {

    @GET("posts")
    suspend fun test(@QueryMap testQueryMap: String)
    
}
    """,
            )

        val compilation = getCompilation(listOf(source))

        val result = compilation.compile()

        assertTrue(result.messages.contains(KtorfitError.QUERY_MAP_PARAMETER_TYPE_MUST_BE_MAP))
    }

    @Test
    fun whenQueryMapKeysIsNotString_ThrowCompilationError() {
        val source =
            SourceFile.kotlin(
                "Source.kt",
                """
      package com.example.api
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryMap

interface TestService {

    @GET("posts")
    suspend fun test(@QueryMap() testQueryMap: Map<Int, String>)
    
}
    """,
            )

        val compilation = getCompilation(listOf(source))

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains(KtorfitError.QUERY_MAP_KEYS_MUST_BE_OF_TYPE_STRING))
    }
}
