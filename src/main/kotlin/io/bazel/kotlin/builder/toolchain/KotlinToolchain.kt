/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.toolchain

import io.bazel.kotlin.builder.utils.BazelRunFiles
import io.bazel.kotlin.builder.utils.resolveVerified
import io.bazel.kotlin.builder.utils.verified
import io.bazel.kotlin.builder.utils.verifiedPath
import org.jetbrains.kotlin.preloading.ClassPreloadingUtils
import org.jetbrains.kotlin.preloading.Preloader
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Method
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

class KotlinToolchain private constructor(
  val classLoader: ClassLoader,
  val kapt3Plugin: CompilerPlugin,
  val jvmAbiGen: CompilerPlugin,
  val skipCodeGen: CompilerPlugin,
  val jdepsGen: CompilerPlugin,
  val kspSymbolProcessingApi: CompilerPlugin,
  val kspSymbolProcessingCommandLine: CompilerPlugin,
) {

  companion object {
    private val JVM_ABI_PLUGIN by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@com_github_jetbrains_kotlin...jvm-abi-gen",
      ).toPath()
    }

    private val KAPT_PLUGIN by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@com_github_jetbrains_kotlin...kapt",
      ).toPath()
    }

    private val COMPILER by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@rules_kotlin...compiler",
      ).toPath()
    }

    private val SKIP_CODE_GEN_PLUGIN by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@rules_kotlin...skip-code-gen",
      ).toPath()
    }

    private val JDEPS_GEN_PLUGIN by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@rules_kotlin...jdeps-gen",
      ).toPath()
    }

    private val KOTLINC by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@com_github_jetbrains_kotlin...kotlin-compiler",
      ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_API by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@com_github_google_ksp...symbol-processing-api",
      ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_CMDLINE by lazy {
      BazelRunFiles.resolveVerifiedFromProperty(
        "@com_github_google_ksp...symbol-processing-cmdline",
      ).toPath()
    }

    internal val NO_ARGS = arrayOf<Any>()

    private val isJdk9OrNewer = !System.getProperty("java.version").startsWith("1.")

    private fun createClassLoader(javaHome: Path, baseJars: List<File>): ClassLoader =
      runCatching {
        ClassPreloadingUtils.preloadClasses(
          mutableListOf<File>().also {
            it += baseJars
            if (!isJdk9OrNewer) {
              it += javaHome.resolveVerified("lib", "tools.jar")
            }
          },
          Preloader.DEFAULT_CLASS_NUMBER_ESTIMATE,
          ClassLoader.getSystemClassLoader(),
          null,
        )
      }.onFailure {
        throw RuntimeException("$javaHome, $baseJars", it)
      }.getOrThrow()

    @JvmStatic
    fun createToolchain(): KotlinToolchain {
      return createToolchain(
        FileSystems.getDefault().getPath(System.getProperty("java.home")).let { path ->
          path.takeIf { !it.endsWith(Paths.get("jre")) } ?: path.parent
        }.verifiedPath(),
        KOTLINC.verified().absoluteFile,
        COMPILER.verified().absoluteFile,
        JVM_ABI_PLUGIN.verified().absoluteFile,
        SKIP_CODE_GEN_PLUGIN.verified().absoluteFile,
        JDEPS_GEN_PLUGIN.verified().absoluteFile,
        KAPT_PLUGIN.verified().absoluteFile,
        KSP_SYMBOL_PROCESSING_API.toFile(),
        KSP_SYMBOL_PROCESSING_CMDLINE.toFile(),
      )
    }

    @JvmStatic
    fun createToolchain(
      javaHome: Path,
      kotlinc: File,
      compiler: File,
      jvmAbiGenFile: File,
      skipCodeGenFile: File,
      jdepsGenFile: File,
      kaptFile: File,
      kspSymbolProcessingApi: File,
      kspSymbolProcessingCommandLine: File,
    ): KotlinToolchain {
      return KotlinToolchain(
        createClassLoader(
          javaHome,
          listOf(
            kotlinc,
            compiler,
            // plugins *must* be preloaded. Not doing so causes class conflicts
            // (and a NoClassDef err) in the compiler extension interfaces.
            // This may cause issues in accepting user defined compiler plugins.
            jvmAbiGenFile,
            skipCodeGenFile,
            jdepsGenFile,
            kspSymbolProcessingApi,
            kspSymbolProcessingCommandLine,
          ),
        ),
        jvmAbiGen = CompilerPlugin(
          jvmAbiGenFile.path,
          "org.jetbrains.kotlin.jvm.abi",
        ),
        skipCodeGen = CompilerPlugin(
          skipCodeGenFile.path,
          "io.bazel.kotlin.plugin.SkipCodeGen",
        ),
        jdepsGen = CompilerPlugin(
          jdepsGenFile.path,
          "io.bazel.kotlin.plugin.jdeps.JDepsGen",
        ),
        kapt3Plugin = CompilerPlugin(
          kaptFile.path,
          "org.jetbrains.kotlin.kapt3",
        ),
        kspSymbolProcessingApi = CompilerPlugin(
          kspSymbolProcessingApi.absolutePath,
          "com.google.devtools.ksp.symbol-processing",
        ),
        kspSymbolProcessingCommandLine = CompilerPlugin(
          kspSymbolProcessingCommandLine.absolutePath,
          "com.google.devtools.ksp.symbol-processing",
        ),
      )
    }
  }

  data class CompilerPlugin(val jarPath: String, val id: String)

  open class KotlinCliToolInvoker internal constructor(
    toolchain: KotlinToolchain,
    clazz: String,
  ) {
    private val compiler: Any
    private val execMethod: Method
    private val getCodeMethod: Method

    init {
      val compilerClass = toolchain.classLoader.loadClass(clazz)
      val exitCodeClass =
        toolchain.classLoader.loadClass("org.jetbrains.kotlin.cli.common.ExitCode")

      compiler = compilerClass.getConstructor().newInstance()
      execMethod =
        compilerClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
      getCodeMethod = exitCodeClass.getMethod("getCode")
    }

    // Kotlin error codes:
    // 1 is a standard compilation error
    // 2 is an internal error
    // 3 is the script execution error
    fun compile(args: Array<String>, out: PrintStream): Int {
      val exitCodeInstance = execMethod.invoke(compiler, out, args)
      return getCodeMethod.invoke(exitCodeInstance, *NO_ARGS) as Int
    }
  }

  @Singleton
  class KotlincInvoker @Inject constructor(
    toolchain: KotlinToolchain,
  ) : KotlinCliToolInvoker(toolchain, "io.bazel.kotlin.compiler.BazelK2JVMCompiler")

  @Singleton
  class K2JSCompilerInvoker @Inject constructor(
    toolchain: KotlinToolchain,
  ) : KotlinCliToolInvoker(toolchain, "org.jetbrains.kotlin.cli.js.K2JSCompiler")
}
