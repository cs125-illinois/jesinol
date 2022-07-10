@file:Suppress("InvalidPackageDeclaration")

package edu.illinois.cs.cs125.jenisol.core

import edu.illinois.cs.cs125.jenisol.core.generators.Complexity
import edu.illinois.cs.cs125.jenisol.core.generators.Generators
import edu.illinois.cs.cs125.jenisol.core.generators.ObjectGenerator
import edu.illinois.cs.cs125.jenisol.core.generators.ReceiverGenerator
import edu.illinois.cs.cs125.jenisol.core.generators.TypeGeneratorGenerator
import edu.illinois.cs.cs125.jenisol.core.generators.Value
import edu.illinois.cs.cs125.jenisol.core.generators.getArrayDimension
import java.lang.RuntimeException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.util.TreeMap
import kotlin.random.Random

class Submission(val solution: Solution, val submission: Class<*>) {
    init {
        if (!solution.solution.visibilityMatches(submission)) {
            throw SubmissionDesignClassError(
                submission,
                "is not ${solution.solution.getVisibilityModifier() ?: "package private"}"
            )
        }
        if (solution.solution.superclass != null && solution.solution.superclass != submission.superclass) {
            throw SubmissionDesignClassError(
                submission,
                "does not extend ${solution.solution.superclass.name}"
            )
        }
        val solutionInterfaces = solution.solution.interfaces.toSet()
        val submissionInterfaces = submission.interfaces.toSet()
        val missingInterfaces = solutionInterfaces.minus(submissionInterfaces)
        if (missingInterfaces.isNotEmpty()) {
            throw SubmissionDesignClassError(
                submission,
                "does not implement ${missingInterfaces.joinToString(separator = ", ") { it.name }}"
            )
        }
        val extraInterfaces = submissionInterfaces.minus(solutionInterfaces)
        if (extraInterfaces.isNotEmpty()) {
            throw SubmissionDesignClassError(
                submission,
                "does implements extra interfaces ${extraInterfaces.joinToString(separator = ", ") { it.name }}"
            )
        }
        solution.solution.typeParameters.forEachIndexed { i, type ->
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                if (!submission.typeParameters[i].bounds.contentEquals(type.bounds)) {
                    throw SubmissionTypeParameterError(submission)
                }
            } catch (e: Exception) {
                throw SubmissionTypeParameterError(submission)
            }
        }
        if (submission.typeParameters.size > solution.solution.typeParameters.size) {
            throw SubmissionTypeParameterError(submission)
        }
    }

    init {
        solution.bothExecutables.forEach {
            if (!it.parameterTypes[0].isAssignableFrom(submission)) {
                throw SubmissionDesignInheritanceError(
                    submission,
                    it.parameterTypes[0]
                )
            }
        }
    }

    private val submissionFields =
        solution.allFields.filter { it.name != "${"$"}assertionsDisabled" }.map { solutionField ->
            submission.findField(solutionField) ?: throw SubmissionDesignMissingFieldError(
                submission,
                solutionField
            )
        }.toSet()

    val submissionExecutables = solution.allExecutables
        .filter {
            !submission.isKotlin() || (!solution.skipReceiver || it !in solution.receiverGenerators)
        }.associate { solutionExecutable ->
            when (solutionExecutable) {
                is Constructor<*> -> submission.findConstructor(solutionExecutable, solution.solution)
                is Method -> submission.findMethod(solutionExecutable, solution.solution)
                else -> error("Encountered unexpected executable type: $solutionExecutable")
            }?.let { executable ->
                executable.isAccessible = true
                solutionExecutable to executable
            } ?: run {
                @Suppress("ComplexCondition")
                if (submission.isKotlin() &&
                    solutionExecutable is Method &&
                    (
                        solutionExecutable.name.startsWith("get") ||
                            solutionExecutable.name.startsWith("set")
                        )
                ) {
                    if (solutionExecutable.name.startsWith("get")) {
                        val field = solutionExecutable.name.removePrefix("get").let {
                            it[0].lowercaseChar() + it.substring(1)
                        }
                        throw SubmissionDesignKotlinNotAccessibleError(
                            submission,
                            field
                        )
                    } else {
                        val field = solutionExecutable.name.removePrefix("set").let {
                            it[0].lowercaseChar() + it.substring(1)
                        }
                        throw SubmissionDesignKotlinNotModifiableError(
                            submission,
                            field
                        )
                    }
                } else {
                    throw SubmissionDesignMissingMethodError(
                        submission,
                        solutionExecutable
                    )
                }
            }
        }.toMutableMap().also {
            if (solution.initializer != null) {
                it[solution.initializer] = solution.initializer
            }
        }.toMap()

    init {
        if (submission != solution.solution) {
            (submission.declaredMethods.toSet() + submission.declaredConstructors.toSet()).filter {
                !it.isPrivate() && !it.isSynthetic && !(it is Method && it.isBridge)
            }.forEach { executable ->
                if (executable !in submissionExecutables.values) {
                    if (submission.isKotlin()) {
                        @Suppress("MagicNumber")
                        if (executable is Method && executable.name.startsWith("get") && executable.name.length > 3) {
                            val setterName = executable.name.replace("get", "set")
                            if (submissionExecutables.values.map { it.name }.contains(setterName)) {
                                return@forEach
                            }
                        }
                        if (solution.skipReceiver && executable is Constructor<*>) {
                            return@forEach
                        }
                        if (executable.isKotlinCompanionAccessor()) {
                            return@forEach
                        }
                        if (executable is Constructor<*> &&
                            executable.parameterTypes.lastOrNull()?.name ==
                            "kotlin.jvm.internal.DefaultConstructorMarker"
                        ) {
                            return@forEach
                        }
                        @Suppress("EmptyCatchBlock")
                        try {
                            if (submission.kotlin.isData && executable.isDataClassGenerated()) {
                                return@forEach
                            }
                        } catch (_: UnsupportedOperationException) {
                        }
                        if (executable.name == "compareTo") {
                            return@forEach
                        }
                    }
                    @Suppress("ComplexCondition", "MagicNumber")
                    if (submission.isKotlin() && executable is Method &&
                        executable.name.length > 3 &&
                        (executable.name.startsWith("set") || executable.name.startsWith("get"))
                    ) {
                        if (executable.name.startsWith("set")) {
                            val field = executable.name.removePrefix("set").let {
                                it[0].lowercaseChar() + it.substring(1)
                            }
                            throw SubmissionDesignKotlinIsModifiableError(
                                submission,
                                field
                            )
                        } else {
                            val field = executable.name.removePrefix("get").let {
                                it[0].lowercaseChar() + it.substring(1)
                            }
                            throw SubmissionDesignKotlinIsAccessibleError(
                                submission,
                                field
                            )
                        }
                    }
                    throw SubmissionDesignExtraMethodError(
                        submission,
                        executable
                    )
                }
            }
            submission.declaredFields.toSet().filter {
                it.name != "${"$"}assertionsDisabled" &&
                    !(submission.isKotlin() && it.name == "Companion")
            }.forEach {
                if (!it.isPrivate() && it !in submissionFields) {
                    throw SubmissionDesignExtraFieldError(submission, it)
                }
                if (it.isStatic()) {
                    if (!solution.skipReceiver) {
                        throw SubmissionStaticFieldError(submission, it)
                    } else if (!it.isPrivate()) {
                        throw SubmissionStaticPublicFieldError(submission, it)
                    }
                }
            }
        }
    }

    private fun MutableList<TestRunner>.readyCount() = count { it.ready }

    private val comparators = Comparators(
        mutableMapOf(solution.solution to solution.receiverCompare, submission to solution.receiverCompare)
    )

    fun compare(solution: Any?, submission: Any?, solutionClass: Class<*>? = null, submissionClass: Class<*>? = null) =
        when (solution) {
            null -> submission == null
            else -> solution.deepEquals(submission, comparators, solutionClass, submissionClass)
        }

    fun verify(executable: Executable, result: TestResult<*, *>) {
        solution.verifiers[executable]?.also { customVerifier ->
            @Suppress("TooGenericExceptionCaught")
            try {
                unwrap { customVerifier.invoke(null, result) }
            } catch (e: ThreadDeath) {
                throw e
            } catch (e: Throwable) {
                result.differs.add(TestResult.Differs.VERIFIER_THREW)
                result.verifierThrew = e
            }
        } ?: run {
            defaultVerify(result)
        }
    }

    @Suppress("ComplexMethod", "LongMethod")
    private fun defaultVerify(result: TestResult<*, *>) {
        val solution = result.solution
        val submission = result.submission

        val strictOutput = result.solutionExecutable.annotations.find { it is Configure }?.let {
            (it as Configure).strictOutput
        } ?: false

        if (!compare(solution.threw, submission.threw, result.solutionClass, result.submissionClass)) {
            result.differs.add(TestResult.Differs.THREW)
        }
        if ((strictOutput || solution.stdout.isNotBlank()) && solution.stdout != submission.stdout) {
            result.differs.add(TestResult.Differs.STDOUT)
            if (solution.stdout == submission.stdout + "\n") {
                result.message = if (result.submissionIsKotlin) {
                    "Output is missing a newline, maybe use println instead of print?"
                } else {
                    "Output is missing a newline, maybe use System.out.println instead of System.out.print?"
                }
            }
            if (solution.stdout + "\n" == submission.stdout) {
                result.message = if (result.submissionIsKotlin) {
                    "Output has an extra newline, maybe use print instead of println?"
                } else {
                    "Output has an extra newline, maybe use System.out.print instead of System.out.println?"
                }
            }
        }
        if ((strictOutput || solution.stderr.isNotBlank()) && solution.stderr != submission.stderr) {
            result.differs.add(TestResult.Differs.STDERR)
            if (solution.stdout == submission.stdout + "\n") {
                result.message =
                    "Error output is missing a newline, maybe use System.err.println instead of System.err.print?"
            }
            if (solution.stdout + "\n" == submission.stdout) {
                result.message =
                    "Error output has an extra newline, maybe use System.err.print instead of System.err.println?"
            }
        }

        if (result.type == TestResult.Type.METHOD || result.type == TestResult.Type.STATIC_METHOD) {
            val customCompare = if (solution.returned != null) {
                this.solution.customCompares.entries.find { (type, _) ->
                    type.isAssignableFrom(solution.returned::class.java)
                }
            } else {
                null
            }?.value
            if (customCompare != null && submission.returned != null) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    customCompare.invoke(null, solution.returned, submission.returned)
                } catch (e: Throwable) {
                    result.differs.add(TestResult.Differs.RETURN)
                    result.message = e.message
                }
            } else if (!compare(
                    solution.returned,
                    submission.returned,
                    result.solutionClass,
                    result.submissionClass
                )
            ) {
                result.differs.add(TestResult.Differs.RETURN)
            }
        }
        if (result.type == TestResult.Type.FACTORY_METHOD &&
            solution.returned != null &&
            solution.returned::class.java.isArray
        ) {
            @Suppress("ComplexCondition")
            if (submission.returned == null ||
                !submission.returned::class.java.isArray ||
                solution.returned::class.java.getArrayDimension()
                != submission.returned::class.java.getArrayDimension() ||
                (solution.returned as Array<*>).size != (submission.returned as Array<*>).size
            ) {
                result.differs.add(TestResult.Differs.RETURN)
            }
        }
        if (!compare(solution.parameters, submission.parameters, result.solutionClass, result.submissionClass)) {
            result.differs.add(TestResult.Differs.PARAMETERS)
        }
    }

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    fun List<TestRunner>.toResults(
        settings: Settings,
        recordingRandom: RecordingRandom,
        completed: Boolean = false,
        threw: Throwable? = null,
        timeout: Boolean = false,
        finishedReceivers: Boolean = true
    ) =
        TestResults(
            map { it.testResults as List<TestResult<Any, ParameterGroup>> }.flatten().sortedBy { it.stepCount },
            settings,
            completed,
            threw,
            timeout,
            finishedReceivers,
            skippedSteps = map { it.skippedTests }.flatten().sorted(),
            randomTrace = recordingRandom.finish()
        )

    private fun List<TestRunner>.failed() = filter { it.failed }.also { runners ->
        check(runners.all { it.lastComplexity != null }) { "Runner failed without recording complexity" }
    }.minByOrNull { it.lastComplexity!!.level }

    @Suppress("unused")
    private inner class ExecutablePicker(private val random: Random) {
        private val executableWeights =
            solution.methodsToTest.associateWith { solution.defaultMethodTestingWeight(it) }.toMutableMap()

        val executableChooser: TreeMap<Double, Executable>
        val total: Double

        init {
            var setTotal = 0.0
            executableChooser = TreeMap(
                solution.methodsToTest.associateWith { solution.defaultMethodTestingWeight(it) }
                    .map { (executable, weight) ->
                        setTotal += weight
                        setTotal to executable
                    }.toMap()
            )
            total = setTotal
        }

        private var previous: Executable? = null
        fun next(): Executable {
            var next = executableChooser.higherEntry(random.nextDouble() * total).value!!
            if (next == previous && solution.methodsToTest.size > 1) {
                next = executableChooser.higherEntry(random.nextDouble() * total).value!!
            }
            previous = next
            return next
        }
    }

    private fun genMethodIterator(random: Random): Sequence<Executable> {
        val executablePicker = ExecutablePicker(random)
        return sequence {
            yield(executablePicker.next())
        }
    }

    class RecordingRandom(seed: Long = Random.nextLong(), private val follow: List<Int>? = null) : Random() {
        private val random = Random(seed)
        private val trace = mutableListOf<Int>()
        private var currentIndex = 0
        override fun nextBits(bitCount: Int): Int {
            return random.nextBits(bitCount).also {
                trace += it
            }.also {
                if (follow != null && follow[currentIndex++] != it) {
                    throw FollowTraceException(currentIndex)
                }
            }
        }

        fun finish(): List<Int> {
            if (follow != null && currentIndex != trace.size) {
                throw FollowTraceException(currentIndex)
            }
            return trace.toList()
        }
    }

    @Suppress("LongMethod", "ComplexMethod", "ReturnCount", "NestedBlockDepth", "ThrowsCount")
    fun test(
        passedSettings: Settings = Settings(),
        captureOutput: CaptureOutput = ::defaultCaptureOutput,
        followTrace: List<Int>? = null
    ): TestResults {
        if (solution.solution.isDesignOnly()) {
            throw DesignOnlyTestingError(solution.solution)
        }
        val settings = solution.setCounts(Settings.DEFAULTS merge passedSettings)

        check(settings.runAll != null)
        check(!(settings.runAll && settings.shrink!!)) {
            "Running all tests combined with test shrinking produce inconsistent results"
        }

        if (!solution.skipReceiver) {
            if (solution.fauxStatic) {
                check(settings.receiverCount == 1) { "Incorrect receiver count" }
            } else {
                check(settings.receiverCount > 1) { "Incorrect receiver count" }
            }
        }

        val random = if (settings.seed == -1) {
            RecordingRandom(follow = followTrace)
        } else {
            RecordingRandom(settings.seed.toLong(), follow = followTrace)
        }

        val runners: MutableList<TestRunner> = mutableListOf()
        var stepCount = 0

        val receiverGenerators = sequence {
            while (true) {
                yieldAll(solution.receiverGenerators.toList().shuffled(random))
            }
        }

        val (receiverGenerator, generatorOverrides) = if (!solution.skipReceiver) {
            val receiverGenerator = ReceiverGenerator(random, mutableListOf())
            Pair(
                receiverGenerator,
                mutableMapOf(
                    (solution.solution as Type) to ({ _: Random -> receiverGenerator } as TypeGeneratorGenerator),
                    (Any::class.java as Type) to { r: Random ->
                        ObjectGenerator(
                            r,
                            receiverGenerator
                        )
                    }
                )
            )
        } else {
            Pair<ReceiverGenerator?, Map<Type, TypeGeneratorGenerator>>(null, mapOf())
        }

        val generators = solution.generatorFactory.get(random, settings, generatorOverrides)

        @Suppress("TooGenericExceptionCaught")
        try {
            fun addRunner(generators: Generators, receivers: Value<Any?>? = null) = TestRunner(
                runners.size,
                this,
                generators,
                receiverGenerators,
                captureOutput,
                genMethodIterator(random),
                receivers = receivers,
                settings = settings
            ).also { runner ->
                if (receivers == null && !solution.skipReceiver) {
                    runner.next(stepCount++)
                }
                runners.add(runner)
            }

            val neededReceivers = settings.receiverCount.coerceAtLeast(1)

            val startMultipleCount = if (settings.startMultipleCount != -1) {
                settings.startMultipleCount
            } else {
                solution.defaultMethodCount
            }

            if (solution.skipReceiver) {
                addRunner(generators).also {
                    check(it.ready) { "Static method receivers should start ready" }
                }
            }

            var totalCount = 0
            var receiverStepCount = 0
            var testStepCount = 0
            while (totalCount < settings.totalTestCount) {
                val finishedReceivers = runners.size >= neededReceivers
                if (Thread.interrupted()) {
                    return runners.toResults(settings, random, timeout = true, finishedReceivers = finishedReceivers)
                }
                if (receiverStepCount > settings.receiverCount * Settings.DEFAULT_RECEIVER_RETRIES) {
                    return runners.toResults(settings, random, finishedReceivers = false)
                }
                val usedRunner = if (runners.readyCount() < neededReceivers) {
                    check(!solution.skipReceiver) { "Static testing should never drop receivers" }
                    addRunner(generators).also { runner ->
                        receiverGenerator?.runners?.add(runner)
                    }.also {
                        if (it.ranLastTest || it.skippedLastTest) {
                            receiverStepCount++
                            totalCount++
                        }
                    }
                } else {
                    if (testStepCount < startMultipleCount) {
                        runners.first { it.ready }.next(stepCount++)
                    } else {
                        runners.filter { it.ready }.shuffled(random).first().next(stepCount++)
                    }.also {
                        if (it.ranLastTest || it.skippedLastTest) {
                            testStepCount++
                            totalCount++
                        }
                    }
                }
                runners.failed()?.also {
                    if ((!settings.shrink!! || it.lastComplexity!!.level <= Complexity.MIN) && !settings.runAll) {
                        return runners.toResults(settings, random, finishedReceivers = finishedReceivers)
                    }
                }
                if (usedRunner.returnedReceivers != null) {
                    usedRunner.returnedReceivers!!.forEach { returnedReceiver ->
                        addRunner(generators, returnedReceiver)
                    }
                    usedRunner.returnedReceivers = null
                }
            }
            return runners.toResults(settings, random, completed = true)
        } catch (e: FollowTraceException) {
            throw e
        } catch (e: Throwable) {
            if (settings.testing!!) {
                throw e
            }
            return runners.toResults(settings, random, threw = e)
        }
    }
}

sealed class SubmissionDesignError(message: String) : RuntimeException(message)
class SubmissionDesignMissingMethodError(klass: Class<*>, executable: Executable) : SubmissionDesignError(
    "Submission class ${klass.name} didn't provide ${
    if (executable.isStatic() && !klass.isKotlin()) {
        "static "
    } else {
        ""
    }
    }${
    if (executable is Method) {
        "method"
    } else {
        "constructor"
    }
    } ${executable.fullName(klass.isKotlin())}"
)

class SubmissionDesignKotlinNotAccessibleError(klass: Class<*>, field: String) : SubmissionDesignError(
    "Property $field on submission class ${klass.name} is not accessible (no getter is available)"
)

class SubmissionDesignKotlinNotModifiableError(klass: Class<*>, field: String) : SubmissionDesignError(
    "Property $field on submission class ${klass.name} is not modifiable (no setter is available)"
)

class SubmissionDesignKotlinIsAccessibleError(klass: Class<*>, field: String) : SubmissionDesignError(
    "Property $field on submission class ${klass.name} is accessible but should not be (getter is available)"
)

class SubmissionDesignKotlinIsModifiableError(klass: Class<*>, field: String) : SubmissionDesignError(
    "Property $field on submission class ${klass.name} is modifiable but should not be (setter is available)"
)

class SubmissionDesignExtraMethodError(klass: Class<*>, executable: Executable) : SubmissionDesignError(
    "Submission class ${klass.name} provided extra ${
    if (executable.isStatic() && !klass.isKotlin()) {
        "static "
    } else {
        ""
    }
    }${
    if (executable is Method) {
        "method"
    } else {
        "constructor"
    }
    } ${executable.fullName(klass.isKotlin())}"
)

class SubmissionDesignInheritanceError(klass: Class<*>, parent: Class<*>) : SubmissionDesignError(
    "Submission class ${klass.name} didn't inherit from ${parent.name}"
)

class SubmissionTypeParameterError(klass: Class<*>) : SubmissionDesignError(
    "Submission class ${klass.name} has missing, unnecessary, or incorrectly-bounded type parameters"
)

class SubmissionDesignMissingFieldError(klass: Class<*>, field: Field) : SubmissionDesignError(
    "Field ${field.fullName()} is not accessible in submission class ${klass.name} but should be"
)

class SubmissionDesignExtraFieldError(klass: Class<*>, field: Field) : SubmissionDesignError(
    "Field ${field.fullName()} is accessible in submission class ${klass.name} but should not be"
)

class SubmissionStaticFieldError(klass: Class<*>, field: Field) : SubmissionDesignError(
    "Field ${field.fullName()} is static in submission class ${klass.name}, " +
        "but static fields are not permitted for this problem"
)

class SubmissionStaticPublicFieldError(klass: Class<*>, field: Field) : SubmissionDesignError(
    "Static field ${field.fullName()} in submission class ${klass.name} must be private"
)

class SubmissionDesignClassError(klass: Class<*>, message: String) : SubmissionDesignError(
    "Submission class ${klass.name} $message"
)

class DesignOnlyTestingError(klass: Class<*>) : Exception(
    "Solution class ${klass.name} is marked as design only"
)

@Suppress("SwallowedException")
fun unwrap(run: () -> Any?): Any? = try {
    run()
} catch (e: InvocationTargetException) {
    throw e.cause ?: error("InvocationTargetException should have a cause")
}

fun Class<*>.isKotlin() = getAnnotation(Metadata::class.java) != null

class FollowTraceException(index: Int) : RuntimeException("Random generator out of sync at index $index")